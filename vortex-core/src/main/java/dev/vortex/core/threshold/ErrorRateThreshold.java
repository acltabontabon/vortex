package dev.vortex.core.threshold;

import dev.vortex.core.shared.ErrorRate;
import dev.vortex.core.shared.OperationId;
import java.util.Objects;

/**
 * A maximum acceptable share of failed requests, e.g. "below 1%".
 *
 * <p>May apply to the whole run or to one operation — see {@link ThresholdScope}.
 */
public record ErrorRateThreshold(ThresholdScope scope, ErrorRate maximum) implements Threshold {

    public ErrorRateThreshold {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(maximum, "maximum");
    }

    public static ErrorRateThreshold ofPercent(double percent) {
        return new ErrorRateThreshold(ThresholdScope.OVERALL, ErrorRate.ofPercent(percent));
    }

    public static ErrorRateThreshold ofFraction(double fraction) {
        return new ErrorRateThreshold(ThresholdScope.OVERALL, ErrorRate.ofFraction(fraction));
    }

    public static ErrorRateThreshold ofPercent(OperationId operation, double percent) {
        return new ErrorRateThreshold(ThresholdScope.of(operation), ErrorRate.ofPercent(percent));
    }

    @Override
    public String id() {
        return "errorRate" + scope.idSuffix();
    }

    @Override
    public String describe() {
        return "error rate below " + maximum.display() + scope.describeSuffix();
    }
}
