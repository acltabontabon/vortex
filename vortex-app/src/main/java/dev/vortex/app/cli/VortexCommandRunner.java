package dev.vortex.app.cli;

import dev.vortex.app.service.TestRunner;
import dev.vortex.app.report.RunExporter;
import dev.vortex.core.evidence.ExportFormat;
import dev.vortex.core.application.CalibrationService;
import dev.vortex.core.application.ComparisonService;
import dev.vortex.core.application.PlanResolutionException;
import dev.vortex.core.application.ProjectService;
import dev.vortex.core.calibration.CalibrationPolicy;
import dev.vortex.core.calibration.WorkloadSuggestion;
import dev.vortex.core.calibration.WorkloadSuggestions;
import dev.vortex.core.capacity.ProductionObservation;
import dev.vortex.core.comparison.RegressionVerdict;
import dev.vortex.core.port.ProductionObservationSource;
import dev.vortex.core.shared.ProjectId;
import dev.vortex.core.threshold.Durations;
import dev.vortex.core.execution.ExecutionProgress;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.port.ConfigurationStore;
import dev.vortex.core.project.Project;
import dev.vortex.core.project.ProjectConfiguration;
import dev.vortex.core.shared.ExecutionId;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The command-line interface.
 *
 * <p>Three commands, chosen because between them they cover what a team needs before anything else:
 * find out whether the environment is set up, check that a configuration is valid, and run a test
 * from a pipeline with a meaningful exit code. Everything else can wait.
 *
 * <p>Both this and the web interface call the same application services. A tool whose CI mode is a
 * separate implementation will eventually disagree with its interactive mode about whether a build
 * passed, and by then nobody will trust either.
 *
 * <p>Written against {@code PrintStream} rather than a logger: this is a program's output, not
 * diagnostics, and it should be pipeable.
 */
@Component
public class VortexCommandRunner {

    private final DoctorReport doctor;
    private final ProjectService projects;
    private final ConfigurationStore configurationStore;
    private final TestRunner testRunner;
    private final ComparisonService comparisons;
    private final RunExporter exporter;
    private final CalibrationService calibration;
    private final CalibrationPolicy calibrationPolicy;
    private final PrintStream out;
    private final PrintStream err;

    @Autowired
    public VortexCommandRunner(DoctorReport doctor, ProjectService projects,
            ConfigurationStore configurationStore, TestRunner testRunner,
            ComparisonService comparisons, RunExporter exporter, CalibrationService calibration,
            CalibrationPolicy calibrationPolicy) {
        this(doctor, projects, configurationStore, testRunner, comparisons, exporter, calibration,
                calibrationPolicy, System.out, System.err);
    }

    /** Used by tests, which capture output rather than writing to the terminal. */
    VortexCommandRunner(DoctorReport doctor, ProjectService projects,
            ConfigurationStore configurationStore, TestRunner testRunner,
            ComparisonService comparisons, RunExporter exporter, CalibrationService calibration,
            CalibrationPolicy calibrationPolicy, PrintStream out, PrintStream err) {
        this.doctor = doctor;
        this.projects = projects;
        this.configurationStore = configurationStore;
        this.testRunner = testRunner;
        this.comparisons = comparisons;
        this.exporter = exporter;
        this.calibration = calibration;
        this.calibrationPolicy = calibrationPolicy;
        this.out = out;
        this.err = err;
    }

    public int run(String[] args) {
        if (args.length == 0) {
            printUsage();
            return ExitCode.SUCCESS.value();
        }

        String command = args[0];
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);

        try {
            return switch (command) {
                case "doctor" -> doctor();
                case "validate" -> validate(rest);
                case "calibrate" -> calibrate(rest);
                case "run" -> runTest(rest);
                case "compare" -> compare(rest);
                case "export" -> export(rest);
                case "help", "--help", "-h" -> {
                    printUsage();
                    yield ExitCode.SUCCESS.value();
                }
                default -> {
                    err.println("Unknown command: " + command);
                    err.println();
                    printUsage();
                    yield ExitCode.ERROR.value();
                }
            };
        } catch (RuntimeException e) {
            err.println("Vortex failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return ExitCode.ERROR.value();
        }
    }

