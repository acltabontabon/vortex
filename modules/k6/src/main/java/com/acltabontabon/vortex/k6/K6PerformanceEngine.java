package com.acltabontabon.vortex.k6;

import com.acltabontabon.vortex.core.execution.ExecutionArtifacts;
import com.acltabontabon.vortex.core.execution.ExecutionProgress;
import com.acltabontabon.vortex.core.execution.ExecutionState;
import com.acltabontabon.vortex.core.metrics.MeasuredResults;
import com.acltabontabon.vortex.core.metrics.SamplePoint;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.plan.PlannedOperation;
import com.acltabontabon.vortex.core.plan.ScriptSource;
import com.acltabontabon.vortex.core.plan.ToolVersions;
import com.acltabontabon.vortex.core.port.PerformanceEngine;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.target.ResourceEnvelopeRequest;
import com.acltabontabon.vortex.core.threshold.Durations;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The k6 adapter: everything Vortex knows about how to actually generate load.
 *
 * <p>Nothing above this class deals in k6 concepts. It receives a resolved Vortex plan, produces a
 * workload, runs the engine, reduces its output, and hands back normalised measurements. A second
 * engine, or distributed execution through the k6 Operator, would implement
 * {@link PerformanceEngine} and change nothing else.
 *
 * <h2>Raw evidence, kept but not hoarded</h2>
 * k6's per-sample output is large — roughly 3.5 KB per request across all metrics, which is close to
 * a gigabyte for a twenty-minute run at high rates. Vortex tails it live to build the time series,
 * then compresses it, so the source evidence survives without the artifact directory becoming
 * unusable. Summaries answer the questions you thought to ask; the raw stream answers the one you
 * think of six months later.
 */
public final class K6PerformanceEngine implements PerformanceEngine {

    private static final Logger log = LoggerFactory.getLogger(K6PerformanceEngine.class);

    public static final String RAW_METRICS_FILE = "raw-metrics.json";
    public static final String RAW_METRICS_ARCHIVE = "raw-metrics.json.gz";
    public static final String SCRIPT_FILE = "generated-test.js";

    /** How often live progress is published. Matches the aggregation bucket width. */
    private static final Duration PROGRESS_INTERVAL = K6RawMetricsAggregator.BUCKET_WIDTH;

    /** How long the aggregator is given, after the engine exits, to finish tailing the sample file. */
    private static final Duration AGGREGATOR_JOIN_TIMEOUT = Duration.ofSeconds(30);

    private final K6Runner runner;
    private final K6ScriptGenerator generator;
    private final K6SummaryParser summaryParser;
    private final K6RawMetricsAggregator aggregator;
    private final Path workspaceRoot;
    private final String vortexVersion;
    private final boolean compressRawMetrics;

