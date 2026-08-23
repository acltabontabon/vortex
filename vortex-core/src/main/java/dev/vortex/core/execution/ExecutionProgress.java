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
        String message) {

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
                null, null, null, null, "", message);
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
}
