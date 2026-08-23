package dev.vortex.core.threshold;

import dev.vortex.core.shared.OperationId;
import dev.vortex.core.shared.Percentile;
import java.time.Duration;
import java.util.Objects;

/**
 * A maximum acceptable latency at a given percentile, e.g. "p95 below 500 ms".
 *
 * <p>Percentiles rather than averages, because an average hides the experience of the unluckiest
 * users: a service with a 90 ms mean can still be failing one request in twenty at three seconds.
 *
 * <p>May apply to the whole run or to one operation — see {@link ThresholdScope}.
 */
public record LatencyThreshold(ThresholdScope scope, Percentile percentile, Duration maximum)
        implements Threshold {

    public LatencyThreshold {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(percentile, "percentile");
        Objects.requireNonNull(maximum, "maximum");
        if (maximum.isZero() || maximum.isNegative()) {
            throw new IllegalArgumentException(
                    "latency threshold must be positive but was " + maximum);
        }
        if (maximum.toMinutes() > 10) {
            throw new IllegalArgumentException(
                    "latency threshold must be at most 10 minutes but was " + maximum);
        }
    }

    public static LatencyThreshold of(Percentile percentile, Duration maximum) {
        return new LatencyThreshold(ThresholdScope.OVERALL, percentile, maximum);
    }

    public static LatencyThreshold of(OperationId operation, Percentile percentile, Duration maximum) {
        return new LatencyThreshold(ThresholdScope.of(operation), percentile, maximum);
    }

    public static LatencyThreshold ofMillis(double percent, long millis) {
        return of(Percentile.of(percent), Duration.ofMillis(millis));
    }

    @Override
    public String id() {
        return "latency." + percentile.label() + scope.idSuffix();
    }

    @Override
    public String describe() {
        return percentile.label() + " latency below " + Durations.display(maximum)
                + scope.describeSuffix();
    }
}
