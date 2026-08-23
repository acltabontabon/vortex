package dev.vortex.core.execution;

import dev.vortex.core.shared.ErrorRate;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.shared.LoadLevel;
import dev.vortex.core.shared.RequestsPerSecond;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A snapshot of a run in flight, pushed to the UI while traffic is being generated.
 *
 * <p>Built from five-second aggregation buckets rather than individual samples. A test at
 * 200 requests/sec produces hundreds of thousands of samples; streaming those to a browser would
 * cost more than the test and risk perturbing the very measurement being taken.
 *
 * @param executionId  the run being reported
 * @param state        current lifecycle state
 * @param elapsed      time since traffic started
 * @param totalPlanned planned total duration, for the progress indicator
 * @param targetLevel  the level the workload is currently aiming for
 * @param currentRate  the request rate observed in the most recent bucket
 * @param currentP95   p95 latency in the most recent bucket
 * @param currentErrorRate error rate in the most recent bucket
 * @param stageLabel   which stage is running, e.g. {@code 100 → 150 requests/sec}
 * @param message      a human-readable status line
 * @param currentResourceReading the target's live CPU/memory reading in the most recent bucket, or
 *                     null when no resource-observing provider is attached to this run (an ordinary
 *                     external-endpoint target, or a run whose telemetry collector reports nothing).
 *                     Follows the exact nullable/{@code xIfPresent()} pattern already established by
 *                     {@code currentRate}/{@code currentP95}/{@code currentErrorRate} above — one more
 *                     field of the same kind, not a parallel channel.
 *
 *                     <p><strong>v1 status: always null.</strong> Populating it live requires {@code
 *                     TelemetryCollector.Session} to expose an incremental "latest reading" read
 *                     alongside its existing {@code finish()}-only shape, which Steps 1-11 did not
 *                     build and which Step 12 (this field's own step) explicitly permits deferring as
 *                     best-effort/lowest-priority rather than forcing an awkward wiring through the
 *                     k6 engine's own {@code ProgressPublisher} (a different Maven module with no
 *                     access to the telemetry session at all). The wire contract exists now so a
 *                     future pass can populate it without another round of API/frontend changes.
 */
public record ExecutionProgress(
        ExecutionId executionId,
        ExecutionState state,
        Duration elapsed,
        Duration totalPlanned,
        LoadLevel targetLevel,
        RequestsPerSecond currentRate,
        Duration currentP95,
        ErrorRate currentErrorRate,
        String stageLabel,
        String message,
        ResourceReading currentResourceReading) {

    public ExecutionProgress {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(state, "state");
        elapsed = elapsed == null ? Duration.ZERO : elapsed;
        totalPlanned = totalPlanned == null ? Duration.ZERO : totalPlanned;
        stageLabel = stageLabel == null ? "" : stageLabel;
        message = message == null ? "" : message;
    }

    public static ExecutionProgress starting(ExecutionId id, Duration totalPlanned, String message) {
        return new ExecutionProgress(id, ExecutionState.STARTING, Duration.ZERO, totalPlanned,
                null, null, null, null, "", message, null);
    }

    /** Completion fraction in [0, 1], or empty when the total duration is unknown. */
    public Optional<Double> completionFraction() {
        if (totalPlanned.isZero() || totalPlanned.isNegative()) {
            return Optional.empty();
        }
        double fraction = (double) elapsed.toMillis() / totalPlanned.toMillis();
        return Optional.of(Math.clamp(fraction, 0.0, 1.0));
    }

    public Optional<Duration> currentP95IfPresent() {
        return Optional.ofNullable(currentP95);
    }

    public Optional<RequestsPerSecond> currentRateIfPresent() {
        return Optional.ofNullable(currentRate);
    }

    public Optional<LoadLevel> targetLevelIfPresent() {
        return Optional.ofNullable(targetLevel);
    }

    public Optional<ErrorRate> currentErrorRateIfPresent() {
        return Optional.ofNullable(currentErrorRate);
    }

    public Optional<ResourceReading> currentResourceReadingIfPresent() {
        return Optional.ofNullable(currentResourceReading);
    }
}