    // ---------------------------------------------------------------- doctor

    private int doctor() {
        List<DoctorReport.Check> checks = doctor.run();

        out.println("Vortex Doctor");
        out.println();

        int width = checks.stream().mapToInt(check -> check.name().length()).max().orElse(10) + 2;
        for (DoctorReport.Check check : checks) {
            out.printf("  %-" + width + "s %s  %s%n",
                    check.name(), check.mark(), check.detail());
        }

        List<DoctorReport.Check> problems = checks.stream()
                .filter(check -> !check.isOk() && !check.remedy().isBlank())
                .toList();

        if (!problems.isEmpty()) {
            out.println();
            for (DoctorReport.Check check : problems) {
                out.println(check.required()
                        ? "Required — " + check.name()
                        : "Optional — " + check.name());
                check.remedy().lines().forEach(line -> out.println("  " + line));
                out.println();
            }
        }

        boolean ready = doctor.isReady(checks);
        out.println(ready
                ? "Vortex is ready to run tests."
                : "Vortex cannot run tests until the required items above are resolved.");

        return ready ? ExitCode.SUCCESS.value() : ExitCode.VALIDATION_FAILED.value();
    }

    // ---------------------------------------------------------------- validate

    private int validate(String[] args) {
        String path = args.length > 0 ? args[0] : ".";
        ConfigurationStore.LoadResult result = configurationStore.load(path);

        if (!result.isValid()) {
            return reportUnusableConfiguration(result);
        }

        ProjectConfiguration configuration = result.configuration();
        out.println(result.sourcePath() + " is valid.");
        out.println();
        out.println("  Service       " + (configuration.serviceName().isBlank()
                ? "(not named)" : configuration.serviceName()));
        out.println("  Environments  " + configuration.environments().size());
        out.println("  Workloads     " + configuration.workloads().stream()
                .map(workload -> workload.name()).toList());
        out.println("  Objectives    " + configuration.thresholds().size());

        if (configuration.thresholds().isEmpty()) {
            out.println();
            out.println("Note: no objectives are configured, so runs will produce measurements but "
                    + "no verdict.");
        }

        return ExitCode.SUCCESS.value();
    }

    // ---------------------------------------------------------------- calibrate

