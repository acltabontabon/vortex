package dev.vortex.core.metrics;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The same measurement at three moments of a run, so that a rise is visible rather than inferred.
 *
 * <p>A peak on its own does not say whether a run caused anything. "Heap at 73%" is a different
 * statement from "heap rose from 58% to 73% during the run and did not recover", and only the
 * second is evidence about the workload. Carrying the start and the end beside the peak is what
 * lets a report say which one it means.
 *
 * <p>This does not make Vortex an observability platform, and it deliberately stops short of a time
 * series: three points answer "did this move, and when was it worst" without turning the product
 * into something that stores and queries telemetry.
 *
 * @param startValue the first value sampled during the run
 * @param peakValue  the highest value sampled
 * @param endValue   the last value sampled
 * @param peakAt     when the peak was sampled; null when the provider did not say
 */
public record ObservationTrace(
        double startValue,
        double peakValue,
        double endValue,
        Instant peakAt) {

    /** How much a signal must move before the change is worth reporting at all. */
    private static final double MATERIAL_RISE = 1.1;

    /** How far a signal must fall back before it counts as having recovered. */
    private static final double RECOVERY = 0.9;

    public ObservationTrace {
        requireFinite(startValue, "startValue");
        requireFinite(peakValue, "peakValue");
        requireFinite(endValue, "endValue");
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be a finite measurement but was " + value);
        }
    }

    public Optional<Instant> peakAtIfPresent() {
        return Optional.ofNullable(peakAt);
    }

    /** How far the signal climbed above where it started. */
    public double rise() {
        return peakValue - startValue;
    }

    /**
     * Whether the signal moved enough during the run to be worth remarking on.
     *
     * <p>A signal that started at its peak was already there before the traffic arrived, and saying
     * the run drove it there would be wrong.
     */
    public boolean roseDuringRun() {
        return startValue > 0 ? peakValue > startValue * MATERIAL_RISE : peakValue > 0;
    }

    /** Whether the signal fell back after its peak, rather than staying elevated. */
    public boolean recovered() {
        return peakValue > 0 && endValue < peakValue * RECOVERY;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ObservationTrace trace
                && Double.compare(startValue, trace.startValue) == 0
                && Double.compare(peakValue, trace.peakValue) == 0
                && Double.compare(endValue, trace.endValue) == 0
                && Objects.equals(peakAt, trace.peakAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(startValue, peakValue, endValue, peakAt);
    }
}
