package com.acltabontabon.vortex.core.analysis;

/**
 * How a {@link StageObservation}'s p95 was actually derived.
 *
 * <p>Averaging one bucket's p95 with another's is not the pooled p95 of anything — percentiles do not
 * compose under arithmetic averaging. A stage's percentile is a mathematically valid pooled estimate
 * only once every bucket behind it carries its own latency distribution to merge; a run recorded before
 * that evidence existed can still be read, but its stage percentile rests on the older, weaker
 * approximation. This travels with the number so a reader can tell which kind of evidence they are
 * looking at, the same way {@link com.acltabontabon.vortex.core.workload.StageWindowBasis} travels with
 * a stage's boundaries.
 */
public enum PercentileBasis {

    /** Merged from every bucket's own latency histogram in the stage — a mathematically valid pool. */
    MERGED_HISTOGRAM("pooled from bucket latency distributions"),

    /**
     * The stage predates pooled latency-distribution capture: its p95 is the arithmetic mean of each
     * bucket's own already-computed p95, the only thing this data can still produce, permanently — the
     * raw distribution behind it no longer exists to recompute from.
     */
    LEGACY_AVERAGED_BUCKET_PERCENTILES("averaged from bucket percentiles (legacy)");

    private final String label;

    PercentileBasis(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isLegacy() {
        return this == LEGACY_AVERAGED_BUCKET_PERCENTILES;
    }
}
