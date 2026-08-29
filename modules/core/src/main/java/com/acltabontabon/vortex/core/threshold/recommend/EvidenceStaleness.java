package com.acltabontabon.vortex.core.threshold.recommend;

import com.acltabontabon.vortex.core.workload.Observation;
import java.time.Duration;
import java.time.Instant;

/**
 * How old is too old, for threshold evidence — rules stated once so a recommendation and a later
 * "new evidence is available" check never disagree about what counts as stale.
 *
 * <p>Age tolerance varies by source, deliberately: a production observation's window says how long
 * it was meant to represent, so staleness is judged against that window rather than a fixed number.
 * A Vortex baseline has no window of its own — the service itself changes at whatever pace the team
 * ships at, not at the pace traffic patterns drift — so it uses a flat, shorter tolerance instead.
 * Attributed evidence (SLO, external requirement, manual) never goes stale: there is nothing
 * time-bound to compare it against.
 */
public final class EvidenceStaleness {

    /** A Vortex baseline older than this is shown as stale, regardless of how long the run took. */
    public static final Duration BASELINE_MAX_AGE = Duration.ofDays(30);

    private EvidenceStaleness() {
    }

    /**
     * A production observation is stale once its age exceeds twice the window it was taken over — a
     * thirty-day window observation is stale sixty days after it was last refetched. An observation
     * with no recorded window, or an unrecorded anchor, is treated as stale: there is nothing to
     * compare its age against, so it cannot be vouched for as current.
     */
    public static boolean isProductionStale(Observation observedAt, Instant now) {
        if (observedAt == null || !observedAt.isKnown()) {
            return true;
        }
        return observedAt.anchor()
                .map(anchor -> {
                    Duration windowSpan = observedAt.span().orElse(Duration.ZERO);
                    Duration tolerance = windowSpan.isZero() ? BASELINE_MAX_AGE : windowSpan.multipliedBy(2);
                    return Duration.between(anchor, now).compareTo(tolerance) > 0;
                })
                .orElse(true);
    }

    /** A Vortex baseline is stale once it is older than {@link #BASELINE_MAX_AGE}. */
    public static boolean isBaselineStale(Instant executedAt, Instant now) {
        if (executedAt == null) {
            return true;
        }
        return Duration.between(executedAt, now).compareTo(BASELINE_MAX_AGE) > 0;
    }
}
