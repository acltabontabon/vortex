package dev.vortex.app.web;

import dev.vortex.app.service.TestRunner;
import dev.vortex.app.web.WorkspaceDtos.CapacityDto;
import dev.vortex.app.web.WorkspaceDtos.CapacityRangeDto;
import dev.vortex.app.web.WorkspaceDtos.DriftDto;
import dev.vortex.app.web.WorkspaceDtos.MarkerDto;
import dev.vortex.app.web.WorkspaceDtos.MixRowDto;
import dev.vortex.app.web.WorkspaceDtos.ProductionDto;
import dev.vortex.app.web.WorkspaceDtos.ReadinessDto;
import dev.vortex.app.web.WorkspaceDtos.ReadinessItemDto;
import dev.vortex.app.web.WorkspaceDtos.RunRefDto;
import dev.vortex.app.web.WorkspaceDtos.RunSummaryDto;
import dev.vortex.app.web.WorkspaceDtos.ServiceHeaderDto;
import dev.vortex.app.web.WorkspaceDtos.SourceDto;
import dev.vortex.app.web.WorkspaceDtos.TargetDto;
import dev.vortex.app.web.WorkspaceDtos.TestRowDto;
import dev.vortex.app.web.WorkspaceDtos.TestTypeDto;
import dev.vortex.app.web.WorkspaceDtos.TestTypeEvidenceDto;
import dev.vortex.core.analysis.DeterministicSummary;
import dev.vortex.core.application.CapacityService;
import dev.vortex.core.application.PlanResolutionException;
import dev.vortex.core.application.ProjectService;
import dev.vortex.core.calibration.WorkloadDrift;
import dev.vortex.core.capacity.BoundaryEdge;
import dev.vortex.core.capacity.CapacityObservation;
import dev.vortex.core.capacity.Headroom;
import dev.vortex.core.capacity.HeadroomCalculator;
import dev.vortex.core.capacity.OperationMixCoverage;
import dev.vortex.core.capacity.ProductionObservation;
import dev.vortex.core.catalog.Operation;
import dev.vortex.core.catalog.ServiceCatalog;
import dev.vortex.core.environment.Environment;
import dev.vortex.core.evidence.CapacityRange;
import dev.vortex.core.execution.FailureReason;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.ExperimentIdentity;
import dev.vortex.core.port.Repositories.ExecutionRepository;
import dev.vortex.core.project.Project;
import dev.vortex.core.project.ProjectConfiguration;
import dev.vortex.core.project.ProjectReadiness;
import dev.vortex.core.shared.LoadLevel;
import dev.vortex.core.shared.OperationId;
import dev.vortex.core.shared.ProjectId;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.threshold.Durations;
import dev.vortex.core.workload.OperationMix;
import dev.vortex.core.workload.TestType;
import dev.vortex.core.workload.Workload;
import dev.vortex.core.workload.WorkloadSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Turns one service into the shapes the workspace renders.
 *
 * <p>One assembler rather than a method on each controller, because Overview, Tests and the run
 * chooser all show the same test row and the same header, and three constructions of "the same"
 * row would drift within a release. It is also the single place where a presentation decision is
 * allowed to be made about domain output — a URL for a readiness item, a label for a level — and
 * every other kind of decision is deliberately absent: what may be drawn, what may be compared,
 * whether a test can run and why not are all read from {@code vortex-core}, never re-derived here.
 */
@Component
public class WorkspaceAssembler {

    /** Enough history to find a service's latest run and its recent activity in one read. */
    public static final int RECENT_RUN_LIMIT = 50;

    /**
     * How many runs Overview ever has available to show before handing over to the Runs page.
     *
     * <p>Not how many are actually shown — the rail displays as many as fit beside the Tests column
     * (a client-side layout decision, {@code RecentRunsRail}'s own job), down to a minimum of 5. This
     * is the ceiling on how much history it can draw from to fill that space.
     */
    private static final int OVERVIEW_RUN_LIMIT = 20;

    private final ProjectService projects;
    private final CapacityService capacity;
    private final WorkloadDrift drift;
    private final WorkloadView workloadView;
    private final TestRunner testRunner;
    private final Display display;

