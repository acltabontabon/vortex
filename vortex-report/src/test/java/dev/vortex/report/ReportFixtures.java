package dev.vortex.report;

import dev.vortex.core.analysis.BreakpointDetector;
import dev.vortex.core.analysis.DeterministicSummary;
import dev.vortex.core.analysis.SystemSaturationDetector;
import dev.vortex.core.application.DeterministicAnalyzer;
import dev.vortex.core.application.RunEvidenceService;
import dev.vortex.core.comparison.RegressionEvaluator;
import dev.vortex.core.evidence.EvidenceSanitizer;
import dev.vortex.core.evidence.FindingDetector;
import dev.vortex.core.evidence.RunEvidence;
import dev.vortex.core.execution.ExecutionArtifacts;
import dev.vortex.core.execution.ExecutionState;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.metrics.Aggregation;
import dev.vortex.core.metrics.LatencyPercentiles;
import dev.vortex.core.metrics.MeasuredResults;
import dev.vortex.core.metrics.MetricObservation;
import dev.vortex.core.metrics.MetricSeries;
import dev.vortex.core.metrics.SamplePoint;
import dev.vortex.core.metrics.MetricSource;
import dev.vortex.core.metrics.MetricUnit;
import dev.vortex.core.metrics.ObservationProvenance;
import dev.vortex.core.metrics.ObservationTrace;
import dev.vortex.core.metrics.OperationMetrics;
import dev.vortex.core.metrics.TimeWindow;
import dev.vortex.core.catalog.HttpMethod;
import dev.vortex.core.catalog.PayloadProvenance;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.PlannedOperation;
import dev.vortex.core.plan.ToolVersions;
import dev.vortex.core.port.Clock;
import dev.vortex.core.resource.ResourceKind;
import dev.vortex.core.resource.ResourceLimit;
import dev.vortex.core.resource.ResourceScope;
import dev.vortex.core.resource.ResourceSignal;
import dev.vortex.core.shared.ErrorRate;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.shared.OperationId;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.threshold.ThresholdEvaluation;
import dev.vortex.core.threshold.ThresholdEvaluator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the runs the exporters are tested against.
 *
 * <p>Deliberately shared by all three exporters. A PDF test and a Markdown test asserting different
 * numbers for "the same run" would be worse than either test not existing, because it would look
 * like coverage.
 */
public final class ReportFixtures {

    public static final Instant GENERATED_AT = Instant.parse("2026-08-21T11:00:00Z");

    private static final RunEvidenceService SERVICE = new RunEvidenceService(
            new DeterministicAnalyzer(new ThresholdEvaluator(), new BreakpointDetector(),
                    new SystemSaturationDetector()),
            new FindingDetector(), new EvidenceSanitizer(), new RegressionEvaluator(),
            Clock.fixed(GENERATED_AT));

    private ReportFixtures() {
    }

    /** A complete, healthy run with per-operation figures and service telemetry. */
    public static RunEvidence rich() {
        MeasuredResults results = results(281, 0.0008, perOperation(), observations());
        return assemble(execution(Fixtures.plan(), results));
    }

    /** A run that met nothing, delivered less than it offered, and saturated a pool. */
    public static RunEvidence failing() {
        MeasuredResults results = results(1_400, 0.06, perOperation(), observations());
        return assemble(execution(Fixtures.plan(), results));
    }

    /**
     * The sparsest run that can still be reported: no per-operation breakdown, no series, no
     * telemetry, no objectives.
     *
     * <p>Every renderer has to survive this. Optional evidence being absent is the normal case for a
     * team that has not wired up an observability provider, and a report that breaks for them is a
     * report that does not work.
     */
    public static RunEvidence sparse() {
        MeasuredResults bare = new MeasuredResults(
                new TimeWindow(Fixtures.NOW, Fixtures.NOW.plusSeconds(600)),
                null, null, 100, 0, LatencyPercentiles.empty(), Map.of(), null, List.of());

        DeterministicSummary summary = new DeterministicSummary(
                "Does it work at all?", dev.vortex.core.threshold.Verdict.NOT_EVALUATED,
                "Not established.", bare, ThresholdEvaluation.empty(), null, null, List.of());

        return assemble(execution(Fixtures.plan(), bare, summary));
    }

