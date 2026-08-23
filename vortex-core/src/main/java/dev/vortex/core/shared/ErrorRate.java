package dev.vortex.core.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** A failure ratio in the closed range [0, 1]. {@code 0.008} means 0.8% of requests failed. */
public record ErrorRate(BigDecimal fraction) implements Comparable<ErrorRate> {

    public static final ErrorRate ZERO = new ErrorRate(BigDecimal.ZERO);

    public ErrorRate {
        if (fraction == null) {
            throw new IllegalArgumentException("error rate must not be null");
        }
        if (fraction.signum() < 0 || fraction.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    "error rate must be between 0 and 1 but was " + fraction.toPlainString());
        }
        fraction = fraction.setScale(6, RoundingMode.HALF_UP);
    }

    public static ErrorRate ofFraction(double fraction) {
        return new ErrorRate(BigDecimal.valueOf(fraction));
    }

    public static ErrorRate ofPercent(double percent) {
        return new ErrorRate(BigDecimal.valueOf(percent).movePointLeft(2));
    }

    public static ErrorRate of(long failed, long total) {
        if (total <= 0) {
            return ZERO;
        }
        return new ErrorRate(BigDecimal.valueOf(failed)
                .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP));
    }

    public double asFraction() {
        return fraction.doubleValue();
    }

    public double asPercent() {
        return fraction.movePointRight(2).doubleValue();
    }

    /** Display as a percentage with at most two decimals: {@code 0.8%}, {@code 0%}, {@code 12.34%}. */
    public String display() {
        return fraction.movePointRight(2).setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString() + "%";
    }

    @Override
    public int compareTo(ErrorRate other) {
        return fraction.compareTo(other.fraction);
    }

    @Override
    public String toString() {
        return display();
    }
}
