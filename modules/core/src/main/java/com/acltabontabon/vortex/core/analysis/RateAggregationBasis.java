package com.acltabontabon.vortex.core.analysis;

/**
 * How a {@link StageObservation}'s request rate and error rate were actually derived.
 *
 * <p>Averaging each bucket's own error fraction unweighted is mathematically wrong whenever bucket
 * traffic volume differs — a near-empty bucket counts the same as one carrying most of the stage's
 * requests. Preserved per-bucket request and failure counts fix that by deriving the stage error rate
 * as {@code sum(failures) / sum(requests)} directly. Request totals and rate benefit too, though
 * differently: under Vortex's current fixed nominal bucket width, the count-derived rate is
 * algebraically identical to the previous arithmetic mean of bucket rates — the gain there is evidence
 * provenance (a total and a rate resting on preserved primitive counts, not a value reconstructed from
 * an already-derived scalar), not a numerical correction. This basis names which situation applies so a
 * reader is not left assuming both rates carry the same weakness.
 */
public enum RateAggregationBasis {

    /**
     * Error rate is {@code sum(failures) / sum(requests)}; request totals and rate are summed from
     * preserved request counts over the stage's existing nominal bucket duration.
     */
    PRESERVED_COUNTS("derived from preserved request/failure counts"),

    /**
     * The stage predates preserved request/failure counts: error rate is the unweighted average of
     * each bucket's own error fraction (a known mathematical weakness), and request totals/rate rely on
     * previously-derived bucket scalar values rather than preserved counts (a provenance weakness, not
     * necessarily a numerical one). Permanent for this data — the counts behind it no longer exist.
     */
    LEGACY_DERIVED_BUCKET_VALUES("derived from bucket-level values (legacy)");

    private final String label;

    RateAggregationBasis(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isLegacy() {
        return this == LEGACY_DERIVED_BUCKET_VALUES;
    }
}
