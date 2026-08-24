package com.acltabontabon.vortex.core.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.calibration.CalibrationPolicy;
import com.acltabontabon.vortex.core.capacity.ProductionObservation;
import com.acltabontabon.vortex.core.environment.EnvironmentType;
import com.acltabontabon.vortex.core.safety.SafetyLimits;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.workload.Observation;
import com.acltabontabon.vortex.core.workload.OperationMix;
import com.acltabontabon.vortex.core.workload.TestType;
import com.acltabontabon.vortex.core.workload.WorkloadModel;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WorkloadRecommenderTest {

    private static final Instant FROM = Instant.parse("2026-07-22T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-21T00:00:00Z");
    private static final OperationMix MIX = OperationMix.single(OperationId.of("getOrder"));

    private final CalibrationPolicy calibrationPolicy = new CalibrationPolicy();
    private final WorkloadRecommender recommender = new WorkloadRecommender(calibrationPolicy);
    private final SafetyLimits limits = SafetyLimits.defaults();

    private static ProductionObservation observation() {
        return new ProductionObservation(RequestsPerSecond.of(80), RequestsPerSecond.of(100),
                RequestsPerSecond.of(120), MIX, "Grafana · checkout-service",
                Observation.over(FROM, TO), "");
    }

    @Test
    void smokeIsAlwaysASmallSteadyManualWorkloadRegardlessOfObservation() {
        WorkloadRecommendation rec = recommender.recommend(TestType.SMOKE, WorkloadModel.OPEN,
                observation(), limits, EnvironmentType.LOCAL_ISOLATED);
        assertThat(rec.shapeKind()).isEqualTo(ShapeKind.STEADY);
        assertThat(rec.isProductionInformed()).isFalse();
        assertThat(rec.shape().totalDuration().toSeconds()).isLessThanOrEqualTo(60);
    }

    @Test
    void averageLoadMatchesCalibrationPolicyVerbatimWhenObservationExists() {
        ProductionObservation observation = observation();
        var suggestion = calibrationPolicy.propose(observation).stream()
                .filter(s -> s.name().equals("average-load")).findFirst().orElseThrow();

        WorkloadRecommendation rec = recommender.recommend(TestType.AVERAGE_LOAD, WorkloadModel.OPEN,
                observation, limits, EnvironmentType.LOCAL_ISOLATED);

        assertThat(rec.shape().startLevel().asDouble()).isEqualTo(suggestion.rate().asDouble());
        assertThat(rec.shape().totalDuration()).isEqualTo(suggestion.duration());
        assertThat(rec.source().derivationIfPresent()).isEqualTo(suggestion.source().derivationIfPresent());
        assertThat(rec.isProductionInformed()).isTrue();
    }

    @Test
    void averageLoadFallsBackToAManualStartingPointWithoutAnObservation() {
        WorkloadRecommendation rec = recommender.recommend(TestType.AVERAGE_LOAD, WorkloadModel.OPEN,
                null, limits, EnvironmentType.LOCAL_ISOLATED);
        assertThat(rec.isProductionInformed()).isFalse();
        assertThat(rec.shapeKind()).isEqualTo(ShapeKind.STEADY);
    }

    @Test
    void stressRampsTowardTheForecastFigureAndStaysVisible() {
        WorkloadRecommendation rec = recommender.recommend(TestType.STRESS, WorkloadModel.OPEN,
                observation(), limits, EnvironmentType.LOCAL_ISOLATED);
        assertThat(rec.shapeKind()).isEqualTo(ShapeKind.PROGRESSIVE_RAMP);
        assertThat(rec.shape().isRamping()).isTrue();
        assertThat(rec.shape().stages()).hasSize(3);
        assertThat(rec.shape().peakLevel().asDouble()).isEqualTo(180); // 1.5x observed peak of 120
    }

    @Test
    void spikeJumpsFromBaselineToObservedPeakAndBackWhenObservationExists() {
        WorkloadRecommendation rec = recommender.recommend(TestType.SPIKE, WorkloadModel.OPEN,
                observation(), limits, EnvironmentType.LOCAL_ISOLATED);
        assertThat(rec.shapeKind()).isEqualTo(ShapeKind.SPIKE);
        assertThat(rec.shape().stages()).hasSize(4);
        var levels = rec.shape().stages().stream().map(s -> s.target().asDouble()).toList();
        assertThat(levels.get(1)).isEqualTo(120); // observed peak
        assertThat(levels.get(1)).isEqualTo(levels.get(2));
        assertThat(levels.get(0)).isEqualTo(levels.get(3));
        assertThat(levels.get(1)).isGreaterThan(levels.get(0));
        assertThat(rec.isProductionInformed()).isTrue();
    }

    @Test
    void spikeFallsBackToAMultipleOfAManualBaselineWithoutAnObservation() {
        WorkloadRecommendation rec = recommender.recommend(TestType.SPIKE, WorkloadModel.OPEN,
                null, limits, EnvironmentType.LOCAL_ISOLATED);
        assertThat(rec.shapeKind()).isEqualTo(ShapeKind.SPIKE);
        assertThat(rec.isProductionInformed()).isFalse();
        assertThat(rec.shape().peakLevel().asDouble()).isGreaterThan(rec.shape().startLevel().asDouble());
    }

    @Test
    void spikeOnAConcurrencyWorkloadIsAlwaysManualSinceProductionReportsThroughputNotUsers() {
        WorkloadRecommendation rec = recommender.recommend(TestType.SPIKE, WorkloadModel.CLOSED,
                observation(), limits, EnvironmentType.LOCAL_ISOLATED);
        assertThat(rec.model()).isEqualTo(WorkloadModel.CLOSED);
        assertThat(rec.isProductionInformed()).isFalse();
    }

    @Test
    void soakEmphasizesDurationOverStages() {
        WorkloadRecommendation rec = recommender.recommend(TestType.SOAK, WorkloadModel.OPEN,
                observation(), limits, EnvironmentType.LOCAL_ISOLATED);
        assertThat(rec.shapeKind()).isEqualTo(ShapeKind.STEADY);
        assertThat(rec.shape().totalDuration().toMinutes()).isEqualTo(60);
    }

    @Test
    void breakpointNeverExceedsTheEnvironmentsSafetyCeilingAndFlagsWhenCapped() {
        // SHARED_TEST's default ceiling (100 req/s) is well below 3x the observed peak (360), so the
        // proposed breakpoint ramp must be visibly capped.
        WorkloadRecommendation rec = recommender.recommend(TestType.BREAKPOINT, WorkloadModel.OPEN,
                observation(), limits, EnvironmentType.SHARED_TEST);
        assertThat(rec.shapeKind()).isEqualTo(ShapeKind.STAGED);
        assertThat(rec.safetyCeilingApplied()).isTrue();
        assertThat(rec.shape().peakLevel().asDouble())
                .isLessThanOrEqualTo(limits.ceilingFor(EnvironmentType.SHARED_TEST).asDouble());
    }

    @Test
    void breakpointDoesNotFlagCappingWhenTheCeilingIsNotReached() {
        // PERFORMANCE's default ceiling (5000 req/s) comfortably exceeds 3x the observed peak (360).
        WorkloadRecommendation rec = recommender.recommend(TestType.BREAKPOINT, WorkloadModel.OPEN,
                observation(), limits, EnvironmentType.PERFORMANCE);
        assertThat(rec.safetyCeilingApplied()).isFalse();
        assertThat(rec.shape().peakLevel().asDouble()).isEqualTo(360);
    }

    @Test
    void breakpointWithoutAnObservationRampsTowardTheEnvironmentCeiling() {
        WorkloadRecommendation rec = recommender.recommend(TestType.BREAKPOINT, WorkloadModel.OPEN,
                null, limits, EnvironmentType.PERFORMANCE);
        assertThat(rec.safetyCeilingApplied()).isTrue();
        assertThat(rec.shape().peakLevel().asDouble())
                .isEqualTo(limits.ceilingFor(EnvironmentType.PERFORMANCE).asDouble());
    }

    @Test
    void breakpointOnAConcurrencyWorkloadRampsTowardTheConcurrencyCeiling() {
        WorkloadRecommendation rec = recommender.recommend(TestType.BREAKPOINT, WorkloadModel.CLOSED,
                null, limits, EnvironmentType.LOCAL_ISOLATED);
        assertThat(rec.model()).isEqualTo(WorkloadModel.CLOSED);
        assertThat(rec.shape().peakLevel().asDouble())
                .isEqualTo(limits.concurrencyCeilingFor(EnvironmentType.LOCAL_ISOLATED).asDouble());
    }
}
