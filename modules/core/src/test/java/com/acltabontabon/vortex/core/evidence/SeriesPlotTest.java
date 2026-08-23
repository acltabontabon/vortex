package com.acltabontabon.vortex.core.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acltabontabon.vortex.core.metrics.MetricSeries;
import com.acltabontabon.vortex.core.metrics.SamplePoint;
import com.acltabontabon.vortex.core.shared.Concurrency;
import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.LoadLevel;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Chart geometry is calculated once and drawn twice — as SVG in the browser and with drawing
 * primitives in the PDF.
 *
 * <p>These tests exist because that is precisely the arrangement in which two renderers quietly
 * stop agreeing. They assert the arithmetic and the shape of the result, never anything about a
 * surface: no pixels, no coordinates in any renderer's space, no markup.
 */
class SeriesPlotTest {

    private static final Instant START = Instant.parse("2026-08-21T10:00:00Z");
    private static final Duration BUCKET = Duration.ofSeconds(5);

    @Nested
    @DisplayName("gaps")
    class Gaps {

        @Test
        @DisplayName("a period with no measurement is a break between segments, never a bridge")
        void gapsAreNotBridged() {
            // Buckets 2 and 3 measured nothing at all.
            MetricSeries series = series(120L, 130L, null, null, 140L, 150L);

            SeriesPlot plot = SeriesPlot.latency(series, null, "Latency");

            assertThat(plot.segments()).hasSize(2);
            assertThat(plot.segments().get(0).points()).hasSize(2);
            assertThat(plot.segments().get(1).points()).hasSize(2);
        }