    /**
     * Fetches observed production traffic and shows the workloads it would propose.
     *
     * <p>Prints by default and writes only when asked. A calibration that edited a committed
     * {@code vortex.yaml} on its own would be a change nobody reviewed, and the reason the file is
     * in version control is that somebody can.
     */
    private int calibrate(String[] args) {
        String path = args.length > 0 && !args[0].startsWith("--") ? args[0] : null;
        String projectName = optionValue(args, "--project").orElse(null);
        boolean apply = hasFlag(args, "--apply");
        boolean write = apply || hasFlag(args, "--write");

        Duration windowOverride;
        try {
            windowOverride = optionValue(args, "--window")
                    .map(raw -> Durations.parse(raw)).orElse(null);
        } catch (IllegalArgumentException e) {
            err.println("--window " + e.getMessage());
            return ExitCode.VALIDATION_FAILED.value();
        }

        Optional<Project> project = resolveProject(path, projectName);
        if (project.isEmpty()) {
            err.println(projectName != null
                    ? "No project named '" + projectName + "'."
                    : "No project found. Give a path to a directory holding .vortex/vortex.yaml, "
                            + "or --project <name>.");
            return ExitCode.VALIDATION_FAILED.value();
        }

        ProjectId projectId = project.get().id();
        ProjectConfiguration configuration = projects.configuration(projectId);

        var retrieval = calibration.fetch(configuration, projects.catalog(projectId).orElse(null),
                windowOverride);

        if (retrieval instanceof ProductionObservationSource.NotRetrieved failure) {
            err.println(failure.what() + ".");
            err.println();
            err.println("  Why    " + failure.why());
            if (!failure.remedy().isBlank()) {
                err.println("  Try    " + failure.remedy());
            }
            return ExitCode.VALIDATION_FAILED.value();
        }

        ProductionObservation observation =
                ((ProductionObservationSource.Retrieved) retrieval).observation();

        out.println("Observed production traffic for " + project.get().name());
        out.println();
        observation.averageRateIfPresent().ifPresent(
                rate -> line("Average", rate.display() + " requests/sec"));
        observation.p95ObservedRateIfPresent().ifPresent(
                rate -> line("p95 rate", rate.display() + " requests/sec"));
        line("Peak", observation.peakRate().display() + " requests/sec");

        out.println();
        out.println("How much this baseline is worth:");
        observation.qualityFacts().forEach(fact -> out.println("  " + fact));

        List<WorkloadSuggestion> suggestions = calibrationPolicy.propose(observation);
        out.println();
        out.println("Workloads Vortex would propose:");
        out.println();
        for (WorkloadSuggestion suggestion : suggestions) {
            out.println("  " + suggestion.name() + "  (" + suggestion.type().label() + ")");
            out.println("    " + suggestion.rate().display() + " requests/sec for "
                    + Durations.display(suggestion.duration()));
            out.println("    " + suggestion.derivation());
            out.println();
        }

        if (!write) {
            out.println("Nothing has been written. Re-run with --write to record this observation "
                    + "in vortex.yaml,");
            out.println("or --apply to also create the workloads above.");
            return ExitCode.SUCCESS.value();
        }

        if (apply) {
            Optional<String> refusal = refuseToApply(observation);
            if (refusal.isPresent()) {
                err.println(refusal.get());
                return ExitCode.VALIDATION_FAILED.value();
            }
        }

        ProjectConfiguration updated = configuration.withProductionObservation(observation);
        if (apply) {
            for (WorkloadSuggestion suggestion : suggestions) {
                updated = updated.withWorkload(WorkloadSuggestions.toWorkload(suggestion,
                        observation.observedMixIfPresent().orElseThrow()));
            }
        }
        projects.saveConfiguration(projectId, updated);

        out.println(apply
                ? "Recorded the observation and created " + suggestions.size() + " workloads."
                : "Recorded the observation. Run with --apply to create the workloads above.");
        return ExitCode.SUCCESS.value();
    }

    /**
     * Why these proposals should not be turned into workloads yet, if they should not.
     *
     * <p>A mix that describes four requests in five is worth showing and worth arguing with; turning
     * it into committed workloads without saying so would let a partial view of production become
     * the definition of it.
     */
    private Optional<String> refuseToApply(ProductionObservation observation) {
        if (observation.observedMixIfPresent().isEmpty()) {
            return Optional.of("""
                    Cannot create workloads: the observation has no operation composition.

                      Why    Vortex will not guess how production traffic is distributed across
                             operations, and a workload built from volume alone reproduces traffic
                             the service has never actually received.
                      Try    Check the operations in your catalog match the routes your service
                             reports, then calibrate again.""");
        }
        return observation.mixCoverageIfPresent()
                .filter(coverage -> !coverage.isRepresentative())
                .map(coverage -> """
                        Cannot create workloads: the observed mix accounts for only %s%% of \
                        production traffic.

                          Why    %s
                          Try    Import the missing operations, or narrow the observation to the \
                        part of the service you mean to test. Re-run with --write alone to record \
                        the observation without creating workloads from it.\
                        """.formatted(coverage.display(), coverage.describe()));
    }

    // ---------------------------------------------------------------- run

