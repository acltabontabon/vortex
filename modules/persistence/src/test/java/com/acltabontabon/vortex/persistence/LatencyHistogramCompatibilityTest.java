package com.acltabontabon.vortex.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.acltabontabon.vortex.core.analysis.BreakpointDetector;
import com.acltabontabon.vortex.core.analysis.PercentileBasis;
import com.acltabontabon.vortex.core.analysis.RateAggregationBasis;
import com.acltabontabon.vortex.core.analysis.StageObservation;
import com.acltabontabon.vortex.core.analysis.SystemSaturationDetector;
import com.acltabontabon.vortex.core.application.DeterministicAnalyzer;
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
 * Executions recorded before pooled latency histograms and preserved request/failure counts existed
 * must still be readable, and must not acquire precision they were never given.
 *
 * <p>Same shape as {@link PhaseFourCompatibilityTest}: a current document is serialized and then has
 * the fields this fix added removed, which is exactly what a pre-fix row looks like. The two new
 * evidence bases — {@link PercentileBasis} and {@link RateAggregationBasis} — are tracked
 * independently, so a row missing only its histogram must not also lose its preserved counts, and vice
 * versa.
 */
class LatencyHistogramCompatibilityTest {

    private final DeterministicAnalyzer analyzer = new DeterministicAnalyzer(
            new ThresholdEvaluator(), new BreakpointDetector(), new SystemSaturationDetector());

    private static SamplePoint currentSamplePoint() {
        LatencyHistogram histogram = LatencyHistogram.builder()
                .record(10_000_000L).record(20_000_000L).record(30_000_000L).build();
        return new SamplePoint(Fixtures.NOW, Duration.ofSeconds(5), RequestsPerSecond.of(600),
                ErrorRate.of(1, 600), histogram.percentile(0.95).orElse(null),
                RequestsPerSecond.of(600), null, null, histogram, 600L, 1L);
    }

    private static ObjectNode withoutFields(Object document, String... fields) {
        ObjectNode node = JsonDocuments.mapper().valueToTree(document);
        for (String field : fields) {
            node.remove(field);
        }
        return node;
    }

    private static List<StageObservation> deriveStagesFrom(SamplePoint point,
            DeterministicAnalyzer analyzer) {
        EffectiveTestPlan plan = Fixtures.plan();
        MeasuredResults results = new MeasuredResults(
                new TimeWindow(Fixtures.NOW, Fixtures.NOW.plus(Duration.ofMinutes(10))),
                plan.peakLevel(), RequestsPerSecond.of(600), 600, 1, LatencyPercentiles.empty(),
                Map.of(), new MetricSeries(Duration.ofSeconds(5), List.of(point)), List.of());
        return analyzer.deriveStages(plan, results);
    }

    @Test
    @DisplayName("a bucket written before this fix reads back with no histogram or preserved counts, not fabricated ones")
    void oldBucketsHaveNoHistogramOrCounts() throws Exception {
        ObjectNode before = withoutFields(currentSamplePoint(),
                "latencyHistogram", "requestCount", "failureCount");

        SamplePoint restored = JsonDocuments.mapper().treeToValue(before, SamplePoint.class);

        assertThat(restored.latencyHistogramIfPresent()).isEmpty();
        assertThat(restored.requestCountIfPresent()).isEmpty();
        assertThat(restored.failureCountIfPresent()).isEmpty();
        // p95/errorRate/requestRate — the pre-existing fields — are unaffected by the widening.
        assertThat(restored.p95IfPresent()).isPresent();
    }

    @Test
    @DisplayName("a stage built entirely from pre-fix buckets falls back to both legacy bases")
    void stageFromOldBucketsUsesBothLegacyBases() throws Exception {
        ObjectNode before = withoutFields(currentSamplePoint(),
                "latencyHistogram", "requestCount", "failureCount");
        SamplePoint restored = JsonDocuments.mapper().treeToValue(before, SamplePoint.class);

        List<StageObservation> stages = deriveStagesFrom(restored, analyzer);

        assertThat(stages).hasSize(1);
        assertThat(stages.get(0).percentileBasis())
                .isEqualTo(PercentileBasis.LEGACY_AVERAGED_BUCKET_PERCENTILES);
        assertThat(stages.get(0).rateBasis()).isEqualTo(RateAggregationBasis.LEGACY_DERIVED_BUCKET_VALUES);
    }

    @Test
    @DisplayName("the two evidence bases are tracked independently: a bucket missing only its histogram keeps its preserved counts")
    void percentileAndRateBasesAreIndependent() throws Exception {
        ObjectNode before = withoutFields(currentSamplePoint(), "latencyHistogram");
        SamplePoint restored = JsonDocuments.mapper().treeToValue(before, SamplePoint.class);

        assertThat(restored.latencyHistogramIfPresent()).isEmpty();
        assertThat(restored.requestCountIfPresent()).hasValue(600L);
        assertThat(restored.failureCountIfPresent()).hasValue(1L);

        List<StageObservation> stages = deriveStagesFrom(restored, analyzer);

        assertThat(stages.get(0).percentileBasis())
                .isEqualTo(PercentileBasis.LEGACY_AVERAGED_BUCKET_PERCENTILES);
        assertThat(stages.get(0).rateBasis()).isEqualTo(RateAggregationBasis.PRESERVED_COUNTS);
    }

    @Test
    @DisplayName("a current bucket round-trips with its histogram and counts intact, under the new bases")
    void currentBucketsRoundTrip() throws Exception {
        SamplePoint original = currentSamplePoint();

        SamplePoint restored = JsonDocuments.mapper().treeToValue(
                JsonDocuments.mapper().valueToTree(original), SamplePoint.class);

        assertThat(restored.latencyHistogramIfPresent()).contains(original.latencyHistogram());
        assertThat(restored.requestCountIfPresent()).hasValue(600L);
        assertThat(restored.failureCountIfPresent()).hasValue(1L);

        List<StageObservation> stages = deriveStagesFrom(restored, analyzer);

        assertThat(stages.get(0).percentileBasis()).isEqualTo(PercentileBasis.MERGED_HISTOGRAM);
        assertThat(stages.get(0).rateBasis()).isEqualTo(RateAggregationBasis.PRESERVED_COUNTS);
    }

    @Test
    @DisplayName("a persisted histogram with an unrecognized scheme version fails clearly, rather than being silently reinterpreted")
    void unknownSchemeVersionFailsClearly() {
        ObjectNode point = JsonDocuments.mapper().valueToTree(currentSamplePoint());
        ObjectNode histogram = (ObjectNode) point.get("latencyHistogram");
        histogram.put("schemeVersion", 99);

        assertThatThrownBy(() -> JsonDocuments.mapper().treeToValue(point, SamplePoint.class))
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("no histogram field at all means legacy evidence, never an implicit scheme v1")
    void absentHistogramIsNotImplicitlyVersioned() throws Exception {
        ObjectNode before = withoutFields(currentSamplePoint(), "latencyHistogram");

        SamplePoint restored = JsonDocuments.mapper().treeToValue(before, SamplePoint.class);

        // Absence, not a versioned-but-empty histogram — the two must not be conflated.
        assertThat(restored.latencyHistogramIfPresent()).isEmpty();
    }
}
