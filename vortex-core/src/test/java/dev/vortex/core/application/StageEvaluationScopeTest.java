package dev.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.analysis.BreakpointDetector;
import dev.vortex.core.analysis.SystemSaturationDetector;
import dev.vortex.core.capacity.HeadroomCalculator;
import dev.vortex.core.capacity.CapacityObservation;
import dev.vortex.core.execution.ExecutionState;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.metrics.MeasuredResults;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.port.Clock;
import dev.vortex.core.port.Repositories.CapacityObservationRepository;
import dev.vortex.core.workload.TestType;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.shared.OperationId;
import dev.vortex.core.shared.Percentile;
import dev.vortex.core.shared.ProjectId;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.threshold.LatencyThreshold;
import dev.vortex.core.threshold.ThresholdEvaluator;
import dev.vortex.core.threshold.ThresholdSet;
import dev.vortex.core.threshold.Verdict;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a stage-level view is allowed to conclude.
 *
 * <p>The time series Vortex builds from the engine's sample stream is aggregate: one p95 per
 * bucket, across every operation in the mix. That is enough to say where the run as a whole left
 * its objectives, and not enough to say anything at all about a particular operation.
 *
 * <p>The distinction matters because the SLO breakpoint is the most quotable sentence Vortex
 * produces. "Objectives were first violated at 150 requests/sec" reads as a measurement of the
 * service. If it were produced by holding a 200 ms objective written for a cheap account lookup
 * against latency that is mostly order submission, it would be a measurement of nothing — and it
 * would look exactly the same.
 */
class StageEvaluationScopeTest {

    private final DeterministicAnalyzer analyzer = new DeterministicAnalyzer(
            new ThresholdEvaluator(), new BreakpointDetector(), new SystemSaturationDetector());

    /** A four-stage ramp whose only objective is scoped to one operation in the mix. */
    private static EffectiveTestPlan rampWithOperationScopedObjectiveOnly() {
        return rampWith(ThresholdSet.of(
                LatencyThreshold.of(Fixtures.GET_ORDER, Percentile.P95, Duration.ofMillis(200))));
    }

    private static EffectiveTestPlan rampWith(ThresholdSet thresholds) {
        EffectiveTestPlan base = Fixtures.plan(TestType.BREAKPOINT, Fixtures.breakpointShape());
        return new EffectiveTestPlan(base.id(), base.projectId(), base.projectName(),
                base.serviceVersion(), base.intent(), base.workloadName(),
                base.workloadDescription(), base.testType(), base.workloadModel(),
                base.peakLevel(), base.stages(), base.operations(), base.workloadSource(),
                thresholds, base.environmentName(), base.environmentType(),
                base.configuredTarget(), base.effectiveTarget(), base.targetRewriteReason(),
                base.dependencyMode(), base.classification(), base.headers(), base.k6Options(),
                base.runner(), base.scriptSource(), base.safetyDecisions(), null)
                .withComputedFingerprint();
    }

    /** Aggregate measurements only — no per-operation breakdown, as a raw stream without one gives. */
    private static MeasuredResults aggregateOnly(EffectiveTestPlan plan) {
        MeasuredResults shape = Fixtures.results(150, 0.0);
        return new MeasuredResults(shape.window(), plan.peakLevel(),
                RequestsPerSecond.of(120), 30_000, 0, shape.latency(),
                Map.of(), Fixtures.degradingSeries(plan.stages()), List.of());
    }

    @Test
    @DisplayName("an objective scoped to one operation is not judged against aggregate latency")
    void scopedObjectivesDoNotDriveStageCompliance() {
        EffectiveTestPlan plan = rampWithOperationScopedObjectiveOnly();

        var stages = analyzer.deriveStages(plan, aggregateOnly(plan));

        // The aggregate p95 climbs past 200 ms from the second stage onwards. getOrder's objective
        // must not be charged with that: the series cannot tell how much of it was getOrder.
        assertThat(stages).isNotEmpty();
        assertThat(stages).allSatisfy(stage ->
                assertThat(stage.violatedThresholds()).isEmpty());
    }

    @Test
    @DisplayName("so no breakpoint is claimed from an objective the series cannot evaluate")
    void noBreakpointIsInventedForAScopedObjective() {
        EffectiveTestPlan plan = rampWithOperationScopedObjectiveOnly();

        var summary = analyzer.analyze(plan, aggregateOnly(plan));

        assertThat(summary.sloBreakpointIfPresent()).isEmpty();
        // And the run says plainly that it could not check the one objective it had, rather than
        // reporting a level at which that objective supposedly failed.
        assertThat(summary.verdict()).isEqualTo(Verdict.NOT_EVALUATED);
    }

    @Test
    @DisplayName("an overall objective still places the breakpoint")
    void overallObjectivesStillDriveStageCompliance() {
        EffectiveTestPlan plan = rampWith(ThresholdSet.of(
                LatencyThreshold.of(Percentile.P95, Duration.ofMillis(200))));

        var summary = analyzer.analyze(plan, aggregateOnly(plan));

        assertThat(summary.sloBreakpointIfPresent()).isPresent();
    }

    @Test
    @DisplayName("a run with both kinds of objective warns that the breakpoint covers only one")
    void mixedScopesAreCalledOut() {
        EffectiveTestPlan plan = rampWith(ThresholdSet.of(
                LatencyThreshold.of(Percentile.P95, Duration.ofMillis(200)),
                LatencyThreshold.of(Fixtures.GET_ORDER, Percentile.P95, Duration.ofMillis(50))));

        var summary = analyzer.analyze(plan, aggregateOnly(plan));

        assertThat(summary.notes())
                .anyMatch(note -> note.contains("overall objectives only"));
    }

    @Test
    @DisplayName("a run whose objectives were never checked does not establish a capacity")
    void unevaluatedRunsRecordNoCapacity() {
        EffectiveTestPlan plan = rampWithOperationScopedObjectiveOnly();
        MeasuredResults results = aggregateOnly(plan);
        var summary = analyzer.analyze(plan, results);
        assertThat(summary.verdict()).isEqualTo(Verdict.NOT_EVALUATED);

        var recorded = new ArrayList<CapacityObservation>();
        var capacity = new CapacityService(recordingRepository(recorded),
                new HeadroomCalculator(), Clock.fixed(Fixtures.NOW));

        var execution = new TestExecution(ExecutionId.generate(), plan.projectId(), plan,
                ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW, Fixtures.NOW.plusSeconds(1200),
                results, summary, null, null, null, "");

        assertThat(capacity.recordFrom(execution, analyzer.deriveStages(plan, results))).isEmpty();
        assertThat(recorded).isEmpty();
    }

    private static CapacityObservationRepository recordingRepository(
            List<CapacityObservation> sink) {

        return new CapacityObservationRepository() {

            @Override
            public CapacityObservation save(CapacityObservation observation) {
                sink.add(observation);
                return observation;
            }

            @Override
            public List<CapacityObservation> findByProject(ProjectId projectId) {
                return List.copyOf(sink);
            }

            @Override
            public Optional<CapacityObservation> findLatest(ProjectId projectId) {
                return sink.isEmpty() ? Optional.empty() : Optional.of(sink.getLast());
            }

            @Override
            public List<CapacityObservation> findByProjectAndVersion(ProjectId projectId,
                    String serviceVersion) {
                return sink.stream()
                        .filter(observation -> observation.serviceVersion().equals(serviceVersion))
                        .toList();
            }
        };
    }
}
