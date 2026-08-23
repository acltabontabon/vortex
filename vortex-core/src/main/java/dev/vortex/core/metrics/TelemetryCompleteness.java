package dev.vortex.core.metrics;

import java.util.Objects;

/**
 * Whether a run's resource-telemetry artifact represents the whole run, part of it, or nothing.
 *
 * <p>Presence of the artifact file is not the same question: a writer can fail twenty minutes into an
 * hour-long run, leaving a file that exists and reads cleanly but describes less than half of what
 * happened. Reporting that as available, full stop, would make a partial record indistinguishable
 * from a complete one — the one outcome resource telemetry must never produce. This is a separate
 * axis from {@link TelemetryGap}/{@link TelemetryAvailability}, which describe whether one named
 * metric was obtained at all, not how much of a time span a series that *was* obtained actually
 * covers.
 *
 * @param status  complete, partial, or never available
 * @param covered the span this artifact actually describes; null when {@code status} is
 *                {@link Status#UNAVAILABLE}
 * @param reason  why, when not {@link Status#COMPLETE}; empty for a clean, complete session
 */
public record TelemetryCompleteness(Status status, TimeWindow covered, String reason) {

    public enum Status { COMPLETE, PARTIAL, UNAVAILABLE }

    public TelemetryCompleteness {
        Objects.requireNonNull(status, "status");
        reason = reason == null ? "" : reason;
    }

    public static TelemetryCompleteness unavailable() {
        return new TelemetryCompleteness(Status.UNAVAILABLE, null, "");
    }

    public boolean isUsable() {
        return status != Status.UNAVAILABLE;
    }
}