    private int runTest(String[] args) {
        if (args.length == 0) {
            err.println("Usage: vortex run <workload> [<path>] [--project <name>] "
                    + "[--environment <name>] [--service-version <value>] [--confirm <value>] "
                    + "[--headless]");
            return ExitCode.ERROR.value();
        }

        String workload = args[0];
        // A path may only appear immediately after the workload, which keeps it unambiguous
        // against the value of any option that follows.
        String path = args.length > 1 && !args[1].startsWith("--") ? args[1] : null;
        String projectName = optionValue(args, "--project").orElse(null);
        String environmentName = optionValue(args, "--environment").orElse(null);
        String serviceVersion = optionValue(args, "--service-version").orElse("");
        List<String> confirmations = optionValues(args, "--confirm");
        boolean headless = hasFlag(args, "--headless");

        Optional<Project> project;
        if (path != null) {
            ProjectService.AdoptionResult adoption = projects.adopt(path);
            if (!adoption.adopted()) {
                return reportUnusableConfiguration(adoption.source());
            }
            project = Optional.of(adoption.project());
        } else if (projectName == null) {
            project = projects.all().stream().findFirst();
        } else {
            project = projects.all().stream()
                    .filter(candidate -> candidate.name().equalsIgnoreCase(projectName))
                    .findFirst();
        }

        if (project.isEmpty()) {
            if (projectName != null) {
                err.println("No project named '" + projectName + "'.");
            } else {
                err.println("No projects exist on this machine yet.");
                err.println();
                err.println("Create one in the Vortex interface, or point Vortex straight at a "
                        + "directory that already");
                err.println("holds a committed .vortex/vortex.yaml — which is how a pipeline runs "
                        + "from a fresh checkout:");
                err.println();
                err.println("  vortex run " + workload + " ./path/to/service");
            }
            return ExitCode.VALIDATION_FAILED.value();
        }

        ProjectConfiguration configuration = projects.configuration(project.get().id());
        String environment = environmentName != null ? environmentName
                : configuration.environments().stream()
                .findFirst()
                .map(candidate -> candidate.name())
                .orElse(null);

        if (environment == null) {
            err.println("This project has no environments configured, so there is nowhere to send "
                    + "traffic.");
            return ExitCode.VALIDATION_FAILED.value();
        }

        // --headless suppresses the per-bucket progress lines and nothing else. A pipeline log is
        // not improved by several hundred identical-looking rows, and the verdict, the objective
        // table and the exit code — the parts continuous integration exists to read — are printed
        // either way.
        Consumer<ExecutionProgress> progressSink = headless
                ? _ -> { }
                : progress -> out.printf("  %s  %s  p95 %s  errors %s%n",
                        progress.stageLabel(),
                        progress.currentRateIfPresent()
                                .map(rate -> rate.displayWithUnit()).orElse("—"),
                        progress.currentP95IfPresent()
                                .map(p95 -> p95.toMillis() + " ms").orElse("—"),
                        progress.currentErrorRateIfPresent()
                                .map(rate -> rate.display()).orElse("—"));

        TestRunner.Outcome outcome;
        try {
            outcome = testRunner.runToCompletion(
                    new TestRunner.RunRequest(project.get().id(), workload, environment,
                            serviceVersion, confirmations),
                    this::printPreflight,
                    progressSink);
        } catch (PlanResolutionException e) {
            // A configuration that cannot become a runnable plan is a validation failure, not a
            // Vortex failure — the distinction is what a pipeline needs in order to react correctly.
            err.println(e.getMessage());
            e.problems().forEach(problem -> err.println("  • " + problem));
            return ExitCode.VALIDATION_FAILED.value();
        }

        int exitCode = report(outcome);

        // After the verdict, and never able to change it.
        optionValue(args, "--export").ifPresent(formats -> {
            if (outcome.execution() != null && outcome.execution().summaryIfPresent().isPresent()) {
                exportAfterRun(outcome.execution(), formats);
            }
        });

        return exitCode;
    }

