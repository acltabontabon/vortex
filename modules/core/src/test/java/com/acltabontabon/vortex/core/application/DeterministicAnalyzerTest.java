package com.acltabontabon.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.analysis.BreakpointDetector;
import com.acltabontabon.vortex.core.analysis.PercentileBasis;
import com.acltabontabon.vortex.core.analysis.RateAggregationBasis;
import com.acltabontabon.vortex.core.analysis.StageObservation;
import com.acltabontabon.vortex.core.analysis.SystemSaturationDetector;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.metrics.LatencyHistogram;
import com.acltabontabon.vortex.core.metrics.LatencyPercentiles;
import com.acltabontabon.vortex.core.metrics.MeasuredResults;
import com.acltabontabon.vortex.core.metrics.MetricSeries;
import com.acltabontabon.vortex.core.metrics.SamplePoint;
import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.threshold.ThresholdEvaluator;
import java.time.Duration;
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

    // ---- percentile: pooled histograms replace averaging bucket p95s -------------------------

    private static SamplePoint pooledPoint(java.time.Instant at, com.acltabontabon.vortex.core.shared.LoadLevel targetLoad,
            RequestsPerSecond rate, long requests, long failures, LatencyHistogram histogram) {
        return new SamplePoint(at, Duration.ofSeconds(5), rate, ErrorRate.of(failures, requests),
                histogram.percentile(0.95).orElse(null), targetLoad, null, null,
                histogram, requests, failures);
    }

    private static MeasuredResults resultsFrom(EffectiveTestPlan plan, List<SamplePoint> points,
            long requests, long failures) {
        return new MeasuredResults(
                new TimeWindow(Fixtures.NOW, Fixtures.NOW.plus(Duration.ofMinutes(10))),
                plan.peakLevel(), RequestsPerSecond.of(20), requests, failures,
                LatencyPercentiles.empty(), Map.of(), new MetricSeries(Duration.ofSeconds(5), points),
                List.of());
    }

    @Test
    @DisplayName("a stage's p95 is pooled by merging every bucket's own histogram, never by averaging their bucket p95s")
    void pooledStageP95MatchesIndependentlyMergedHistograms() {
        EffectiveTestPlan plan = Fixtures.plan();
        LatencyHistogram tight = LatencyHistogram.builder().record(10_000_000L).record(10_000_000L).build();
        LatencyHistogram tailSpike = LatencyHistogram.builder().record(500_000_000L).build();

        SamplePoint a = pooledPoint(Fixtures.NOW, plan.peakLevel(), RequestsPerSecond.of(20), 2, 0, tight);
        SamplePoint b = pooledPoint(Fixtures.NOW.plusSeconds(5), plan.peakLevel(), RequestsPerSecond.of(20), 1, 0, tailSpike);
        MeasuredResults results = resultsFrom(plan, List.of(a, b), 3, 0);

        List<StageObservation> stages = analyzer.deriveStages(plan, results);

        assertThat(stages).hasSize(1);
        java.time.Duration expectedPooled = tight.merge(tailSpike).percentile(0.95).orElseThrow();
        assertThat(stages.get(0).p95()).isEqualTo(expectedPooled);
        assertThat(stages.get(0).percentileBasis()).isEqualTo(PercentileBasis.MERGED_HISTOGRAM);

        // The old bug's answer — averaging each bucket's own p95 — is a different, disprovable
        // number: it is not what this stage's pooled evidence actually says.
        java.time.Duration oldBuggyAverage = java.time.Duration.ofNanos(
                (a.p95().toNanos() + b.p95().toNanos()) / 2);
        assertThat(stages.get(0).p95()).isNotEqualTo(oldBuggyAverage);
    }

    @Test
    @DisplayName("a legacy stage (no histograms/counts) reproduces the old averaged p95 and rate values exactly, under the legacy basis")
    void legacyStageReproducesOldAveragedValuesExactly() {
        EffectiveTestPlan plan = Fixtures.plan();
        SamplePoint a = new SamplePoint(Fixtures.NOW, Duration.ofSeconds(5), RequestsPerSecond.of(18),
                ErrorRate.ofFraction(0.10), Duration.ofMillis(100), plan.peakLevel());
        SamplePoint b = new SamplePoint(Fixtures.NOW.plusSeconds(5), Duration.ofSeconds(5),
                RequestsPerSecond.of(22), ErrorRate.ofFraction(0.20), Duration.ofMillis(300),
                plan.peakLevel());
        MeasuredResults results = resultsFrom(plan, List.of(a, b), 0, 0);

        List<StageObservation> stages = analyzer.deriveStages(plan, results);

        assertThat(stages).hasSize(1);
        StageObservation stage = stages.get(0);
        assertThat(stage.percentileBasis()).isEqualTo(PercentileBasis.LEGACY_AVERAGED_BUCKET_PERCENTILES);
        assertThat(stage.rateBasis()).isEqualTo(RateAggregationBasis.LEGACY_DERIVED_BUCKET_VALUES);
        assertThat(stage.p95()).isEqualTo(Duration.ofMillis(200)); // (100 + 300) / 2, the old formula
        assertThat(stage.achievedRate().asDouble()).isEqualTo(20.0); // (18 + 22) / 2, the old formula
        assertThat(stage.errorRate().asFraction()).isEqualTo(0.15, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("a stage mixing legacy and new-evidence points falls back to the legacy basis entirely for both metric families — no partial pooling")
    void mixedBasisFallsBackEntirelyForBothMetricFamilies() {
        EffectiveTestPlan plan = Fixtures.plan();
        LatencyHistogram histogram = LatencyHistogram.builder().record(10_000_000L).build();
        SamplePoint withEvidence = pooledPoint(Fixtures.NOW, plan.peakLevel(), RequestsPerSecond.of(20), 100, 1, histogram);
        SamplePoint legacy = new SamplePoint(Fixtures.NOW.plusSeconds(5), Duration.ofSeconds(5),
                RequestsPerSecond.of(20), ErrorRate.ofFraction(0.01), Duration.ofMillis(50),
                plan.peakLevel());
        MeasuredResults results = resultsFrom(plan, List.of(withEvidence, legacy), 0, 0);

        List<StageObservation> stages = analyzer.deriveStages(plan, results);

        assertThat(stages.get(0).percentileBasis())
                .isEqualTo(PercentileBasis.LEGACY_AVERAGED_BUCKET_PERCENTILES);
        assertThat(stages.get(0).rateBasis()).isEqualTo(RateAggregationBasis.LEGACY_DERIVED_BUCKET_VALUES);
    }

    // ---- error rate: a genuine correctness fix, not merely an evidence-quality improvement -----

    @Test
    @DisplayName("stage error rate is sum(failures)/sum(requests), and materially differs from the old unweighted average of bucket fractions")
    void weightedErrorRateDiffersFromUnweightedMean() {
        EffectiveTestPlan plan = Fixtures.plan();
        LatencyHistogram histogram = LatencyHistogram.builder().record(10_000_000L).build();
        // bucket A: 1000 requests, 1 failure (0.1%); bucket B: 10 requests, 5 failures (50%)
        SamplePoint a = pooledPoint(Fixtures.NOW, plan.peakLevel(), RequestsPerSecond.of(200), 1000, 1, histogram);
        SamplePoint b = pooledPoint(Fixtures.NOW.plusSeconds(5), plan.peakLevel(), RequestsPerSecond.of(2), 10, 5, histogram);
        MeasuredResults results = resultsFrom(plan, List.of(a, b), 1010, 6);

        List<StageObservation> stages = analyzer.deriveStages(plan, results);

        double weighted = stages.get(0).errorRate().asFraction();
        assertThat(weighted).isEqualTo(6.0 / 1010.0, org.assertj.core.data.Offset.offset(1e-4));

        double unweightedMean = (0.001 + 0.5) / 2; // the old, wrong formula: 0.2505
        assertThat(weighted).isNotCloseTo(unweightedMean, org.assertj.core.data.Offset.offset(0.01));
        assertThat(stages.get(0).rateBasis()).isEqualTo(RateAggregationBasis.PRESERVED_COUNTS);
    }

    // ---- request rate/count: evidence-preservation, equivalent under today's fixed bucket width --

    @Test
    @DisplayName("stage request total sums preserved counts exactly, and stage request rate equals the old arithmetic mean of bucket rates under today's fixed bucket width")
    void requestRateEquivalentToOldMeanUnderFixedBucketWidth() {
        EffectiveTestPlan plan = Fixtures.plan();
        LatencyHistogram histogram = LatencyHistogram.builder().record(10_000_000L).build();
        SamplePoint a = pooledPoint(Fixtures.NOW, plan.peakLevel(), RequestsPerSecond.of(100), 500, 0, histogram);
        SamplePoint b = pooledPoint(Fixtures.NOW.plusSeconds(5), plan.peakLevel(), RequestsPerSecond.of(40), 200, 0, histogram);
        MeasuredResults results = resultsFrom(plan, List.of(a, b), 700, 0);

        List<StageObservation> stages = analyzer.deriveStages(plan, results);

        // preserved-count path: exact total, not a rate*width reconstruction
        assertThat(stages.get(0).requests()).isEqualTo(700);

        // the old formula, computed independently here, for comparison only — never as an oracle for
        // percentile/error-rate correctness, but request rate under equal bucket widths is genuinely
        // expected to coincide with it (see DeterministicAnalyzer's stageRequestRate javadoc)
        double oldMean = (100.0 + 40.0) / 2;
        assertThat(stages.get(0).achievedRate().asDouble())
                .isEqualTo(oldMean, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(stages.get(0).rateBasis()).isEqualTo(RateAggregationBasis.PRESERVED_COUNTS);
    }
}
