package com.acltabontabon.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.analysis.BreakpointDetector;
import com.acltabontabon.vortex.core.analysis.StageObservation;
import com.acltabontabon.vortex.core.analysis.SystemSaturationDetector;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.metrics.MeasuredResults;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.threshold.ThresholdEvaluator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a run's time series becomes one {@link StageObservation} per stage.
 *
 * <p>k6's {@code ramping-arrival-rate} executor moves a stage's rate linearly from the previous
 * stage's target to this one's, over this stage's own duration — so a stage's first several seconds
 * are below its nominal target by design, not because anything actually fell behind. A stage that
 * tracks that ramp almost perfectly must report (near) zero shortfall; a flat average of the whole
 * window compared against the fully-ramped target would instead always show one, regardless of
 * whether the run kept up.
 */
class DeterministicAnalyzerTest {

    private final DeterministicAnalyzer analyzer = new DeterministicAnalyzer(
            new ThresholdEvaluator(), new BreakpointDetector(), new SystemSaturationDetector());

    private static MeasuredResults ramping(EffectiveTestPlan plan) {
        MeasuredResults shape = Fixtures.results(60, 0.0);
        return new MeasuredResults(shape.window(), plan.peakLevel(), RequestsPerSecond.of(120), 0, 0,
                shape.latency(), Map.of(), Fixtures.rampingSeries(plan.stages()), List.of());
    }

    @Test
    @DisplayName("a stage that tracks its own ramp reports (near) zero shortfall")
    void aStageTrackingItsRampHasNoMaterialShortfall() {
        EffectiveTestPlan plan = Fixtures.breakpointPlan();

        List<StageObservation> stages = analyzer.deriveStages(plan, ramping(plan));

        assertThat(stages).hasSize(4);
        assertThat(stages).allSatisfy(stage ->
                assertThat(stage.rateShortfall()).hasValueSatisfying(shortfall ->
                        assertThat(shortfall).as("stage at %s", stage.targetLoad().displayWithUnit())
                                .isLessThan(0.02)));
    }

    @Test
    @DisplayName("each stage after the first carries the level it ramped from")
    void rampStartLevelIsThePreviousStagesTarget() {
        EffectiveTestPlan plan = Fixtures.breakpointPlan();

        List<StageObservation> stages = analyzer.deriveStages(plan, ramping(plan));

        assertThat(stages.get(0).rampStartLevelIfPresent()).isEmpty();
        for (int i = 1; i < stages.size(); i++) {
            assertThat(stages.get(i).rampStartLevelIfPresent())
                    .hasValue(stages.get(i - 1).targetLoad());
        }
    }

    /**
     * The same correction, applied to the whole-run note instead of a single stage: a run that
     * tracked its ramp's own time-weighted average exactly must not be told it fell short of the
     * ramp's peak, which it was only ever going to touch for an instant.
     */
    @Test
    @DisplayName("a run that tracked its whole ramp is not told it fell short of the ramp's peak")
    void aRunTrackingItsWholeRampHasNoShortfallNote() {
        EffectiveTestPlan plan = Fixtures.breakpointPlan(); // 50 -> 100 -> 150 -> 200, 5 min each

        // Time-weighted average of that exact ramp: (50 + 75 + 125 + 175) / 4 = 106.25.
        MeasuredResults shape = Fixtures.results(60, 0.0);
        MeasuredResults results = new MeasuredResults(shape.window(), plan.peakLevel(),
                RequestsPerSecond.of(106.25), shape.requests(), 0, shape.latency(), Map.of(),
                shape.series(), List.of());

        var summary = analyzer.analyze(plan, results);

        assertThat(summary.notes()).noneMatch(note -> note.contains("below the offered")
                || note.contains("below the load its ramp asked for"));
    }
}
