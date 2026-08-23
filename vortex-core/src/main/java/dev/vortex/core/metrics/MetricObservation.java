package dev.vortex.core.metrics;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A single measured value together with everything needed to judge how much it is worth.
 *
 * <p>Vortex deliberately does not flatten measurements into anonymous numbers. Every observation
 * carries the metric name, who measured it, in what unit, how it was aggregated, over which window
 * and with which dimensions — so a later claim built on it can be traced back to its source, and so
 * the AI assistant can be held to referencing evidence that actually exists.
 *
 * <p>{@code provenance} and {@code trace} are both optional and both absent for a provider that
 * cannot supply them. That is the honest default rather than a gap to be filled in: the Actuator
 * endpoint reports a gauge's current value and nothing about how it got there, and inventing a
 * starting point for it would be exactly the kind of plausible number this type exists to prevent.
 *
 * @param id          stable reference used by findings, e.g. {@code metric:http.p95}
 * @param name        metric name in its source system
 * @param source      who measured it
 * @param unit        unit of {@code value}
 * @param aggregation how the value was reduced over {@code window}
 * @param value       the measurement
 * @param window      the interval it covers
 * @param dimensions  source-specific labels, e.g. {@code {"operation": "createOrder"}}
 * @param provenance  the query that produced it and where to check it; null when unknown
 * @param trace       how the value moved across the run; null when only one reading was taken
 */
public record MetricObservation(
        String id,
        String name,
        MetricSource source,
        MetricUnit unit,
        Aggregation aggregation,
        double value,
        TimeWindow window,
        Map<String, String> dimensions,
        ObservationProvenance provenance,
        ObservationTrace trace) {

    public MetricObservation {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(aggregation, "aggregation");
        Objects.requireNonNull(window, "window");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("metric observation id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("metric name must not be blank");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "metric '" + name + "' must have a finite value but was " + value);
        }
        dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
    }

    /**
     * An observation with no provenance and no trace.
     *
     * <p>Retained as a constructor rather than folded into the canonical one because it is how every
     * existing caller builds an observation, and widening a record should not mean editing every
     * site that never had anything to put in the new fields.
     */
    public MetricObservation(String id, String name, MetricSource source, MetricUnit unit,
            Aggregation aggregation, double value, TimeWindow window,
            Map<String, String> dimensions) {
        this(id, name, source, unit, aggregation, value, window, dimensions, null, null);
    }

    public static MetricObservation of(String id, String name, MetricSource source, MetricUnit unit,
            Aggregation aggregation, double value, TimeWindow window) {
        return new MetricObservation(id, name, source, unit, aggregation, value, window, Map.of());
    }

    public Optional<ObservationProvenance> provenanceIfPresent() {
        return Optional.ofNullable(provenance).filter(p -> !p.isEmpty());
    }

    public Optional<ObservationTrace> traceIfPresent() {
        return Optional.ofNullable(trace);
    }

    public MetricObservation withProvenance(ObservationProvenance newProvenance) {
        return new MetricObservation(id, name, source, unit, aggregation, value, window, dimensions,
                newProvenance, trace);
    }

    public MetricObservation withTrace(ObservationTrace newTrace) {
        return new MetricObservation(id, name, source, unit, aggregation, value, window, dimensions,
                provenance, newTrace);
    }

    /** The same measurement restated over a different window and aggregation. */
    public MetricObservation over(TimeWindow newWindow, Aggregation newAggregation) {
        return new MetricObservation(id, name, source, unit, newAggregation, value, newWindow,
                dimensions, provenance, trace);
    }

    /** Display form including units, e.g. {@code 522 ms} or {@code 98%}. */
    public String display() {
        return display(value);
    }

    /** Display form of an arbitrary value in this observation's unit, for start and end readings. */
    public String display(double reading) {
        if (unit == MetricUnit.BYTES) {
            return bytes(reading);
        }
        String number = reading == Math.rint(reading) && Math.abs(reading) < 1e15
                ? String.valueOf((long) reading)
                : String.format(java.util.Locale.ROOT, "%.2f", reading);
        return unit.symbol().isEmpty() ? number : number + " " + unit.symbol();
    }

    /**
     * Bytes at a scale a person can read.
     *
     * <p>{@code 5419040765 bytes} is technically the measurement and practically unreadable; nobody
     * comparing a heap against its limit counts digits. Powers of 1024, because that is what a JVM
     * and an operating system mean by a megabyte.
     */
    private static String bytes(double value) {
        String[] units = {"bytes", "KB", "MB", "GB", "TB"};
        double scaled = Math.abs(value);
        int unitIndex = 0;
        while (scaled >= 1024 && unitIndex < units.length - 1) {
            scaled /= 1024;
            unitIndex++;
        }
        double signed = value < 0 ? -scaled : scaled;
        String number = unitIndex == 0
                ? String.valueOf((long) signed)
                : String.format(java.util.Locale.ROOT, "%.1f", signed);
        return number + " " + units[unitIndex];
    }
}
