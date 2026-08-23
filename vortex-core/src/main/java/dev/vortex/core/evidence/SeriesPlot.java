package dev.vortex.core.evidence;

import dev.vortex.core.metrics.MetricSeries;
import dev.vortex.core.metrics.SamplePoint;
import dev.vortex.core.shared.RequestsPerSecond;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.ToDoubleFunction;

/**
 * A time series reduced to normalised geometry, with no notion of pixels, colours or a surface.
 *
 * <p>This exists because the same chart is now drawn twice — as inline SVG in the browser and with
 * drawing primitives in the PDF. Two implementations of "where does this point go" would eventually
 * disagree, and in a product whose charts are presented as evidence, a chart that disagrees with the
 * chart beside it is worse than no chart.
 *
 * <p>The arithmetic lives here rather than in either renderer because it is a calculation over
 * measurements, which is what {@code vortex-core} is for. Rendering stays outside.
 *
 * <h2>Coordinates</h2>
 *
 * <p>{@link PlotPoint#x()} runs 0 to 1 across the run. {@link PlotPoint#y()} is the value as a
 * fraction of {@link #scaleMaximum()}, so 0 is zero and 1 is the top of the axis.
 *
 * <p>Deliberately <em>not</em> pre-inverted for a screen. SVG's y axis grows downwards and PDF's
 * grows upwards, so a value flipped to suit one is wrong for the other. Each renderer inverts for
 * its own coordinate system; the model stays neutral.
 *
 * <h2>Gaps</h2>
 *
 * <p>A bucket with no measurement ends the current {@link Segment} and starts a new one. Drawing a
 * line across it would invent a measurement, which is why the gap is structural here rather than a
 * convention each renderer has to remember (ADR-021).
 *
 * @param label         what the series shows, for an accessible description
 * @param unitSymbol    the unit of the values, e.g. {@code ms}
 * @param scaleMaximum  the top of the axis, rounded to a number a person would choose
 * @param segments      contiguous runs of measured points
 * @param reference     a second series drawn for comparison, such as the offered rate; may be empty
 * @param referenceLevel a horizontal line as a fraction of {@code scaleMaximum}, such as a latency
 *                      objective; null when there is none, or when it falls above the axis
 * @param span          how long the run lasted
 * @param renderable    whether there is enough here to be worth drawing
 */
