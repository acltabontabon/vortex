package com.acltabontabon.vortex.core.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * A rate of requests per second: the control quantity of an <em>open</em> workload, and also the
 * throughput actually observed during a run.
 *
 * <p>One Vortex operation is one load-generator iteration is one request, so the rate a workload
 * asks for and the rate the engine reports count the same events. Vortex still reports target and
 * achieved separately and never derives one from the other — a service that cannot keep up delivers
 * fewer requests than were offered, and that gap is one of the most important signals a run
 * produces.
 *
 * <p>This type used to have a sibling, {@code JourneysPerSecond}, because a multi-step journey at
 * 100 arrivals/sec issued roughly 300 requests/sec and conflating the two produced capacity figures
 * wrong by a factor of three. That ambiguity was created by the journey abstraction and disappeared
 * with it; see {@code docs/adr/adr-024-service-level-workload-modelling.adoc}.
 *
 * <p>Zero is representable because an observation of zero throughput is a real and important
 * measurement. A workload that asks for zero is not, so the workload types reject it themselves
 * with a message about the workload rather than about the number.
 *
 * @see Concurrency
 */
public record RequestsPerSecond(BigDecimal value)
        implements LoadLevel, Comparable<RequestsPerSecond> {

    /** Rates are held to three decimal places; k6 cannot meaningfully schedule finer than this. */
    public static final int SCALE = 3;

    /** The smallest schedulable increment, matching {@link #SCALE}. */
    public static final BigDecimal UNIT = BigDecimal.ONE.movePointLeft(SCALE);

    public static final BigDecimal MAXIMUM = BigDecimal.valueOf(1_000_000);

    public RequestsPerSecond {
        if (value == null) {
            throw new IllegalArgumentException("request rate must not be null");
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(
                    "request rate must not be negative but was " + value.toPlainString());
        }
        if (value.compareTo(MAXIMUM) > 0) {
            throw new IllegalArgumentException(
                    "request rate must be at most " + MAXIMUM.toPlainString() + "/sec but was "
                            + value.toPlainString());
        }
        value = value.setScale(SCALE, RoundingMode.HALF_UP);
    }

    public static RequestsPerSecond of(double value) {
        return new RequestsPerSecond(BigDecimal.valueOf(value));
    }

    public static RequestsPerSecond of(String value) {
        return new RequestsPerSecond(new BigDecimal(value));
    }

    /**
     * A rate intended to drive a workload, which must be greater than zero.
     *
     * @param context what the rate is for, used in the failure message so the reader is told which
     *                setting is wrong rather than merely that a number was rejected
     */
    public static RequestsPerSecond driving(BigDecimal value, String context) {
        RequestsPerSecond rate = new RequestsPerSecond(value);
        if (!rate.isPositive()) {
            throw new IllegalArgumentException(
                    context + " must be greater than 0 requests/sec. A workload that generates no "
                            + "traffic produces no evidence.");
        }
        return rate;
    }

    public boolean isPositive() {
        return value.signum() > 0;
    }

    @Override
    public double asDouble() {
        return value.doubleValue();
    }

    /** Human display, trimming trailing zeros: {@code 40.2}, {@code 20}, {@code 6.667}. */
    @Override
    public String display() {
        return value.stripTrailingZeros().toPlainString();
    }

    @Override
    public String unit() {
        return "requests/sec";
    }

    @Override
    public int compareTo(RequestsPerSecond other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return displayWithUnit();
    }
}