    /**
     * A wide run: enough operations that a table must split across pages and repeat its header.
     *
     * <p>The plan is built with the operations rather than only the metrics, because the breakdown
     * is driven by what was planned — an earlier version of this fixture supplied fifty sets of
     * measurements against a two-operation plan and produced a two-row table, which would have made
     * the pagination test pass without ever paginating.
     */
    public static RunEvidence manyOperations(int count) {
        List<PlannedOperation> planned = new ArrayList<>();
        Map<OperationId, OperationMetrics> measured = new LinkedHashMap<>();

        for (int i = 0; i < count; i++) {
            OperationId id = OperationId.of("operation" + i);
            planned.add(new PlannedOperation(id, "GET /resource/" + i + "/detail", "op" + i,
                    HttpMethod.GET, "/resource/" + i + "/detail",
                    dev.vortex.core.data.RequestData.EMPTY, PayloadProvenance.SCHEMA_GENERATED, null,
                    BigDecimal.ONE.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP),
                    RequestsPerSecond.of(10 + i)));

            measured.put(id, new OperationMetrics(id, "GET /resource/" + i + "/detail", null,
                    RequestsPerSecond.of(10 + i), 1_000 + i, i,
                    LatencyPercentiles.builder().atMillis(95, 100 + i).atMillis(99, 200 + i)
                            .build()));
        }

        EffectiveTestPlan base = Fixtures.plan();
        EffectiveTestPlan wide = new EffectiveTestPlan(base.id(), base.projectId(),
                base.projectName(), base.serviceVersion(), base.intent(), base.workloadName(),
                base.workloadDescription(), base.testType(), base.workloadModel(),
                base.peakLevel(), base.stages(), planned, base.workloadSource(), base.thresholds(),
                base.environmentName(), base.environmentType(), base.configuredTarget(),
                base.effectiveTarget(), base.targetRewriteReason(), base.dependencyMode(),
                base.classification(), base.headers(), base.k6Options(), base.runner(),
                base.scriptSource(), base.safetyDecisions(), base.fingerprint());