    /**
     * What is about to happen, before any traffic is generated.
     *
     * <p>The web interface has a whole preflight screen; the command line was computing the same
     * report and discarding it. A pipeline log that records the release, the target and the offered
     * load is the difference between a result somebody can interpret six months later and a number
     * with no context — and if the target is wrong, this is where a person notices.
     *
     * <p>Printed, never prompted. The command line has to stay usable from a script.
     */
    private void printPreflight(dev.vortex.core.application.PreflightReport report) {
        var plan = report.plan();
        out.println();
        line("Workload", plan.workloadName().isBlank() ? "(unnamed)" : plan.workloadName());
        line("Release", plan.serviceVersionIfPresent().orElse("not recorded"));
        line("Target", plan.effectiveTargetIfPresent()
                .map(target -> target.value())
                .orElseGet(() -> plan.executionTarget().summary()));
        line("Environment", plan.environmentName() + " (" + plan.classification().label() + ")");
        line("LoadShape", plan.workloadModel().label() + ", " + plan.peakLevel().displayWithUnit());
        line("Duration", dev.vortex.core.threshold.Durations.display(plan.totalDuration()));
        line("Operations", String.valueOf(plan.operations().size()));
        line("Objectives", plan.thresholds().isEmpty()
                ? "none — this run cannot pass or fail"
                : String.valueOf(plan.thresholds().size()));
        plan.estimatedRequests().ifPresent(requests ->
                line("Estimated reqs", "~" + String.format("%,d", requests)));
        out.println();
    }

    private void line(String label, String value) {
        out.printf("  %-16s%s%n", label, value);
    }

    // ---------------------------------------------------------------- compare

    /**
     * Sets two runs against each other, and says whether the difference means anything.
     *
     * <p>The whole point is the middle section. Any two runs can be shown side by side; a
     * regression verdict requires that they tested the same experiment, and when they did not this
     * says which condition changed rather than producing a percentage that reads like a finding.
     */
    private int compare(String[] args) {
        if (args.length == 0) {
            err.println("Usage: vortex compare <baseline-execution> <candidate-execution>");
            err.println("       vortex compare --previous <execution>");
            return ExitCode.ERROR.value();
        }

        Optional<String> previous = optionValue(args, "--previous");
        TestExecution baseline;
        TestExecution candidate;

        if (previous.isPresent()) {
            Optional<TestExecution> found = load(previous.get());
            if (found.isEmpty()) {
                return ExitCode.VALIDATION_FAILED.value();
            }
            candidate = found.get();
            Optional<TestExecution> earlier = comparisons.previousCompatible(candidate);
            if (earlier.isEmpty()) {
                err.println("No previous compatible run exists for " + candidate.id().value() + ".");
                err.println();
                err.println("A comparison needs an earlier run of the same experiment — the same "
                        + "workload, environment,");
                err.println("target and objectives. Run this workload against another release to "
                        + "establish one.");
                return ExitCode.VALIDATION_FAILED.value();
            }
            baseline = earlier.get();
        } else {
            List<String> ids = java.util.Arrays.stream(args)
                    .filter(argument -> !argument.startsWith("--"))
                    .toList();
            if (ids.size() < 2) {
                err.println("Two execution ids are required, or --previous <execution>.");
                return ExitCode.ERROR.value();
            }
            Optional<TestExecution> left = load(ids.get(0));
            Optional<TestExecution> right = load(ids.get(1));
            if (left.isEmpty() || right.isEmpty()) {
                return ExitCode.VALIDATION_FAILED.value();
            }
            baseline = left.get();
            candidate = right.get();
        }

        return reportComparison(comparisons.compareAndEvaluate(baseline, candidate));
    }

    private Optional<TestExecution> load(String id) {
        Optional<TestExecution> found = comparisons.find(ExecutionId.of(id));
        if (found.isEmpty()) {
            err.println("No execution with id " + id + ".");
        }
        return found;
    }

