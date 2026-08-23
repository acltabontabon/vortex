package com.acltabontabon.vortex.core.capacity;

import java.time.Duration;

/**
 * How finely a production observation samples the window it covers.
 *
 * <h2>Why this is a rule rather than a constant</h2>
 * Every rate statistic Vortex takes from a monitoring system — the peak, the 95th percentile, the
 * mean — is a statistic <em>of a set of samples</em>, and the sample interval decides what those
 * words mean. A peak taken from one-minute samples is the busiest minute; a peak taken from hourly
 * samples is the busiest hour, which is a smaller number describing the same traffic. Neither is
 * wrong, and a report that does not say which it used is.
 *
 * <p>The interval cannot simply be fixed at one minute either: a thirty-day window at one-minute
 * resolution is forty-three thousand points, which most Prometheus deployments will refuse to
 * evaluate and no engineer needs. So it scales with the window, by a rule stated here rather than
 * chosen inside an adapter, so both adapters answer the same question the same way and the choice
 * can be argued with.
 */
public final class ObservationResolution {

    /** Windows up to this length are sampled every minute. */
    public static final Duration FINE_WINDOW = Duration.ofDays(2);

    /** Windows up to this length are sampled every five minutes. */
    public static final Duration MEDIUM_WINDOW = Duration.ofDays(14);

    private static final Duration FINE = Duration.ofMinutes(1);
    private static final Duration MEDIUM = Duration.ofMinutes(5);
    private static final Duration COARSE = Duration.ofHours(1);

    private ObservationResolution() {
    }

    /** The sample interval to use for an observation covering {@code window}. */
    public static Duration forWindow(Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException(
                    "an observation window of " + window + " covers no traffic");
        }
        if (window.compareTo(FINE_WINDOW) <= 0) {
            return FINE;
        }
        if (window.compareTo(MEDIUM_WINDOW) <= 0) {
            return MEDIUM;
        }
        return COARSE;
    }

    /**
     * The rule in words, for documentation and for the settings page.
     *
     * <p>Kept beside the rule it describes, because a documented threshold that lives in a different
     * file from the branch enforcing it is a documented threshold that will eventually be wrong.
     */
    public static String describe() {
        return "Rate samples are averaged over 1m for windows up to 2 days, 5m up to 14 days, "
                + "and 1h beyond, so a long window stays answerable without collapsing short bursts.";
    }
}