        return assemble(execution(wide, results(281, 0.0008, measured, observations())));
    }

    // ---------------------------------------------------------------- building blocks

    private static RunEvidence assemble(TestExecution execution) {
        return SERVICE.assemble(execution, "/tmp/executions/" + execution.id().value(),
                List.of("plan.json", "generated-test.js", "k6-summary.json"));
    }

    private static MeasuredResults results(long p95Millis, double errorFraction,
            Map<OperationId, OperationMetrics> operations, List<MetricObservation> observations) {
        MeasuredResults base = Fixtures.results(p95Millis, errorFraction);
        return new MeasuredResults(base.window(), base.targetLoad(), base.achievedRate(),
                base.requests(), base.failures(), base.latency(), operations, series(),
                observations, List.of(), List.of(), base.generation(), base.phases(),
                base.reliability(), classified(observations));
    }

    /**
     * The observations above, classified the way the Actuator adapter classifies them.
     *
     * <p>Both lists are populated because that is what a provider does: the observation is what the
     * report renders, and the typed signal is what a finding about a limit may rest on. A fixture
     * that filled only the first would produce a report with a resources table and no resource
     * findings — which is a real state, but not the one this golden file is describing.
     */
    private static List<ResourceSignal> classified(List<MetricObservation> observations) {
        return observations.stream()
                .map(observation -> new ResourceSignal(observation,
                        observation.id().contains("cpu") ? ResourceKind.CPU : ResourceKind.POOL,
                        ResourceScope.SYSTEM_UNDER_TEST,
                        ResourceLimit.inherentTo(observation.unit())))
                .toList();
    }

    /**
     * A ramping series with a genuine hole in it.
     *
     * <p>The gap is deliberate. Every renderer has to draw a period where nothing was measured as a
     * gap rather than bridging it, and a fixture with no gaps would never catch a renderer that
     * quietly interpolates.
     */
    private static MetricSeries series() {
        List<SamplePoint> points = new ArrayList<>();
        Instant cursor = Fixtures.NOW;
        for (int i = 0; i < 60; i++) {
            boolean measured = i < 25 || i > 28;
            double level = 20 + i;
            points.add(new SamplePoint(cursor, Duration.ofSeconds(5),
                    measured ? RequestsPerSecond.of(level * 0.99) : null,
                    ErrorRate.ofFraction(i < 45 ? 0.001 : 0.02),
                    measured ? Duration.ofMillis(90 + i * 6L) : null,
                    RequestsPerSecond.of(level)));
            cursor = cursor.plusSeconds(5);
        }
        return new MetricSeries(Duration.ofSeconds(5), points);
    }

    private static Map<OperationId, OperationMetrics> perOperation() {
        Map<OperationId, OperationMetrics> operations = new LinkedHashMap<>();
        operations.put(Fixtures.GET_ACCOUNT, new OperationMetrics(Fixtures.GET_ACCOUNT,
                "getAccount", null, RequestsPerSecond.of(60), 36_000, 4,
                LatencyPercentiles.builder().atMillis(95, 90).atMillis(99, 170).build()));
        operations.put(Fixtures.GET_ORDER, new OperationMetrics(Fixtures.GET_ORDER,
                "getOrder", null, RequestsPerSecond.of(40), 24_000, 21,
                LatencyPercentiles.builder().atMillis(95, 320).atMillis(99, 610).build()));
        return operations;
    }

    private static List<MetricObservation> observations() {
        TimeWindow window = new TimeWindow(Fixtures.NOW, Fixtures.NOW.plusSeconds(600));
        return List.of(
                MetricObservation.of("metric:hikaricp.connections.utilization",
                                "hikaricp.connections.utilization", MetricSource.ACTUATOR,
                                MetricUnit.PERCENT, Aggregation.MAX, 94, window)
                        .withProvenance(new ObservationProvenance("actuator",
                                "hikaricp.connections.active / hikaricp.connections.max", "",
                                "http://localhost:8080/actuator/metrics/hikaricp.connections.active"))
                        .withTrace(new ObservationTrace(31, 94, 47,
                                Fixtures.NOW.plusSeconds(360))),
                MetricObservation.of("metric:system.cpu.usage", "system.cpu.usage",
                                MetricSource.ACTUATOR, MetricUnit.PERCENT, Aggregation.MAX, 81,
                                window)
                        .withTrace(new ObservationTrace(42, 81, 55, null)));
    }

    private static TestExecution execution(EffectiveTestPlan plan, MeasuredResults results) {
        ThresholdEvaluation evaluation =
                new ThresholdEvaluator().evaluate(Fixtures.thresholds(), results);

        DeterministicSummary summary = new DeterministicSummary(
                "Can the service hold 20 requests/sec within its objectives?",
                evaluation.overall(),
                evaluation.passed() ? "Yes, with every objective met."
                        : "No — at least one objective was violated.",
                results, evaluation, null, null,
                List.of("Dependencies were mocked, so this cannot establish production capacity."));

        return execution(plan, results, summary);
    }

    private static TestExecution execution(EffectiveTestPlan plan, MeasuredResults results,
            DeterministicSummary summary) {
        return new TestExecution(
                ExecutionId.of("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"), plan.projectId(), plan,
                ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW.plusSeconds(1),
                Fixtures.NOW.plusSeconds(601), results, summary,
                new ToolVersions("0.1.0", "k6 v1.3.0", "Java 25", ""),
                ExecutionArtifacts.empty().with("plan.json", "plan.json"), null, "");
    }
}
