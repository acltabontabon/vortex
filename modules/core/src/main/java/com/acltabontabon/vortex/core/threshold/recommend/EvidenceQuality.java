package com.acltabontabon.vortex.core.threshold.recommend;

import com.acltabontabon.vortex.core.validity.RunQuality;

/**
 * How much a piece of threshold evidence is worth trusting — a word, not a score.
 *
 * <p>A percentage confidence figure invites exactly the kind of manufactured precision this whole
 * feature exists to avoid: nothing here is measured precisely enough to justify "87% confident."
 * Three plain words, each backed by a stated rule, say everything a reader needs in order to judge
 * whether a recommendation is worth trusting as-is.
 */
public enum EvidenceQuality {

    STRONG("Strong evidence"),
    MODERATE("Moderate evidence"),
    LIMITED("Limited evidence");

    private final String label;

    EvidenceQuality(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Quality of a production observation.
     *
     * <p>Strong requires both a fetched (not hand-typed) observation and a complete operation mix —
     * either alone leaves something a reader would reasonably want to double-check.
     */
    public static EvidenceQuality ofProduction(boolean fetched, boolean mixCoverageComplete, boolean stale) {
        if (stale) {
            return LIMITED;
        }
        if (fetched && mixCoverageComplete) {
            return STRONG;
        }
        return MODERATE;
    }

    /**
     * Quality of a Vortex baseline, reusing run validity rather than inventing separate trust logic.
     *
     * <p>{@code RunQuality.INVALID} must never reach here — an invalid run is excluded from
     * candidacy entirely, not merely downgraded (see {@code ThresholdRecommender}).
     */
    public static EvidenceQuality ofBaseline(RunQuality quality, int compatiblePriorRunCount, boolean stale) {
        if (quality == RunQuality.INVALID) {
            throw new IllegalArgumentException("an invalid run must never be scored as evidence");
        }
        if (stale) {
            return LIMITED;
        }
        if (quality == RunQuality.DEGRADED || quality == RunQuality.NOT_ASSESSED) {
            return LIMITED;
        }
        return compatiblePriorRunCount >= 3 ? STRONG : MODERATE;
    }
}