    private int reportComparison(ComparisonService.Result result) {
        out.println();
        out.println("Comparing releases");
        out.println();
        describeSide("Baseline", result.baseline());
        describeSide("Candidate", result.candidate());

        out.println();
        out.println("Experiment compatibility");
        if (result.comparison().supportsRegressionVerdict()) {
            out.println("  Compatible");
        } else {
            out.println("  Not comparable");
            result.comparison().differences().forEach(difference ->
                    out.println("    • " + difference));
        }

        if (!result.comparison().deltas().isEmpty()) {
            out.println();
            out.println("Measurements");
            result.comparison().deltas().forEach(delta ->
                    out.printf("  %-20s %-28s %s%n",
                            delta.metric(), delta.display(), delta.percentChangeDisplay()));
        }

        out.println();
        out.println("Regression verdict");
        if (!result.supportsVerdict()) {
            // Deliberately not a percentage and deliberately not a refusal on its own: the numbers
            // above are real and worth looking at, and only the conclusion is withheld.
            out.println("  Vortex will not issue a regression verdict: these runs did not test the "
                    + "same experiment.");
            out.println("  The measurements above are shown for inspection only.");
            out.println();
            return ExitCode.VALIDATION_FAILED.value();
        }

        out.println("  " + result.verdict().label().toUpperCase(java.util.Locale.ROOT)
                + " — " + qualified(result));
        out.println();

        return result.verdict() == RegressionVerdict.REGRESSED
                ? ExitCode.THRESHOLDS_VIOLATED.value()
                : ExitCode.SUCCESS.value();
    }

    /**
     * The verdict, stated with the condition that makes it meaningful.
     *
     * <p>"Release B is 20% slower" is a claim about the service that a bare percentage cannot
     * support. "Under equivalent experiment conditions, p95 increased by 20%" is the same number
     * carrying the assumption it depends on.
     */
    private String qualified(ComparisonService.Result result) {
        String movement = result.comparison().deltas().stream()
                .filter(delta -> delta.isDegradation(java.math.BigDecimal.valueOf(10)).isPresent())
                .map(delta -> delta.metric().toLowerCase(java.util.Locale.ROOT) + " "
                        + delta.percentChangeDisplay())
                .findFirst()
                .orElse("");

        return switch (result.verdict()) {
            case REGRESSED -> "under equivalent experiment conditions, " + movement + ".";
            case IMPROVED -> "under equivalent experiment conditions, " + movement + ".";
            case UNCHANGED -> result.verdict().description();
            case NOT_COMPARABLE -> result.verdict().description();
        };
    }

