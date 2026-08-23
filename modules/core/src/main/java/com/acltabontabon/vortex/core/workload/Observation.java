package com.acltabontabon.vortex.core.workload;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * When an observation was taken.
 *
 * <p>Traffic is almost never observed at an instant. "120 requests/sec at the month-end peak" is a
 * statement about a window — an hour, an evening, a rolling thirty days — and the window is part of
 * what the number means: the same service observed over a minute and over a month produces different
 * peaks, and a reader who cannot see which was used cannot judge the figure.
 *
 * <p>Vortex therefore models the window rather than flattening it to a timestamp. A single
 * {@code Instant} would have forced every window to be recorded as one of its endpoints, silently
 * discarding the rest — and a zero-length window is not the same claim as a point reading.
 *
 * <p>Three states, kept distinct because they mean different things:
 *
 * <ul>
 *   <li>{@link #over(Instant, Instant)} — measured across a period. The usual case.</li>
 *   <li>{@link #at(Instant)} — a genuine point reading, such as a gauge sampled once. Rare, and
 *       worth being able to say explicitly rather than disguising as a one-second window.</li>
 *   <li>{@link #unknown()} — nobody recorded when. Absent stays absent: a placeholder timestamp
 *       would make an unattributed number look sourced.</li>
 * </ul>
 */
public record Observation(Instant from, Instant to) {

    private static final Observation UNKNOWN = new Observation(null, null);

    public Observation {
        if (from == null && to != null) {
            throw new IllegalArgumentException(
                    "an observation with an end but no beginning is not a window");
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new IllegalArgumentException(
                    "an observation window cannot end (" + to + ") before it begins (" + from + ")");
        }
    }

    /** Measured across a period — the ordinary case for observed traffic. */
    public static Observation over(Instant from, Instant to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return new Observation(from, to);
    }

    /** A genuine single reading, not a window collapsed to one end of itself. */
    public static Observation at(Instant instant) {
        Objects.requireNonNull(instant, "instant");
        return new Observation(instant, null);
    }

    /** Nobody recorded when. */
    public static Observation unknown() {
        return UNKNOWN;
    }

    public boolean isKnown() {
        return from != null;
    }

    public boolean isWindow() {
        return from != null && to != null;
    }

    /** A point observation: known, but covering no period. */
    public boolean isPoint() {
        return from != null && to == null;
    }

    /**
     * How long the observation covers, for a window.
     *
     * <p>Empty for a point reading rather than {@link Duration#ZERO}: "observed over no time at all"
     * and "observed at one moment" are different claims, and only one of them is true.
     */
    public Optional<Duration> span() {
        return isWindow() ? Optional.of(Duration.between(from, to)) : Optional.empty();
    }

    public Optional<Instant> fromIfPresent() {
        return Optional.ofNullable(from);
    }

    public Optional<Instant> toIfPresent() {
        return Optional.ofNullable(to);
    }

    /**
     * The instant this observation is anchored to, for ordering and staleness checks.
     *
     * <p>The end of a window, because that is when the observation stopped being true of the live
     * system — a thirty-day window ending yesterday is fresher evidence than a one-hour window from
     * last year, and anchoring on the start would reverse that.
     */
    public Optional<Instant> anchor() {
        return isWindow() ? Optional.of(to) : Optional.ofNullable(from);
    }

    /**
     * The window stated plainly, in UTC.
     *
     * <p>ISO-8601 rather than a friendly rendering: this string ends up in reports and exports that
     * are read on machines other than the one that produced them, and a locale-dependent date is how
     * two readers come to disagree about which day a peak fell on. The interface renders local time
     * where a person is looking at it.
     */
    public String describe() {
        if (!isKnown()) {
            return "not recorded";
        }
        if (isPoint()) {
            return "at " + from;
        }
        return from + " to " + to;
    }
}