public record SeriesPlot(
        String label,
        String unitSymbol,
        double scaleMaximum,
        List<Segment> segments,
        List<Segment> reference,
        Double referenceLevel,
        Duration span,
        boolean renderable) {

    /** How many points a series needs before a line means anything. */
    private static final int MINIMUM_POINTS = 2;

    /** Headroom above the peak, so the highest point is not welded to the top of the axis. */
    private static final double HEADROOM = 1.12;

    public SeriesPlot {
        label = label == null ? "" : label;
        unitSymbol = unitSymbol == null ? "" : unitSymbol;
        segments = segments == null ? List.of() : List.copyOf(segments);
        reference = reference == null ? List.of() : List.copyOf(reference);
        span = span == null ? Duration.ZERO : span;
    }

    /**
     * One measured point.
     *
     * @param x     position across the run, 0 to 1
     * @param y     value as a fraction of the axis maximum, 0 to 1
     * @param at    when the bucket started
     * @param value the measurement itself, kept so a renderer can label a point without rescaling
     */
    public record PlotPoint(double x, double y, Instant at, double value) {
    }

    /** A run of consecutive measured points, unbroken by a gap. */
    public record Segment(List<PlotPoint> points) {
        public Segment {
            points = points == null ? List.of() : List.copyOf(points);
        }

        public boolean isEmpty() {
            return points.isEmpty();
        }
    }

    public static SeriesPlot empty(String label, String unitSymbol) {
        return new SeriesPlot(label, unitSymbol, 0, List.of(), List.of(), null, Duration.ZERO, false);
    }

    /** Latency over time, with the objective drawn as a reference line. */
    public static SeriesPlot latency(MetricSeries series, Duration threshold, String label) {
        Double line = threshold == null ? null : (double) threshold.toMillis();
        return build(series, label, "ms",
                point -> point.p95IfPresent().map(p95 -> p95.toNanos() / 1_000_000d)
                        .orElse(Double.NaN),
                null, line);
    }

    /**
     * Achieved throughput over time, with the offered level as a reference series.
     *
     * <p>The reference is drawn only for an arrival-rate workload. Under a concurrency workload the
     * target is a virtual-user count, and plotting it on a requests-per-second axis would put two
     * different quantities on one scale — exactly the comparison this product refuses to make
     * everywhere else.
     */
    public static SeriesPlot throughput(MetricSeries series, String label) {
        return build(series, label, "requests/sec",
                point -> point.requestRateIfPresent().map(RequestsPerSecond::asDouble)
                        .orElse(Double.NaN),
                point -> point.targetLoadIfPresent()
                        .filter(level -> level instanceof RequestsPerSecond)
                        .map(dev.vortex.core.shared.LoadLevel::asDouble)
                        .orElse(Double.NaN),
                null);
    }

    /** Error rate over time, as a percentage. */
    public static SeriesPlot errorRate(MetricSeries series, String label) {
        return build(series, label, "%", point -> point.errorRate().asPercent(), null, null);
    }

    private static SeriesPlot build(MetricSeries series, String label, String unitSymbol,
            ToDoubleFunction<SamplePoint> value, ToDoubleFunction<SamplePoint> referenceSeries,
            Double referenceValue) {

        List<SamplePoint> points = series == null ? List.of() : series.points();
        if (points.size() < MINIMUM_POINTS) {
            return empty(label, unitSymbol);
        }

        double maximum = 0;
        for (SamplePoint point : points) {
            maximum = Math.max(maximum, finiteOrZero(value.applyAsDouble(point)));
            if (referenceSeries != null) {
                maximum = Math.max(maximum, finiteOrZero(referenceSeries.applyAsDouble(point)));
            }
        }
        if (referenceValue != null) {
            maximum = Math.max(maximum, referenceValue);
        }
        if (maximum <= 0) {
            return empty(label, unitSymbol);
        }

        double scale = niceCeiling(maximum * HEADROOM);

        // A reference line above the axis would be drawn off the chart, so it is reported as absent
        // rather than silently clamped onto the top gridline where it would read as a real level.
        Double level = referenceValue != null && referenceValue <= scale
                ? referenceValue / scale
                : null;

        return new SeriesPlot(label, unitSymbol, scale,
                segment(points, value, scale),
                referenceSeries == null ? List.of() : segment(points, referenceSeries, scale),
                level, series.span(), true);
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0;
    }

    /** Splits a series into runs of measured points, so a gap stays a gap. */
    private static List<Segment> segment(List<SamplePoint> points,
            ToDoubleFunction<SamplePoint> value, double scale) {

        List<Segment> segments = new ArrayList<>();
        List<PlotPoint> current = new ArrayList<>();
        int last = points.size() - 1;

        for (int i = 0; i <= last; i++) {
            SamplePoint point = points.get(i);
            double measurement = value.applyAsDouble(point);

            if (!Double.isFinite(measurement)) {
                if (current.size() >= MINIMUM_POINTS) {
                    segments.add(new Segment(current));
                }
                current = new ArrayList<>();
                continue;
            }

            current.add(new PlotPoint(
                    i / (double) last,
                    Math.min(measurement, scale) / scale,
                    point.at(),
                    measurement));
        }

        if (current.size() >= MINIMUM_POINTS) {
            segments.add(new Segment(current));
        }
        return List.copyOf(segments);
    }

    /** Whether there is any measured line to draw, as opposed to only an axis. */
    public boolean hasData() {
        return renderable && !segments.isEmpty();
    }

    public Optional<Double> referenceLevelIfPresent() {
        return Optional.ofNullable(referenceLevel);
    }

    public boolean hasReferenceSeries() {
        return !reference.isEmpty();
    }

    /**
     * Axis labels from zero to the maximum, bottom first.
     *
     * @param divisions how many intervals the axis is split into; {@code 4} yields five labels
     */
    public List<String> axisLabels(int divisions) {
        if (divisions < 1) {
            throw new IllegalArgumentException("an axis needs at least one division");
        }
        List<String> labels = new ArrayList<>(divisions + 1);
        for (int i = 0; i <= divisions; i++) {
            labels.add(axisLabel(scaleMaximum * (i / (double) divisions)));
        }
        return List.copyOf(labels);
    }

    /** The elapsed-time label for the right-hand end of the axis, e.g. {@code 10:00}. */
    public String spanLabel() {
        return elapsedLabel(span);
    }

    /** Rounds an axis maximum up to something a person would choose. */
    public static double niceCeiling(double value) {
        if (!Double.isFinite(value) || value <= 0) {
            return 0;
        }
        double magnitude = Math.pow(10, Math.floor(Math.log10(value)));
        double normalised = value / magnitude;
        double step;
        if (normalised <= 1) {
            step = 1;
        } else if (normalised <= 2) {
            step = 2;
        } else if (normalised <= 5) {
            step = 5;
        } else {
            step = 10;
        }
        return step * magnitude;
    }

    public static String axisLabel(double value) {
        if (value >= 1000) {
            return String.format(Locale.ROOT, "%.0fk", value / 1000);
        }
        if (value >= 10 || value == Math.rint(value)) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    public static String elapsedLabel(Duration span) {
        Duration elapsed = span == null ? Duration.ZERO : span;
        return elapsed.toMinutes() + ":"
                + String.format(Locale.ROOT, "%02d", elapsed.toSecondsPart());
    }

    /**
     * A trimmed set of points for the data table that accompanies every chart.
     *
     * <p>Both renderers call this, so the table under the SVG and the table in the PDF list the same
     * rows. ADR-021 treats the table as the other half of the chart rather than a fallback, which
     * only holds if the two halves agree.
     */
    public static List<SamplePoint> sample(MetricSeries series, int maxRows) {
        if (series == null || series.isEmpty() || maxRows < 1) {
            return List.of();
        }
        List<SamplePoint> points = series.points();
        if (points.size() <= maxRows) {
            return points;
        }
        int stride = (int) Math.ceil(points.size() / (double) maxRows);
        List<SamplePoint> sampled = new ArrayList<>();
        for (int i = 0; i < points.size(); i += stride) {
            sampled.add(points.get(i));
        }
        return List.copyOf(sampled);
    }

    /** The bucket with the highest observed p95, when latency was measured at all. */
    public static Optional<SamplePoint> peak(MetricSeries series) {
        if (series == null || series.isEmpty()) {
            return Optional.empty();
        }
        return series.points().stream()
                .filter(point -> point.p95IfPresent().isPresent())
                .max(Comparator.comparing(SamplePoint::p95));
    }

    /** Whether a series has enough points to be worth drawing. */
    public static boolean isRenderable(MetricSeries series) {
        return series != null && series.size() >= MINIMUM_POINTS;
    }
}