    private void describeSide(String label, TestExecution execution) {
        out.printf("  %-11s release %-14s run %s   %s%n",
                label,
                execution.plan().serviceVersionIfPresent().orElse("(not recorded)"),
                execution.requestedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS),
                execution.id().value());
    }


    // ---------------------------------------------------------------- export

    /**
     * Writes a completed run's evidence to a file.
     *
     * <p>Exists so a report can be produced from a run that has already happened — after a pipeline
     * has finished, or from a workspace somebody has been handed. Producing one as part of a run is
     * {@code run --export}.
     */
    private int export(String[] args) {
        if (args.length == 0) {
            err.println("Usage: vortex export <run-id> [--format " + ExportFormat.available()
                    + "] [--output <path>]");
            return ExitCode.ERROR.value();
        }

        String runId = args[0];
        String requested = optionValue(args, "--format").orElse(ExportFormat.PDF.extension());
        ExportFormat format = ExportFormat.parse(requested).orElse(null);
        if (format == null) {
            err.println("Vortex does not export '" + requested + "'.");
            err.println("Available formats: " + ExportFormat.available() + ".");
            return ExitCode.VALIDATION_FAILED.value();
        }

        RunExporter.Exported document;
        try {
            document = exporter.export(ExecutionId.of(runId), format);
        } catch (RunExporter.RefusedException e) {
            err.println(e.getMessage());
            // Nothing was measured, or nothing can be, so this is a validation failure rather than
            // a Vortex failure — the distinction is what a pipeline reacts to.
            return ExitCode.VALIDATION_FAILED.value();
        } catch (RuntimeException e) {
            err.println("Vortex could not render the report: " + e.getMessage());
            return ExitCode.ERROR.value();
        }

        Path destination = Path.of(optionValue(args, "--output").orElse("."))
                .resolve(document.filename()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(destination.toAbsolutePath().getParent());
            Files.write(destination, document.content());
        } catch (java.io.IOException e) {
            err.println("Vortex could not write " + destination + ": " + e.getMessage());
            return ExitCode.ERROR.value();
        }

        out.println(destination);
        return ExitCode.SUCCESS.value();
    }

    /**
     * Writes the formats a run was asked to export, after it has completed.
     *
     * <p>Deliberately cannot change the exit code. A pipeline's gate must reflect the service's
     * performance, never the report generator's mood, so a failed export is a warning on stderr and
     * nothing more.
     */
    private void exportAfterRun(TestExecution execution, String formats) {
        for (String requested : formats.split(",")) {
            String name = requested.trim();
            if (name.isEmpty()) {
                continue;
            }
            ExportFormat format = ExportFormat.parse(name).orElse(null);
            if (format == null) {
                err.println("Skipping unknown export format '" + name + "'. Available: "
                        + ExportFormat.available() + ".");
                continue;
            }
            try {
                RunExporter.Exported document = exporter.export(execution.id(), format);
                Path destination = Path.of(document.filename()).toAbsolutePath().normalize();
                Files.write(destination, document.content());
                out.println("  Exported           " + destination);
            } catch (RuntimeException | java.io.IOException e) {
                err.println("Could not export " + name + ": " + e.getMessage());
            }
        }
    }

    /** Shared by {@code validate} and by {@code run <workload> <path>}, so both explain a bad
     * configuration the same way. */
    private int reportUnusableConfiguration(ConfigurationStore.LoadResult result) {
        if (result.problems().isEmpty()) {
            err.println("No configuration found at " + result.sourcePath());
            err.println();
            err.println("Vortex expects a .vortex/vortex.yaml file in the directory you point it at.");
        } else {
            err.println(result.sourcePath() + " has "
                    + (result.problems().size() == 1 ? "a problem" : result.problems().size()
                    + " problems") + ":");
            err.println();
            result.problems().forEach(problem -> err.println("  • " + problem));
        }
        return ExitCode.VALIDATION_FAILED.value();
    }

    private int report(TestRunner.Outcome outcome) {
        if (outcome.preflightFailed()) {
            err.println("Preflight checks failed, so no traffic was generated:");
            err.println();
            outcome.problems().forEach(problem -> err.println("  • " + problem));
            return ExitCode.VALIDATION_FAILED.value();
        }

        if (outcome.cancelled()) {
            err.println("The run was cancelled.");
            return ExitCode.CANCELLED.value();
        }

        if (outcome.execution() == null) {
            err.println("The run did not complete.");
            outcome.problems().forEach(problem -> err.println("  " + problem));
            return ExitCode.ERROR.value();
        }

        var execution = outcome.execution();
        if (execution.summaryIfPresent().isEmpty()) {
            err.println("The run did not produce results.");
            execution.failureReasonIfPresent().ifPresent(reason -> {
                err.println("  " + reason.label());
                err.println("  " + reason.guidance());
            });
            return ExitCode.ERROR.value();
        }

        var summary = execution.summary();
        out.println();
        out.println(summary.question());
        out.println(summary.answer());
        out.println();
        out.println("  Result             " + summary.verdict().label());
        out.println("  LoadShape           " + execution.plan().workloadModel().label() + ", "
                + execution.plan().peakLevel().displayWithUnit());
        execution.results().achievedRateIfPresent().ifPresent(rate ->
                out.println("  Achieved rate      " + rate.displayWithUnit()));
        out.println("  Error rate         " + execution.results().errorRate().display());
        out.println();

        summary.thresholds().results().forEach(result ->
                out.printf("  %-32s %-14s %s%n",
                        result.threshold().describe(),
                        result.verdict().label(),
                        result.observed().isBlank() ? result.note() : result.observed()));

        out.println();
        out.println("  Artifacts          " + outcome.artifactDirectory());

        var quality = execution.quality();
        if (quality.isInvalid()) {
            // Checked before the verdict, and only for invalidity. The objectives may well have been
            // met; what this says is that the experiment did not measure what it claims to, so the
            // verdict is not a statement about the service. Fail closed where Vortex genuinely
            // cannot tell — never merely because it would have liked more evidence.
            err.println();
            err.println("This run did not measure what it claims to, so its result is not a verdict "
                    + "about the service:");
            quality.findings().forEach(finding -> err.println("  • " + finding.statement()));
            return ExitCode.EVIDENCE_NOT_VALID.value();
        }
        if (quality.quality() == dev.vortex.core.validity.RunQuality.DEGRADED) {
            // Printed, and deliberately not acted on. The verdict decides the exit code.
            out.println();
            out.println("  Qualifications");
            quality.qualifications().forEach(statement -> out.println("    • " + statement));
        }

        return switch (summary.verdict()) {
            case PASS -> ExitCode.SUCCESS.value();
            case FAIL -> ExitCode.THRESHOLDS_VIOLATED.value();
            case NOT_EVALUATED -> {
                err.println();
                err.println("At least one objective could not be evaluated, so this run does not "
                        + "constitute a pass.");
                yield ExitCode.THRESHOLDS_VIOLATED.value();
            }
        };
    }

    /** The project a command should act on: an adopted path, a named project, or the only one. */
    private Optional<Project> resolveProject(String path, String projectName) {
        if (path != null) {
            ProjectService.AdoptionResult adoption = projects.adopt(path);
            return adoption.adopted() ? Optional.of(adoption.project()) : Optional.empty();
        }
        if (projectName == null) {
            return projects.all().stream().findFirst();
        }
        return projects.all().stream()
                .filter(candidate -> candidate.name().equalsIgnoreCase(projectName))
                .findFirst();
    }

    private boolean hasFlag(String[] args, String flag) {
        return java.util.Arrays.asList(args).contains(flag);
    }

    private Optional<String> optionValue(String[] args, String option) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(option)) {
                return Optional.of(args[i + 1]);
            }
        }
        return Optional.empty();
    }

    /** Every value given for a repeatable option — a run may face more than one challenge. */
    private List<String> optionValues(String[] args, String option) {
        List<String> values = new java.util.ArrayList<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(option)) {
                values.add(args[i + 1]);
            }
        }
        return values;
    }

    private void printUsage() {
        out.println("""
                Vortex — local-first performance engineering workbench

                Usage:
                  vortex                              start the workbench in your browser
                  vortex doctor                       check that the tools Vortex needs are present
                  vortex validate [path]              check a project's configuration
                  vortex calibrate [path]             fetch observed production traffic and
                                                      propose workloads from it
                  vortex run <workload> [<path>]      execute a workload and report its verdict
                  vortex compare <run-a> <run-b>      set two runs against each other
                  vortex compare --previous <run>     compare a run with the previous compatible one
                  vortex export <run-id>              write a completed run\u0027s report to a file

                Options for run:
                  <path>                              a directory holding .vortex/vortex.yaml;
                                                      Vortex adopts it without modifying the file
                  --project <name>                    which project (defaults to the only one)
                  --environment <name>                which environment (defaults to the first)
                  --service-version <value>           the release under test, e.g. "$GIT_SHA";
                                                      overrides service.version in vortex.yaml
                  --confirm <value>                   satisfies a typed safety challenge; repeat
                                                      once per challenge the run raises
                  --export <formats>                  also write the report, e.g. "pdf" or "json,md";
                                                      a failed export never changes the exit code
                  --headless                          no interactive output

                Options for calibrate:
                  <path>                              a directory holding .vortex/vortex.yaml
                  --project <name>                    which project (defaults to the only one)
                  --window <duration>                 override the configured observation window
                  --write                             record the observation in vortex.yaml
                  --apply                             also create the proposed workloads

                Options for export:
                  --format <format>                   json, md or pdf (defaults to pdf)
                  --output <path>                     directory to write into (defaults to .)

                Exit codes:
                  0   completed, every objective met — or a comparison showing no regression
                  1   Vortex itself failed
                  2   completed, an objective was violated — or a comparison showing a regression
                  3   configuration or preflight failed, no traffic generated — or two runs that
                      cannot be compared
                  4   cancelled
                """);
    }
}