    public K6PerformanceEngine(K6Runner runner, K6ScriptGenerator generator,
            K6SummaryParser summaryParser, K6RawMetricsAggregator aggregator,
            Path workspaceRoot, String vortexVersion, boolean compressRawMetrics) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.summaryParser = Objects.requireNonNull(summaryParser, "summaryParser");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.workspaceRoot = Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        this.vortexVersion = vortexVersion == null ? "unknown" : vortexVersion;
        this.compressRawMetrics = compressRawMetrics;
    }

    @Override
    public EngineAvailability availability() {
        return runner.availability();
    }

    @Override
    public ToolVersions toolVersions() {
        return new ToolVersions(
                vortexVersion,
                runner.version().orElse("unknown"),
                System.getProperty("java.vm.name", "") + " " + System.getProperty("java.version", ""),
                runner instanceof DockerK6Runner docker ? docker.image() : "");
    }

    /**
     * The rewrite this runner requires for the given plan, for display on the preflight screen and
     * for {@code ExecutionService} to compose with target resolution when building the transient,
     * engine-facing plan copy it actually executes (see {@code
     * com.acltabontabon.vortex.core.port.PerformanceEngine#targetRewriteFor}).
     */
    @Override
    public Optional<PerformanceEngine.TargetRewrite> targetRewriteFor(EffectiveTestPlan plan) {
        return runner.targetRewriteFor(plan)
                .map(hint -> new PerformanceEngine.TargetRewrite(hint.newHost(), hint.reason()));
    }

    /**
     * Checks a workload without generating traffic, using k6's own parser.
     *
     * <p>Catches problems Vortex's own validation cannot see — a malformed executor configuration,
     * an option k6 rejects — before anything reaches a real service.
     */
    @Override
    public ValidationResult validate(EffectiveTestPlan plan) {
        if (plan.scriptSource() == ScriptSource.IMPORTED) {
            return ValidationResult.ok();
        }

        Path directory;
        try {
            directory = Files.createTempDirectory("vortex-validate-");
        } catch (IOException e) {
            return ValidationResult.invalid(List.of(
                    "Vortex could not create a temporary directory to validate the workload: "
                            + e.getMessage()));
        }

        try {
            Path script = directory.resolve(SCRIPT_FILE);
            Files.writeString(script, generator.generate(scriptCheckable(plan)), StandardCharsets.UTF_8);

            List<String> problems = new ArrayList<>();
            var outcome = runner.run(
                    List.of("inspect", SCRIPT_FILE),
                    directory,
                    Map.of(),
                    ResourceEnvelopeRequest.none(),
                    _ -> { },
                    problems::add,
                    Cancellation.never());

            return outcome.succeeded()
                    ? ValidationResult.ok()
                    : ValidationResult.invalid(problems.isEmpty()
                    ? List.of("The execution engine rejected the generated workload "
                    + "(exit code " + outcome.exitCode() + ").")
                    : problems);
        } catch (IOException e) {
            return ValidationResult.invalid(List.of(
                    "Vortex could not write the workload for validation: " + e.getMessage()));
        } catch (K6ExecutionException e) {
            return ValidationResult.invalid(List.of(e.getMessage(), e.detail()));
        } finally {
            deleteQuietly(directory);
        }
    }

    /**
     * A copy of {@code plan} guaranteed to carry a resolved target address, for generating the
     * disposable script this method uses only to ask k6 "does this parse" — never run, never shown
     * to a user, deleted within microseconds.
     *
     * <p>A Docker or Compose target has no real address yet at this point — target preparation, the
     * step that would resolve one, only ever runs once a real execution starts, which validation
     * deliberately happens before. Substituting a placeholder here is correct rather than a
     * workaround: this check is about syntax, not reachability, and reachability for such a target
     * is already covered separately by {@code TargetExecutor.checkAvailability}.
     */
    private static EffectiveTestPlan scriptCheckable(EffectiveTestPlan plan) {
        if (plan.effectiveTargetIfPresent().isPresent()) {
            return plan;
        }
        var placeholder = com.acltabontabon.vortex.core.environment.TargetUrl.of("http://validation.invalid");
        return plan.withTargetAddress(placeholder, placeholder, "");
    }

    @Override
    public EngineOutcome execute(ExecutionId executionId, EffectiveTestPlan plan,
            ResourceEnvelopeRequest loadGeneratorResources, Consumer<ExecutionProgress> progressSink,
            Cancellation cancellation) {

        Path directory = executionDirectory(executionId);
        Instant startedAt = Instant.now();

        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new K6ExecutionException("Vortex could not create the execution directory.",
                    "Tried to create " + directory + ". Check that the workspace is writable.", e);
        }

        List<String> artifacts = new ArrayList<>();
        Path scriptPath = directory.resolve(SCRIPT_FILE);

        if (plan.scriptSource() == ScriptSource.GENERATED) {
            writeArtifact(scriptPath, generator.generate(plan));
            artifacts.add(SCRIPT_FILE);
        }

        List<String> arguments = argumentsFor(plan);
        Map<String, String> environment = secretEnvironment(plan);

        Path stdoutPath = directory.resolve(ExecutionArtifacts.STDOUT);
        Path stderrPath = directory.resolve(ExecutionArtifacts.STDERR);
        List<String> stdoutLines = new ArrayList<>();
        List<String> stderrLines = new ArrayList<>();
        Path rawMetrics = directory.resolve(RAW_METRICS_FILE);

        ProgressPublisher progress = new ProgressPublisher(executionId, plan, startedAt, progressSink);
        progressSink.accept(ExecutionProgress.starting(executionId, plan.totalDuration(),
                "Starting the execution engine"));

        // The sample stream is consumed while the engine is still writing it, so progress is
        // actually live. Reading the file only after the process exits would produce a run that
        // shows nothing for the whole test and then everything at once.
        AtomicBoolean engineFinished = new AtomicBoolean();
        AtomicReference<K6RawMetricsAggregator.Aggregation> aggregated = new AtomicReference<>();

        Thread aggregatorThread = Thread.ofVirtual()
                .name("k6-aggregate-" + executionId.value())
                .start(() -> aggregated.set(aggregate(rawMetrics, plan, progress, engineFinished::get)));

        K6Runner.ProcessOutcome outcome;
        try {
            outcome = runner.run(arguments, directory, environment, loadGeneratorResources,
                    line -> {
                        stdoutLines.add(line);
                        progress.noteEngineOutput(line);
                    },
                    stderrLines::add,
                    cancellation);
        } finally {
            engineFinished.set(true);
            awaitAggregator(aggregatorThread, AGGREGATOR_JOIN_TIMEOUT, executionId);
            writeArtifact(stdoutPath, String.join(System.lineSeparator(), stdoutLines));
            writeArtifact(stderrPath, String.join(System.lineSeparator(), stderrLines));
            artifacts.add(ExecutionArtifacts.STDOUT);
            artifacts.add(ExecutionArtifacts.STDERR);
        }

        K6RawMetricsAggregator.Aggregation aggregation = aggregated.get() == null
                ? K6RawMetricsAggregator.Aggregation.none()
                : aggregated.get();

        Path summaryPath = directory.resolve(K6ScriptGenerator.SUMMARY_FILE);
        if (Files.exists(summaryPath)) {
            artifacts.add(K6ScriptGenerator.SUMMARY_FILE);
        }

        if (Files.exists(rawMetrics)) {
            artifacts.add(archiveRawMetrics(rawMetrics, directory));
        }

        if (outcome.cancelled()) {
            // A killed k6 never writes its summary, so there are no end-of-run percentiles. What the
            // aggregator read from the stream while the run was alive is real measurement, and
            // returning nothing would discard it — along with the only evidence that could say
            // whether the generator was keeping up before somebody stopped it.
            return new EngineOutcome(partialResults(aggregation, startedAt, plan),
                    outcome.exitCode(),
                    "The run was cancelled. Partial measurements and artifacts have been kept.",
                    artifacts);
        }

        if (!Files.exists(summaryPath)) {
            return new EngineOutcome(null, outcome.exitCode(),
                    failureDetail(outcome, stderrLines), artifacts);
        }

        try {
            MeasuredResults results = summaryParser.parse(
                    Files.readString(summaryPath, StandardCharsets.UTF_8),
                    startedAt,
                    plan.peakLevel(),
                    aggregation,
                    List.of());
            return new EngineOutcome(results, outcome.exitCode(), "", artifacts);
        } catch (IOException | K6SummaryParser.UnreadableSummaryException e) {
            return new EngineOutcome(null, outcome.exitCode(),
                    "The run finished but its results could not be read: " + e.getMessage()
                            + "\nThe raw output has been kept in this execution's artifacts.",
                    artifacts);
        }
    }

    /**
     * What a cancelled run measured before it was stopped.
     *
     * <p>No latency percentiles: k6 computes those in its summary, and a process killed mid-run
     * never writes one. Absent is the honest answer — deriving them from the aggregator's capped
     * per-bucket reservoirs would produce numbers that look like the ones a completed run reports
     * and are not comparable with them.
     *
     * <p>Everything the stream did establish is kept: the series, the per-operation totals, what the
     * generator managed, and the outcome distribution. A cancelled run is graded
     * {@code EXECUTION_INTERRUPTED} rather than discarded, and grading needs something to grade.
     */
    private MeasuredResults partialResults(K6RawMetricsAggregator.Aggregation aggregation,
            Instant startedAt, EffectiveTestPlan plan) {

        var points = aggregation.series().points();
        Instant finishedAt = points.isEmpty()
                ? startedAt
                : points.getLast().at().plus(points.getLast().duration());

        long requests = aggregation.operations().values().stream()
                .mapToLong(com.acltabontabon.vortex.core.metrics.OperationMetrics::requests).sum();
        long failures = aggregation.operations().values().stream()
                .mapToLong(com.acltabontabon.vortex.core.metrics.OperationMetrics::failures).sum();

        return new MeasuredResults(
                new com.acltabontabon.vortex.core.metrics.TimeWindow(startedAt, finishedAt),
                plan.peakLevel(), null, requests, Math.min(failures, requests),
                com.acltabontabon.vortex.core.metrics.LatencyPercentiles.empty(),
                aggregation.operations(), aggregation.series(), List.of(), List.of(), List.of(),
                aggregation.generation(), com.acltabontabon.vortex.core.metrics.RequestPhases.empty(),
                aggregation.reliability());
    }

    /**
     * Builds the k6 command.
     *
     * <p>Everything is a separate argument. Nothing is concatenated into a shell string, so values
     * that came from user configuration cannot become commands.
     */
    List<String> argumentsFor(EffectiveTestPlan plan) {
        List<String> arguments = new ArrayList<>();
        arguments.add("run");
        arguments.add("--quiet");
        arguments.add("--no-color");
        arguments.add("--out");
        arguments.add("json=" + RAW_METRICS_FILE);

        if (plan.scriptSource() == ScriptSource.IMPORTED) {
            // An imported script is executed exactly as its author wrote it. Vortex does not rewrite
            // it to inject a summary handler, so it uses k6's own export mechanism instead and
            // accepts reduced introspection — an honest trade rather than a silent modification.
            arguments.add("--summary-export");
            arguments.add(K6ScriptGenerator.SUMMARY_FILE);
        }

        arguments.add(SCRIPT_FILE);
        return arguments;
    }

    /**
     * The environment the engine process receives.
     *
     * <p>Secret values are read from Vortex's own environment at this moment and passed straight
     * through. They are never written to the plan, the artifacts, the logs or the reports — this
     * method is the only point at which a resolved secret exists, and it exists only for the
     * lifetime of the child process.
     */
    Map<String, String> secretEnvironment(EffectiveTestPlan plan) {
        Set<String> names = new LinkedHashSet<>();

        // The environment's headers, which every request carries.
        for (String value : plan.headers().values()) {
            names.addAll(com.acltabontabon.vortex.core.environment.SecretReferences.referencedNames(value));
        }
        // And every request value that resolves from the environment — a token can be bound to a
        // query parameter or a body field as easily as to a header, and a variable the script looks
        // up but the process was never given resolves to an empty string and a confusing 401.
        for (PlannedOperation operation : plan.operations()) {
            names.addAll(operation.referencedEnvironmentNames());
        }

        Map<String, String> environment = new LinkedHashMap<>();
        for (String name : names) {
            String resolved = System.getenv(name);
            if (resolved != null) {
                environment.put(name, resolved);
            }
        }
        return environment;
    }

    /**
     * Waits for the aggregator to finish, and says so plainly when it did not.
     *
     * <p>A timed-out join is not itself a failure — the engine has already produced its own summary
     * independently of this thread — but proceeding silently would mean the recorded time series and
     * operation counts for this run are quietly substituted with an empty result, with nothing in the
     * logs to explain why.
     *
     * @return {@code true} if the aggregator finished within the timeout
     */
    static boolean awaitAggregator(Thread aggregatorThread, Duration timeout, ExecutionId executionId) {
        try {
            aggregatorThread.join(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for the raw-metric aggregator for execution {} to "
                    + "finish; its time series may be incomplete.", executionId.value());
            return false;
        }
        if (aggregatorThread.isAlive()) {
            log.warn("The raw-metric aggregator for execution {} was still running after {} "
                    + "seconds; proceeding without waiting further, so this run's time series and "
                    + "operation counts may be incomplete.", executionId.value(), timeout.toSeconds());
            return false;
        }
        return true;
    }

    /**
     * Consumes the sample stream as it is written, publishing each completed bucket.
     *
     * <p>Runs on its own virtual thread for the duration of the test. The cost is one thread that
     * spends nearly all its time blocked on I/O; the benefit is an interface that shows what is
     * happening while it happens.
     */
    private K6RawMetricsAggregator.Aggregation aggregate(Path rawMetrics, EffectiveTestPlan plan,
            ProgressPublisher progress, java.util.function.BooleanSupplier engineFinished) {
        try {
            return aggregator.aggregate(
                    new TailingLines(rawMetrics, engineFinished),
                    plan.totalDuration(),
                    plan.operationsByScenarioKey(),
                    K6RawMetricsAggregator.TargetLoadAt.fromStages(plan.stages()),
                    progress::publish);
        } catch (RuntimeException e) {
            // Losing the time series is a degradation, not a failure: the end-of-test summary is
            // written independently and still yields a complete, evaluable result.
            log.warn("Could not aggregate the raw metric stream: {}", e.getMessage());
            return K6RawMetricsAggregator.Aggregation.none();
        }
    }

    /**
     * Compresses the raw sample stream.
     *
     * <p>k6's per-sample output is verbose and highly repetitive, so it compresses by roughly an
     * order of magnitude. Keeping the compressed form preserves the source evidence without leaving
     * hundreds of megabytes per run in the workspace.
     */
    private String archiveRawMetrics(Path rawMetrics, Path directory) {
        if (!compressRawMetrics) {
            return RAW_METRICS_FILE;
        }
        Path archive = directory.resolve(RAW_METRICS_ARCHIVE);
        try (var in = Files.newInputStream(rawMetrics);
                var out = new GZIPOutputStream(Files.newOutputStream(archive))) {
            in.transferTo(out);
        } catch (IOException e) {
            log.warn("Could not compress the raw metric stream; keeping it uncompressed: {}",
                    e.getMessage());
            return RAW_METRICS_FILE;
        }
        try {
            Files.deleteIfExists(rawMetrics);
        } catch (IOException e) {
            log.debug("Could not remove the uncompressed raw metric stream: {}", e.getMessage());
        }
        return RAW_METRICS_ARCHIVE;
    }

    private String failureDetail(K6Runner.ProcessOutcome outcome, List<String> stderrLines) {
        StringBuilder detail = new StringBuilder();
        if (outcome.generatorOomKilled()) {
            // Direct, unambiguous cause — never left to read as a bare, unexplained exit code. This
            // run never produced a summary because the generator itself was killed for exceeding the
            // memory budget Vortex configured and confirmed was applied; the shortfall this run would
            // otherwise report is the generator's own ceiling, not the system under test's.
            detail.append("The load generator exceeded its configured memory budget and was ")
                    .append("terminated by the operating system before it could finish.\n")
                    .append("This run cannot establish the capacity of the system under test — the ")
                    .append("generator itself ran out of the resources Vortex allocated to it. ")
                    .append("Increase the load generator's memory budget under Settings → Load ")
                    .append("Generator Resources and try again.\n");
        } else {
            detail.append("The execution engine exited with code ").append(outcome.exitCode())
                    .append(" without producing a summary.\n");
        }
        if (!stderrLines.isEmpty()) {
            detail.append("\nEngine output:\n");
            stderrLines.stream().limit(40).forEach(line -> detail.append("  ").append(line).append('\n'));
            if (stderrLines.size() > 40) {
                detail.append("  … ").append(stderrLines.size() - 40)
                        .append(" further lines are in this execution's artifacts.\n");
            }
        }
        return detail.toString();
    }

    private Path executionDirectory(ExecutionId executionId) {
        return workspaceRoot.resolve("executions").resolve(executionId.value());
    }

    private void writeArtifact(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not write artifact {}: {}", path, e.getMessage());
        }
    }

    private void deleteQuietly(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: a leftover temporary directory is harmless.
                }
            });
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    /** Turns completed buckets into progress updates, at bucket cadence rather than per sample. */
    private static final class ProgressPublisher {

        private final ExecutionId executionId;
        private final EffectiveTestPlan plan;
        private final Instant startedAt;
        private final Consumer<ExecutionProgress> sink;
        private volatile String stageLabel = "";

        ProgressPublisher(ExecutionId executionId, EffectiveTestPlan plan, Instant startedAt,
                Consumer<ExecutionProgress> sink) {
            this.executionId = executionId;
            this.plan = plan;
            this.startedAt = startedAt;
            this.sink = sink;
        }

        void publish(SamplePoint point) {
            Duration elapsed = Duration.between(startedAt, point.at().plus(point.duration()));
            sink.accept(new ExecutionProgress(
                    executionId,
                    ExecutionState.RUNNING,
                    elapsed.isNegative() ? Duration.ZERO : elapsed,
                    plan.totalDuration(),
                    point.targetLoadIfPresent().orElse(plan.peakLevel()),
                    point.requestRateIfPresent().orElse(null),
                    point.p95IfPresent().orElse(null),
                    point.errorRate(),
                    stageLabelFor(point),
                    "",
                    // Always null: this engine's own progress publisher has no access to the
                    // telemetry session that would produce a live resource reading — see
                    // ExecutionProgress.currentResourceReading's own javadoc for why that field is
                    // deliberately deferred in v1 rather than wired here.
                    null));
        }

        private String stageLabelFor(SamplePoint point) {
            return point.targetLoadIfPresent()
                    .map(level -> "Holding " + level.displayWithUnit()
                            + (plan.stages().size() > 1
                            ? " (stage target, " + Durations.display(plan.totalDuration())
                            + " total)" : ""))
                    .orElse(stageLabel);
        }

        void noteEngineOutput(String line) {
            if (line != null && line.toLowerCase(java.util.Locale.ROOT).contains("insufficient vus")) {
                // Worth surfacing: k6 quietly under-delivers load in this situation, which would
                // make the measurement wrong in a way that is easy to miss.
                stageLabel = "Warning: the engine reported insufficient virtual users";
            }
        }
    }
}