    public WorkspaceAssembler(ProjectService projects, CapacityService capacity, WorkloadDrift drift,
            WorkloadView workloadView, TestRunner testRunner, Display display) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.capacity = Objects.requireNonNull(capacity, "capacity");
        this.drift = Objects.requireNonNull(drift, "drift");
        this.workloadView = Objects.requireNonNull(workloadView, "workloadView");
        this.testRunner = Objects.requireNonNull(testRunner, "testRunner");
        this.display = Objects.requireNonNull(display, "display");
    }

    // ---------------------------------------------------------------- header

    public ServiceHeaderDto header(Project project, ProjectConfiguration configuration,
            ServiceCatalog catalog, ProjectReadiness readiness, long runCount,
            TestExecution running) {

        Environment environment = configuration.environments().stream().findFirst().orElse(null);

        return new ServiceHeaderDto(
                project.id().value(),
                project.name(),
                blankToNull(project.description()),
                environment == null ? null : target(environment),
                configuration.environments().size(),
                configuration.serviceVersionIfPresent().orElse(null),
                readiness(project.id(), readiness),
                catalog == null ? 0 : catalog.operationCount(),
                configuration.workloads().size(),
                runCount,
                running == null ? null : new RunRefDto(running.id().value(),
                        running.plan().workloadName(), running.plan().testType().label(),
                        running.state().label()));
    }

    private TargetDto target(Environment environment) {
        dev.vortex.core.target.ExecutionTarget executionTarget = environment.target();
        // Only ExternalEndpointTarget has a genuine pre-run address — Docker/Compose targets resolve
        // one only once a run actually prepares the target, so baseUrl stays empty for them rather
        // than manufacturing a value, the same rule the domain model itself follows (see
        // ExecutionTarget's javadoc).
        String baseUrl = switch (executionTarget) {
            case dev.vortex.core.target.ExternalEndpointTarget external -> external.endpoint().value();
            case dev.vortex.core.target.DockerImageTarget ignored -> "";
            case dev.vortex.core.target.DockerComposeTarget ignored -> "";
        };
        String targetKind = switch (executionTarget) {
            case dev.vortex.core.target.ExternalEndpointTarget ignored -> "EXTERNAL_ENDPOINT";
            case dev.vortex.core.target.DockerImageTarget ignored -> "DOCKER_IMAGE";
            case dev.vortex.core.target.DockerComposeTarget ignored -> "DOCKER_COMPOSE";
        };
        return new TargetDto(
                environment.name(),
                baseUrl,
                environment.type().label(),
                environment.classification().name(),
                environment.classification().label(),
                environment.classification().caveat(),
                environment.dependencyMode().label(),
                targetKind,
                executionTarget.summary());
    }

    // ---------------------------------------------------------------- readiness

    private ReadinessDto readiness(ProjectId projectId, ProjectReadiness readiness) {
        List<ReadinessItemDto> items = readiness.items().stream()
                .map(item -> {
                    List<ProjectReadiness.Item> unmet = readiness.unmetPrerequisites(item);
                    return new ReadinessItemDto(item.key(), item.kind().name(), item.label(),
                            item.satisfied(), item.requiredToRun(),
                            readiness.effectivelyRequired(item), unmet.isEmpty(),
                            readiness.distinctFromWhatItNarrows(item),
                            unmet.stream().map(ProjectReadiness.Item::key).toList(),
                            unmet.isEmpty() ? null : item.blockedReason(), item.nextStep(),
                            "/services/" + projectId.value() + "/" + readinessTarget(item.key()));
                })
                .toList();

        return new ReadinessDto(readiness.canRun(), (int) readiness.satisfiedCount(),
                readiness.totalCount(), readiness.blockers().size(), items,
                readiness.nextAction().map(ProjectReadiness.Item::nextStep).orElse(null));
    }

    /**
     * Where a readiness item is satisfied, in the workbench's own layout.
     *
     * <p>{@code ProjectReadiness} states what is missing and what to do about it, and deliberately
     * knows nothing about screens. Mapping one to the other is a presentation decision, so it is
     * made here. Everything except defining and executing a test belongs in Configuration's own
     * sections.
     *
     * <p>Every target here has to be somewhere you actually arrive. Defining a workload used to point
     * at {@code tests}, which the SPA redirects straight back to Overview — a link that lands you
     * where you already were, offering to do the thing you clicked it for. It goes to the editor
     * instead. Executing one stays on {@code tests}: the run controls live on the test rows there,
     * and {@code run} is not an entry point — it prepares one named workload and needs to be told
     * which.
     *
     * <p>Switched on the item's key, never its label: a label is prose somebody will eventually
     * reword, and doing so should not silently drop every link through to {@code default}.
     */
    private String readinessTarget(String itemKey) {
        return switch (itemKey) {
            case "API_IMPORTED" -> "configuration#operations";
            case "ENVIRONMENT" -> "configuration#environments";
            case "WORKLOAD", "AVERAGE_LOAD_WORKLOAD" -> "tests/new";
            case "OBJECTIVES" -> "configuration#objectives";
            case "PRODUCTION_TRAFFIC" -> "configuration#production";
            case "TEST_EXECUTED" -> "tests";
            default -> "configuration";
        };
    }

    // ---------------------------------------------------------------- tests

    /**
     * Every configured test, in the order they appear in {@code vortex.yaml}.
     *
     * <p>Without per-test capacity evidence — used by the run chooser and the standalone Tests page,
     * neither of which shows evidence today.
     *
     * @param history recent executions for this service, newest first — passed in so a page showing
     *                both tests and runs reads the table once
     */
    public List<TestRowDto> tests(Project project, ProjectConfiguration configuration,
            ServiceCatalog catalog, List<TestExecution> history) {
        return tests(project, configuration, catalog, history, Map.of(), null);
    }

    /**
     * Every configured test, each carrying its own tested-capacity evidence.
     *
     * @param capacityByWorkload each test's own most recent {@link CapacityObservation}, from
     *                           {@link CapacityService#latestPerWorkload(ProjectId)} — read once per
     *                           request and passed in, the same reasoning as {@code history}
     * @param production        what production sends this service, shared across every test's range;
     *                           may be null
     */
    public List<TestRowDto> tests(Project project, ProjectConfiguration configuration,
            ServiceCatalog catalog, List<TestExecution> history,
            Map<String, CapacityObservation> capacityByWorkload, ProductionObservation production) {

        String environmentName = configuration.environments().stream()
                .findFirst().map(Environment::name).orElse(null);
        RequestsPerSecond productionPeak = configuration.productionObservationIfPresent()
                .map(ProductionObservation::peakRate).orElse(null);
        List<WorkloadDrift.Assessment> assessments = drift.assess(configuration);

        return configuration.workloads().stream()
                .map(workload -> testRow(project, workload, catalog, history, environmentName,
                        productionPeak, assessments, capacityByWorkload, production))
                .toList();
    }

    private TestRowDto testRow(Project project, Workload workload, ServiceCatalog catalog,
            List<TestExecution> history, String environmentName,
            RequestsPerSecond productionPeak, List<WorkloadDrift.Assessment> assessments,
            Map<String, CapacityObservation> capacityByWorkload, ProductionObservation production) {

        WorkloadView.Composition composition = workloadView.compose(workload, catalog);

        List<TestExecution> mine = history.stream()
                .filter(execution -> workload.name().equals(execution.plan().workloadName()))
                .toList();
        TestExecution latest = mine.stream()
                .filter(execution -> execution.state().isTerminal())
                .findFirst().orElse(null);

        List<String> problems = problemsRunning(project.id(), workload.name(), environmentName);

        DriftDto driftDto = assessments.stream()
                .filter(assessment -> assessment.workload().name().equals(workload.name()))
                .findFirst()
                .map(this::toDto)
                .orElse(null);

        // This test's own reading, never a different test's or the service's — the same generic
        // per-observation builders context() uses for the single service-wide figure, just called
        // once per workload instead of once per request.
        CapacityObservation observation = capacityByWorkload.get(workload.name());
        HeadroomCalculator.Result headroom = this.capacity.headroom(observation, production);
        CapacityRange testRange = observation != null
                ? CapacityRange.from(observation, production)
                : CapacityRange.productionOnly(production);

        return new TestRowDto(
                workload.name(),
                blankToNull(workload.description()),
                workload.question(),
                workload.type().name(),
                workload.type().label(),
                workload.type().question(),
                workload.type().isSaturating(),
                workload.model().name(),
                workload.model().label(),
                workloadView.headline(workload),
                workload.peakLevel().unit(),
                Durations.display(workload.totalDuration()),
                workload.stages().size(),
                workload.shape().isRamping(),
                workload.operations().size(),
                source(workload.source()),
                versusProduction(workload.peakLevel(), productionPeak),
                problems.isEmpty(),
                problems,
                environmentName,
                latest == null ? null : runSummary(latest, null),
                mine.size(),
                driftDto,
                mix(composition),
                composition.drift().map(BigDecimal::toPlainString).orElse(null),
                capacity(observation, headroom),
                range(testRange));
    }

    /**
     * Why this test could not run, in the domain's own words.
     *
     * <p>Resolution only — the plan is built and thrown away, and nothing is probed over the
     * network. That is what makes it affordable once per test on a page load, and it is enough to
     * catch every reason a test is structurally unrunnable: no environment, an operation that is not
     * in the imported description, or a mutating operation nobody has reviewed. The checks that do
     * cost something — is the load generator present, is the target reachable, do the referenced
     * secrets exist — belong to preflight, which is one click away and says so itself.
     */
    private List<String> problemsRunning(ProjectId projectId, String workloadName,
            String environmentName) {

        if (environmentName == null) {
            return List.of("No environment is configured, so Vortex does not know where to send "
                    + "traffic.");
        }
        try {
            testRunner.resolve(projectId, workloadName, environmentName, null, List.of());
            return List.of();
        } catch (PlanResolutionException e) {
            return e.problems().isEmpty() ? List.of(e.getMessage()) : e.problems();
        }
    }

    private SourceDto source(WorkloadSource source) {
        return new SourceDto(
                source.kind().name(),
                source.kind().label(),
                source.describe(),
                blankToNull(source.detail()),
                source.isProductionInformed(),
                source.observation().isKnown() ? source.observation().describe() : null,
                source.derivationIfPresent().orElse(null));
    }

    /**
     * The tested level as a multiple of what production actually sends.
     *
     * <p>Only where both measure the same quantity. Virtual users and requests per second are not
     * divisible, and this is the same refusal {@code HeadroomCalculator} makes about capacity —
     * made here for the same reason and never worked around because the figure would be useful.
     */
    private String versusProduction(LoadLevel level, RequestsPerSecond productionPeak) {
        if (productionPeak == null || productionPeak.asDouble() <= 0
                || !level.sameQuantityAs(productionPeak)) {
            return null;
        }
        BigDecimal multiple = BigDecimal.valueOf(level.asDouble())
                .divide(BigDecimal.valueOf(productionPeak.asDouble()), 2, RoundingMode.HALF_UP);
        return multiple.stripTrailingZeros().toPlainString() + "× observed production peak";
    }

    private DriftDto toDto(WorkloadDrift.Assessment assessment) {
        return switch (assessment) {
            case WorkloadDrift.Unchanged unchanged -> new DriftDto("UNCHANGED",
                    unchanged.statement(), null, null, null);
            case WorkloadDrift.Drifted drifted -> new DriftDto("DRIFTED", drifted.statement(),
                    drifted.derivedFrom().displayWithUnit(), drifted.proposedNow().displayWithUnit(),
                    drifted.derivation());
            case WorkloadDrift.NotAssessable notAssessable -> new DriftDto("NOT_ASSESSABLE",
                    notAssessable.statement(), null, null, null);
        };
    }

    public List<MixRowDto> mix(WorkloadView.Composition composition) {
        return composition.rows().stream()
                .map(row -> new MixRowDto(row.operationId(), row.label(), row.method(), row.path(),
                        row.sharePercent(), row.shareFraction().doubleValue(),
                        row.rate().map(RequestsPerSecond::display).orElse(null), row.known()))
                .toList();
    }

    /** The six questions Vortex can answer, each with how many tests already answer it. */
    public List<TestTypeDto> testTypes(ProjectConfiguration configuration) {
        return Arrays.stream(TestType.values())
                .map(type -> new TestTypeDto(type.name(), type.label(), type.question(),
                        type.guidance(), type.isSaturating(),
                        (int) configuration.workloads().stream()
                                .filter(workload -> workload.type() == type).count()))
                .toList();
    }

    // ---------------------------------------------------------------- evidence by test type

    /**
     * Every test type's own most recent evidence, in the same order {@link #testTypes} teaches them —
     * one entry per {@code TestType}, always, so a freshly configured service says it has not tested
     * the other five yet rather than omitting them.
     *
     * <p>Built entirely from {@code tests} — already assembled by {@link #tests} for the same request,
     * each carrying its own {@code testType}, {@code latestRun} and {@code capacity} — so this is a
     * rollup over numbers the domain already produced, never a second read of execution history and
     * never a capacity or headroom figure computed here.
     *
     * @param running the execution currently in flight for this service, or null. Kept independent of
     *                whichever test type's evidence is shown, so a first-ever run in progress is never
     *                confused with — or allowed to overwrite — prior completed evidence
     */
    public List<TestTypeEvidenceDto> evidenceByTestType(List<TestRowDto> tests, TestExecution running) {
        return Arrays.stream(TestType.values())
                .map(type -> evidenceForType(type, tests, running))
                .toList();
    }

    private TestTypeEvidenceDto evidenceForType(TestType type, List<TestRowDto> tests,
            TestExecution running) {

        TestRowDto latest = tests.stream()
                .filter(test -> test.testType().equals(type.name()) && test.latestRun() != null)
                .max(Comparator.comparing(test -> Instant.parse(test.latestRun().isoTimestamp())))
                .orElse(null);

        boolean isRunning = running != null && running.plan().testType() == type;
        String runningWorkloadName = isRunning ? running.plan().workloadName() : null;

        if (latest == null) {
            return new TestTypeEvidenceDto(type.name(), type.label(), false,
                    null, null, null, null,
                    null, null, null, null,
                    null, null, null, null,
                    isRunning, runningWorkloadName);
        }

        RunSummaryDto run = latest.latestRun();
        CapacityDto testCapacity = latest.capacity();
        Primary primary = primaryFor(type, run, testCapacity);

        return new TestTypeEvidenceDto(type.name(), type.label(), true,
                run.verdict(), run.verdictLabel(), primary.kind(), primary.value(),
                testCapacity == null ? null : testCapacity.headroom(),
                latest.name(), run.environmentName(), run.release(),
                run.id(), display.freshness(Instant.parse(run.isoTimestamp())), run.isoTimestamp(),
                run.answer(), isRunning, runningWorkloadName);
    }

    private record Primary(String kind, String value) {
    }

    /**
     * What a test type's evidence is actually about, decided once here from figures the domain already
     * computed — never re-guessed by a renderer switching on the test type's name.
     *
     * <p>Smoke has no meaningful throughput of its own — a pass/fail is the whole story. Average load
     * and Spike are read from the run itself: the level that run actually used, not today's configured
     * level, since evidence describes what was measured. Stress and Breakpoint are read from the same
     * tested-capacity conclusion ({@code CapacityDto}) shown elsewhere on Overview — the highest
     * sustained level when it is quotable, the domain's own boundary sentence otherwise. Soak is its
     * measured duration, since "how long did it hold" is the question a soak test answers.
     */
    private Primary primaryFor(TestType type, RunSummaryDto run, CapacityDto capacity) {
        return switch (type) {
            case SMOKE -> new Primary("OUTCOME", run.verdictLabel());
            case AVERAGE_LOAD, SPIKE -> new Primary("RATE", run.levelDisplay());
            case STRESS, BREAKPOINT -> {
                if (capacity != null && capacity.quotable()) {
                    yield new Primary("RATE", capacity.compliantLevel());
                }
                yield new Primary("OUTCOME",
                        capacity != null ? capacity.boundaryStatusLabel() : run.verdictLabel());
            }
            case SOAK -> new Primary("DURATION", run.durationDisplay());
        };
    }

    // ---------------------------------------------------------------- runs

    /**
     * One run, as a row.
     *
     * @param currentFingerprint the fingerprint the test as configured now would produce, so the row
     *                           can say whether re-running it would test the same thing. Null where
     *                           that is unknown — the test may have been renamed, edited into
     *                           something unresolvable, or deleted — and the row then claims nothing
     */
    public RunSummaryDto runSummary(TestExecution execution, String currentFingerprint) {
        EffectiveTestPlan plan = execution.plan();

        String answer = execution.summaryIfPresent()
                .map(DeterministicSummary::answer)
                .orElseGet(() -> execution.failureReasonIfPresent()
                        .map(FailureReason::guidance)
                        .orElse(execution.state().label()));

        String p95 = execution.resultsIfPresent()
                .flatMap(results -> results.latency().p95())
                .map(Durations::display)
                .orElse(null);

        Boolean matches = null;
        List<String> differences = List.of();
        if (currentFingerprint != null) {
            matches = currentFingerprint.equals(plan.fingerprint().hash());
        }

        return new RunSummaryDto(
                execution.id().value(),
                execution.verdict().name(),
                display.verdictLabel(execution.verdict()),
                execution.state().label(),
                execution.state().isTerminal(),
                plan.workloadName(),
                plan.testType().name(),
                plan.testType().label(),
                plan.peakLevel().displayWithUnit(),
                plan.environmentName(),
                plan.classification().name(),
                blankToNull(plan.serviceVersion()),
                answer,
                p95,
                execution.duration().map(Durations::display).orElse(null),
                display.relative(execution.requestedAt()),
                DateTimeFormatter.ISO_INSTANT.format(execution.requestedAt()),
                matches,
                differences);
    }

    /**
     * Run rows that know whether the test behind them has moved since.
     *
     * <p>Resolving the current definition once per distinct test name rather than once per run: a
     * history of forty runs of one test is one resolution, not forty.
     */
    public List<RunSummaryDto> runSummaries(ProjectId projectId, ProjectConfiguration configuration,
            List<TestExecution> executions) {

        String environmentName = configuration.environments().stream()
                .findFirst().map(Environment::name).orElse(null);

        Map<String, String> fingerprints = new HashMap<>();
        return executions.stream().map(execution -> {
            String name = execution.plan().workloadName();
            String fingerprint = fingerprints.computeIfAbsent(name, testName ->
                    currentFingerprint(projectId, configuration, testName, environmentName));
            RunSummaryDto row = runSummary(execution, "".equals(fingerprint) ? null : fingerprint);
            return withDifferences(row, execution, projectId, configuration, testName(execution),
                    environmentName);
        }).toList();
    }

    private String testName(TestExecution execution) {
        return execution.plan().workloadName();
    }

    /**
     * The fingerprint the named test would produce right now.
     *
     * @return an empty string where the test no longer resolves, which is not the same as a
     *         mismatch and must not be rendered as one
     */
    private String currentFingerprint(ProjectId projectId, ProjectConfiguration configuration,
            String testName, String environmentName) {

        if (environmentName == null || configuration.workloadByName(testName).isEmpty()) {
            return "";
        }
        try {
            return testRunner.resolve(projectId, testName, environmentName, null, List.of())
                    .fingerprint().hash();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * What moved, where a run and its test no longer describe the same experiment.
     *
     * <p>Phrased by {@code ExperimentIdentity}, which already compares the dimensions that decide
     * comparability and already says so in sentences. Nothing here decides what counts as a
     * difference.
     */
    private RunSummaryDto withDifferences(RunSummaryDto row, TestExecution execution,
            ProjectId projectId, ProjectConfiguration configuration, String testName,
            String environmentName) {

        if (row.matchesCurrentTest() == null || row.matchesCurrentTest()) {
            return row;
        }
        try {
            EffectiveTestPlan current = testRunner.resolve(projectId, testName, environmentName,
                    null, List.of());
            List<String> differences =
                    ExperimentIdentity.compare(execution.plan(), current).differences();
            return new RunSummaryDto(row.id(), row.verdict(), row.verdictLabel(), row.stateLabel(),
                    row.terminal(), row.testName(), row.testType(), row.testTypeLabel(),
                    row.levelDisplay(), row.environmentName(), row.classification(), row.release(),
                    row.answer(), row.p95(), row.durationDisplay(), row.relativeTime(),
                    row.isoTimestamp(), row.matchesCurrentTest(), differences);
        } catch (RuntimeException e) {
            return row;
        }
    }

    // ---------------------------------------------------------------- capacity

    public CapacityRangeDto range(CapacityRange range) {
        if (range == null || !range.isRenderable()) {
            return new CapacityRangeDto(false, null, List.of(), false);
        }
        List<MarkerDto> markers = range.markers().stream()
                .map(marker -> new MarkerDto(marker.kind().name(), marker.label(),
                        marker.level().displayWithUnit(), range.position(marker.level())))
                .toList();
        return new CapacityRangeDto(true, range.unit(), markers, range.isOpenEnded());
    }

    /**
     * Tested capacity with the conditions it holds under, or null when no run has established one.
     *
     * <p>Never the figure alone. Tested capacity is not a property a service has — it moves with the
     * version, the environment, the dependency mode and the size of the data — so the conditions
     * travel with it or it does not travel at all.
     */
    public CapacityDto capacity(CapacityObservation observation, HeadroomCalculator.Result headroom) {
        if (observation == null) {
            return null;
        }
        return new CapacityDto(
                observation.compliantLevel().displayWithUnit(),
                observation.label(),
                observation.boundary(),
                observation.boundaryLabel(),
                observation.isQuotable(),
                observation.boundaryStatus().name(),
                observation.boundaryStatus().label(),
                // The bare label. Display's version appends "evidence", which reads as
                // "boundary confidence Low evidence" once the field has a label of its own.
                observation.boundaryStrength().label(),
                observation.firstNonCompliantIfPresent()
                        .map(BoundaryEdge::describe).orElse(null),
                headroom != null && headroom.isAvailable()
                        ? headroom.value().map(Headroom::display).orElse(null) : null,
                headroom == null ? "No comparison was attempted." : headroom.reason().orElse(null),
                blankToNull(observation.serviceVersion()),
                observation.environmentName(),
                observation.classification().name(),
                observation.dependencyMode().name(),
                observation.workloadName(),
                observation.operationMix(),
                observation.thresholdSummary(),
                Durations.display(observation.duration()),
                display.timestamp(observation.observedAt()),
                observation.executionId().value(),
                observation.conditions(),
                observation.constraintCandidates().stream()
                        .map(this::toDto)
                        .toList());
    }

    private WorkspaceDtos.ConstraintCandidateDto toDto(
            dev.vortex.core.capacity.ConstraintCandidate candidate) {
        return new WorkspaceDtos.ConstraintCandidateDto(
                candidate.describe(),
                candidate.strength().label(),
                "Support: " + candidate.strength().label() + ", stage boundaries "
                        + candidate.alignmentBasis().label() + ".");
    }

    // ---------------------------------------------------------------- production

    public ProductionDto production(ProductionObservation observation, ServiceCatalog catalog) {
        if (observation == null) {
            return null;
        }
        return new ProductionDto(
                observation.peakRate().displayWithUnit(),
                observation.averageRateIfPresent().map(RequestsPerSecond::displayWithUnit)
                        .orElse(null),
                observation.p95ObservedRateIfPresent().map(RequestsPerSecond::displayWithUnit)
                        .orElse(null),
                observation.hasSource() ? observation.source() : null,
                observation.isAttributed(),
                observation.wasFetched(),
                observation.observation().isKnown() ? observation.observation().describe() : null,
                blankToNull(observation.note()),
                observation.qualityFacts(),
                observedMix(observation, catalog),
                observation.mixCoverageIfPresent()
                        .map(OperationMixCoverage::describe).orElse(null));
    }

    /**
     * How production's traffic divides across operations.
     *
     * <p>Shares only. There is no per-operation rate here even though the peak is known, because
     * dividing a peak by an average composition would produce a figure nobody measured.
     */
    private List<MixRowDto> observedMix(ProductionObservation observation, ServiceCatalog catalog) {
        Optional<OperationMix> mix = observation.observedMixIfPresent();
        if (mix.isEmpty()) {
            return List.of();
        }
        return mix.get().shares().entrySet().stream()
                .map(share -> observedMixRow(share.getKey(), share.getValue(), mix.get(), catalog))
                .toList();
    }

    private MixRowDto observedMixRow(OperationId operationId, BigDecimal fraction, OperationMix mix,
            ServiceCatalog catalog) {

        Optional<Operation> operation = catalog == null
                ? Optional.<Operation>empty()
                : catalog.find(operationId);

        return new MixRowDto(
                operationId.value(),
                operation.map(Operation::label).orElse(operationId.value()),
                operation.map(op -> op.method().name()).orElse(""),
                operation.map(Operation::path).orElse(""),
                mix.sharePercent(operationId) + "%",
                fraction.doubleValue(),
                null,
                operation.isPresent());
    }

    // ---------------------------------------------------------------- shared reads

    /**
     * One read of everything a workspace screen needs about a service.
     *
     * <p>Assembled once per request and handed around, so the header, the test rows and the run list
     * cannot disagree about which run is the latest or how many there are.
     */
    public record Context(Project project, ProjectConfiguration configuration, ServiceCatalog catalog,
            ProjectReadiness readiness, List<TestExecution> history, long runCount,
            CapacityObservation observation, HeadroomCalculator.Result headroom,
            CapacityRange range, ProductionObservation production) {

        public TestExecution latestTerminal() {
            return history.stream().filter(execution -> execution.state().isTerminal())
                    .findFirst().orElse(null);
        }

        public TestExecution running() {
            return history.stream().filter(execution -> !execution.state().isTerminal())
                    .findFirst().orElse(null);
        }

        public List<TestExecution> recent(int limit) {
            return history.stream().filter(execution -> execution.state().isTerminal())
                    .limit(limit).toList();
        }

        /**
         * Whether the evidence describes a release the service has since moved off.
         *
         * <p>Not staleness — nothing here knows whether the change mattered. Only that the two
         * versions differ, and that running a test is what would settle it.
         */
        public boolean evidencePredatesRelease() {
            if (observation == null) {
                return false;
            }
            String measured = observation.serviceVersion();
            String current = configuration.serviceVersion();
            return measured != null && !measured.isBlank()
                    && current != null && !current.isBlank()
                    && !measured.equals(current);
        }
    }

    public Context context(ProjectId projectId,
            ExecutionRepository executions) {

        Project project = projects.find(projectId).orElseThrow(
                () -> new IllegalArgumentException("No service with id " + projectId.value()));
        ProjectConfiguration configuration = projects.configuration(projectId);
        ServiceCatalog catalog = projects.catalog(projectId).orElse(null);

        List<TestExecution> history = executions.findByProject(projectId, RECENT_RUN_LIMIT).stream()
                .sorted(Comparator.comparing(TestExecution::requestedAt).reversed())
                .toList();
        long runCount = executions.countByProject(projectId);

        ProjectReadiness readiness = configuration.readiness(
                catalog != null && !catalog.isEmpty(), runCount > 0);

        ProductionObservation production =
                configuration.productionObservationIfPresent().orElse(null);
        CapacityObservation observation = capacity.latest(projectId).orElse(null);
        HeadroomCalculator.Result headroom = capacity.headroom(observation, production);
        CapacityRange range = observation != null
                ? CapacityRange.from(observation, production)
                : CapacityRange.productionOnly(production);

        return new Context(project, configuration, catalog, readiness, history, runCount,
                observation, headroom, range, production);
    }

    // ---------------------------------------------------------------- evidence

    /**
     * Capacity history grouped by release, newest release first, each observation carrying its own
     * headroom-or-refusal against the production traffic recorded now.
     *
     * <p>Headroom is computed here rather than read off the observation, because it compares a fixed
     * past measurement against production traffic recorded <em>now</em> — a service whose traffic
     * doubled has less headroom than it did yesterday, without any run having happened.
     */
    public List<WorkspaceDtos.CapacityHistoryEntryDto> capacityHistory(
            java.util.SequencedMap<String, List<CapacityObservation>> byVersion,
            ProductionObservation production, String currentVersion) {

        List<WorkspaceDtos.CapacityHistoryEntryDto> entries = new java.util.ArrayList<>();
        for (var entry : byVersion.entrySet()) {
            List<CapacityDto> observations = entry.getValue().stream()
                    .map(observation -> capacity(observation, this.capacity.headroom(observation,
                            production)))
                    .toList();
            entries.add(new WorkspaceDtos.CapacityHistoryEntryDto(entry.getKey(),
                    entry.getKey().equals(blankToNull(currentVersion)), observations));
        }
        return entries;
    }

    /** The headroom figure alone, for a summary line — the reason is carried on {@link CapacityDto}. */
    public String headroomLabel(HeadroomCalculator.Result headroom) {
        return headroom != null && headroom.isAvailable()
                ? headroom.value().map(Headroom::display).orElse(null)
                : null;
    }

    public int overviewRunLimit() {
        return OVERVIEW_RUN_LIMIT;
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