        @Test
        @DisplayName("the gap keeps its place on the axis, so the break is where it happened")
        void gapPreservesPosition() {
            MetricSeries series = series(120L, 130L, null, null, 140L, 150L);

            SeriesPlot plot = SeriesPlot.latency(series, null, "Latency");

            // Six buckets, so x runs 0, 0.2, 0.4, 0.6, 0.8, 1.0. The second segment must resume at
            // 0.8 — not at 0.4, which is where it would land if gaps simply closed up.
            List<SeriesPlot.PlotPoint> resumed = plot.segments().get(1).points();
            assertThat(resumed.get(0).x()).isEqualTo(0.8);
            assertThat(resumed.get(1).x()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("a lone measured point between two gaps is not a line, so it is dropped")
        void isolatedPointIsNotASegment() {
            MetricSeries series = series(120L, 130L, null, 500L, null, 140L, 150L);

            SeriesPlot plot = SeriesPlot.latency(series, null, "Latency");

            assertThat(plot.segments()).hasSize(2);
            assertThat(plot.segments()).allSatisfy(
                    segment -> assertThat(segment.points()).hasSizeGreaterThanOrEqualTo(2));
        }
    }

    @Nested
    @DisplayName("the axis")
    class Axis {

        @Test
        @DisplayName("the maximum is rounded up to a number a person would choose")
        void niceCeilings() {
            assertThat(SeriesPlot.niceCeiling(0.4)).isEqualTo(0.5);
            assertThat(SeriesPlot.niceCeiling(7)).isEqualTo(10);
            assertThat(SeriesPlot.niceCeiling(140)).isEqualTo(200);
            assertThat(SeriesPlot.niceCeiling(1_400)).isEqualTo(2_000);
        }

        @Test
        @DisplayName("the peak is never welded to the top of the axis")
        void peakHasHeadroom() {
            MetricSeries series = series(100L, 200L);

            SeriesPlot plot = SeriesPlot.latency(series, null, "Latency");

            assertThat(plot.scaleMaximum()).isGreaterThan(200);
        }

        @Test
        @DisplayName("values are a fraction of the axis, not inverted for any particular surface")
        void valuesAreNeutralFractions() {
            MetricSeries series = series(0L, 500L);

            SeriesPlot plot = SeriesPlot.latency(series, null, "Latency");
            List<SeriesPlot.PlotPoint> points = plot.segments().get(0).points();

            // Zero sits at 0 and the larger value above it. A plot pre-inverted for SVG would have
            // these the other way round, and would then be wrong for the PDF, whose y axis grows
            // in the opposite direction.
            assertThat(points.get(0).y()).isZero();
            assertThat(points.get(1).y()).isGreaterThan(points.get(0).y());
            assertThat(points).allSatisfy(point -> assertThat(point.y()).isBetween(0.0, 1.0));
        }

        @Test
        void axisLabelsRunFromZeroToTheMaximum() {
            // 170 ms plus headroom is 190.4, which rounds up to 200 rather than to the next
            // power of ten. The step ladder is 1, 2, 5, 10 against the magnitude.
            SeriesPlot plot = SeriesPlot.latency(series(100L, 170L), null, "Latency");

            assertThat(plot.scaleMaximum()).isEqualTo(200);
            assertThat(plot.axisLabels(4)).containsExactly("0", "50", "100", "150", "200");
        }

        @Test
        void anAxisNeedsAtLeastOneDivision() {
            SeriesPlot plot = SeriesPlot.latency(series(100L, 180L), null, "Latency");

            assertThatThrownBy(() -> plot.axisLabels(0))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("reference lines")
    class References {

        @Test
        @DisplayName("an objective inside the axis is reported as a fraction of it")
        void thresholdWithinScale() {
            SeriesPlot plot = SeriesPlot.latency(series(100L, 170L), Duration.ofMillis(100), "Latency");

            assertThat(plot.scaleMaximum()).isEqualTo(200);
            assertThat(plot.referenceLevelIfPresent()).hasValue(0.5);
        }

        @Test
        @DisplayName("an objective is never clamped onto the top gridline, where it would read as a real level")
        void thresholdAboveScaleIsAbsentNotClamped() {
            // The axis is grown to include the threshold, so it can never exceed it. The guard
            // matters anyway: clamping would draw a line at a level nothing was measured against.
            SeriesPlot plot = SeriesPlot.latency(series(100L, 180L), Duration.ofMillis(5_000), "Latency");

            assertThat(plot.scaleMaximum()).isGreaterThanOrEqualTo(5_000);
            assertThat(plot.referenceLevelIfPresent()).isPresent();
        }

        @Test
        @DisplayName("an arrival-rate workload draws the offered rate beside the achieved rate")
        void openWorkloadHasAReferenceSeries() {
            SeriesPlot plot = SeriesPlot.throughput(
                    throughputSeries(RequestsPerSecond.of(100), 98, 97), "Throughput");

            assertThat(plot.hasReferenceSeries()).isTrue();
        }

        @Test
        @DisplayName("a concurrency workload draws no reference: VUs and requests/sec are not one scale")
        void closedWorkloadHasNoReferenceSeries() {
            SeriesPlot plot = SeriesPlot.throughput(
                    throughputSeries(Concurrency.of(50), 98, 97), "Throughput");

            assertThat(plot.hasReferenceSeries()).isFalse();
        }
    }

    @Nested
    @DisplayName("when there is nothing to draw")
    class NothingToDraw {

        @Test
        void aSinglePointIsNotALine() {
            assertThat(SeriesPlot.latency(series(120L), null, "Latency").renderable()).isFalse();
        }

        @Test
        void anEmptySeriesIsNotRenderable() {
            assertThat(SeriesPlot.latency(MetricSeries.empty(), null, "Latency").renderable())
                    .isFalse();
        }

        @Test
        void aNullSeriesIsNotRenderable() {
            assertThat(SeriesPlot.latency(null, null, "Latency").renderable()).isFalse();
        }

        @Test
        @DisplayName("a series measured entirely at zero has no axis to draw")
        void allZeroesAreNotRenderable() {
            assertThat(SeriesPlot.errorRate(series(0L, 0L, 0L), "Errors").renderable()).isFalse();
        }

        @Test
        @DisplayName("a series where every bucket is a gap is renderable as an axis but has no line")
        void allGapsHaveNoData() {
            MetricSeries series = throughputSeries(RequestsPerSecond.of(100), 90, 90);

            SeriesPlot plot = SeriesPlot.latency(series, null, "Latency");

            assertThat(plot.renderable()).isFalse();
            assertThat(plot.hasData()).isFalse();
        }
    }

    @Nested
    @DisplayName("the table that accompanies every chart")
    class Table {

        @Test
        @DisplayName("a short series is shown in full")
        void shortSeriesIsNotSampled() {
            MetricSeries series = series(1L, 2L, 3L);

            assertThat(SeriesPlot.sample(series, 10)).hasSize(3);
        }

        @Test
        @DisplayName("a long series is thinned rather than truncated, so the run's end is still shown")
        void longSeriesIsThinnedAcrossTheWholeRun() {
            List<Long> values = new ArrayList<>();
            for (long i = 0; i < 100; i++) {
                values.add(100 + i);
            }
            MetricSeries series = series(values.toArray(Long[]::new));

            List<SamplePoint> sampled = SeriesPlot.sample(series, 10);

            assertThat(sampled).hasSizeLessThanOrEqualTo(10);
            assertThat(sampled.get(0)).isEqualTo(series.points().get(0));
            // The last sampled bucket must come from the far end of the run, not the first tenth.
            assertThat(sampled.get(sampled.size() - 1).at())
                    .isAfterOrEqualTo(START.plusSeconds(5 * 89));
        }

        @Test
        void anEmptySeriesHasNoRows() {
            assertThat(SeriesPlot.sample(MetricSeries.empty(), 10)).isEmpty();
            assertThat(SeriesPlot.sample(null, 10)).isEmpty();
        }
    }

    @Test
    @DisplayName("the peak is the highest measured p95, ignoring buckets that measured none")
    void peakIgnoresGaps() {
        MetricSeries series = series(120L, null, 900L, 130L);

        assertThat(SeriesPlot.peak(series))
                .hasValueSatisfying(point ->
                        assertThat(point.p95()).isEqualTo(Duration.ofMillis(900)));
    }

    @Test
    void elapsedLabelsReadAsMinutesAndSeconds() {
        assertThat(SeriesPlot.elapsedLabel(Duration.ofSeconds(605))).isEqualTo("10:05");
        assertThat(SeriesPlot.elapsedLabel(Duration.ZERO)).isEqualTo("0:00");
        assertThat(SeriesPlot.elapsedLabel(null)).isEqualTo("0:00");
    }

    /** A latency series. A {@code null} entry is a bucket in which nothing was measured. */
    private static MetricSeries series(Long... p95Millis) {
        List<SamplePoint> points = new ArrayList<>();
        Instant cursor = START;
        for (Long millis : p95Millis) {
            points.add(new SamplePoint(cursor, BUCKET,
                    RequestsPerSecond.of(10), ErrorRate.ofFraction(0),
                    millis == null ? null : Duration.ofMillis(millis), null));
            cursor = cursor.plus(BUCKET);
        }
        return new MetricSeries(BUCKET, points);
    }

    /** A throughput series with no latency measured, carrying a target level of a given quantity. */
    private static MetricSeries throughputSeries(LoadLevel target, double... achieved) {
        List<SamplePoint> points = new ArrayList<>();
        Instant cursor = START;
        for (double rate : achieved) {
            points.add(new SamplePoint(cursor, BUCKET,
                    RequestsPerSecond.of(rate), ErrorRate.ofFraction(0), null, target));
            cursor = cursor.plus(BUCKET);
        }
        return new MetricSeries(BUCKET, points);
    }
}
