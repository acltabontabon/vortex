package dev.vortex.core.evidence;

import dev.vortex.core.analysis.StageObservation;
import dev.vortex.core.metrics.MetricSeries;
import dev.vortex.core.metrics.SamplePoint;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The run over time: load, latency and errors on one axis, with the workload's own stage boundaries.
 *
 * <p>Carries {@link SeriesPlot}s rather than pixels, so the SVG on the screen and the chart in the
 * PDF project identical numbers. It also carries {@code tableRows}, because ADR-021 treats the table
 * of numbers as the other half of every chart rather than a fallback — and that only holds if both
 * halves list the same rows in both renderers.
 *
 * <p>This is not an observability dashboard and is not trying to become one. Three signals, the
 * stage bands, and the numbers underneath.
 *
 * @param bands          the levels the workload held, as intervals of the run, so a chart can shade them
 * @param peak           the bucket with the worst latency; null when latency was never measured
 * @param breakpointAt   when the first non-compliant stage began, by real sample-bucket time — the
 *                       one instant a renderer may mark across every plot as "the run stopped
 *                       complying here"; null when every stage complied, or there were too few
 *                       stages to judge
 * @param levelChangeAt  when the workload first moved off its opening stage, by real sample-bucket
 *                       time; null with fewer than two stages. Generic to any multi-stage run — it
 *                       carries no opinion about test kind — but it is exactly the instant a Spike
 *                       test's own question calls "the jump"
 */
public record TimelineEvidence(
        MetricSeries series,
        List<StageObservation> stages,
        List<StageBand> bands,
        SeriesPlot latencyPlot,
        SeriesPlot throughputPlot,
        SeriesPlot errorRatePlot,
        List<SamplePoint> tableRows,
        SamplePoint peak,
        Instant breakpointAt,
        Instant levelChangeAt) {

    /**
     * How many rows the accompanying table shows before it is thinned.
     *
     * <p>Enough to see the shape of the run, few enough that the numbers do not become most of a
     * printed report. The series itself is kept in full on disk for anyone who needs every bucket.
     */
    public static final int MAX_TABLE_ROWS = 24;

    public TimelineEvidence {
        stages = stages == null ? List.of() : List.copyOf(stages);
        bands = bands == null ? List.of() : List.copyOf(bands);
        tableRows = tableRows == null ? List.of() : List.copyOf(tableRows);
    }

    public static TimelineEvidence empty() {
        return new TimelineEvidence(MetricSeries.empty(), List.of(), List.of(),
                SeriesPlot.empty("p95 Latency", "ms"),
                SeriesPlot.empty("Throughput", "requests/sec"),
                SeriesPlot.empty("Errors", "%"),
                List.of(), null, null, null);
    }

    /**
     * When the run first stopped complying, as the start of the first stage with a violated
     * threshold — by real bucket time, not a fraction of the axis, so it lands on the exact same
     * point a renderer would reach by walking the series itself.
     *
     * <p>Mirrors the same sample-count walk {@code RunEvidenceService.bands()} uses to place stage
     * boundaries, so this instant and that shading always agree about where one stage ends and the
     * next begins.
     */
    public static Instant breakpointInstant(List<StageObservation> stages, List<SamplePoint> points) {
        if (stages == null || stages.size() < 2 || points == null || points.isEmpty()) {
            return null;
        }
        int consumed = 0;
        for (StageObservation stage : stages) {
            if (!stage.violatedThresholds().isEmpty()) {
                return points.get(Math.min(consumed, points.size() - 1)).at();
            }
            consumed += stage.sampleCount();
        }
        return null;
    }

    /**
     * When the workload first moved off its opening stage — the start of the second stage, by real
     * bucket time. Unlike {@link #breakpointInstant}, this needs no compliance judgment: a run either
     * has a second stage or it doesn't, so there is no threshold to get wrong. A Spike test's own
     * question makes this instant the jump; nothing here decides that meaning.
     */
    public static Instant levelChangeInstant(List<StageObservation> stages, List<SamplePoint> points) {
        if (stages == null || stages.size() < 2 || points == null || points.isEmpty()) {
            return null;
        }
        int firstStageSamples = stages.get(0).sampleCount();
        return points.get(Math.min(firstStageSamples, points.size() - 1)).at();
    }

    /**
     * One held level, as a fraction of the run, so a renderer can shade the interval it covers.
     *
     * @param startFraction where the band begins, 0 to 1 across the run
     * @param endFraction   where it ends
     * @param compliant     whether every objective still held at this level
     */
    public record StageBand(
            String label,
            double startFraction,
            double endFraction,
            boolean compliant) {
    }

    public boolean isRenderable() {
        return latencyPlot.renderable() || throughputPlot.renderable() || errorRatePlot.renderable();
    }

    public Optional<SamplePoint> peakIfPresent() {
        return Optional.ofNullable(peak);
    }

    public Optional<Instant> breakpointAtIfPresent() {
        return Optional.ofNullable(breakpointAt);
    }

    public Optional<Instant> levelChangeAtIfPresent() {
        return Optional.ofNullable(levelChangeAt);
    }

    public boolean hasStageBands() {
        return bands.size() > 1;
    }

    /** Every plot, in the order they are stacked against the shared time axis. */
    public List<SeriesPlot> plots() {
        return List.of(throughputPlot, latencyPlot, errorRatePlot);
    }
}
