package com.acltabontabon.vortex.core.validity;

/**
 * What a validity finding does to the conclusions a run may state.
 *
 * <p>Deliberately three outcomes rather than a score. There is no weighting and no blended
 * confidence percentage: a finding either fires on a measurement or does not exist, and what it does
 * when it fires is one of these.
 */
public enum ValidityEffect {

    /**
     * The conclusion is weaker, and says so. The default.
     *
     * <p>Most findings land here. A run held for two minutes when its type wants five measured
     * everything it claims to measure; a reader just needs to know before quoting it.
     */
    QUALIFIES(RunQuality.DEGRADED),

    /**
     * No capacity may be quoted, at or above the level this finding names.
     *
     * <p>For the cases where the capacity figure specifically would be false while the latency and
     * reliability measurements around it remain sound.
     */
    WITHHOLDS_CAPACITY(RunQuality.INVALID),

    /**
     * The run did not measure what it claims to, and no conclusion rests safely on it.
     *
     * <p>Reserved for the cases where a specific claim would be false rather than merely weaker.
     */
    WITHHOLDS_ALL_CLAIMS(RunQuality.INVALID);

    private final RunQuality grade;

    ValidityEffect(RunQuality grade) {
        this.grade = grade;
    }

    /** The grade a run carrying this finding cannot be better than. */
    public RunQuality grade() {
        return grade;
    }

    public boolean withholdsCapacity() {
        return this != QUALIFIES;
    }
}
