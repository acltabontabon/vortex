package dev.vortex.core.evidence;

/**
 * How much attention a deterministic finding deserves.
 *
 * <p>Distinct from {@code Verdict}, and deliberately so. A verdict answers "did this run meet its
 * objectives" and has exactly three honest answers. A finding level answers "how should a reader
 * weigh this sentence", which needs a warning tier that a pass/fail verdict must never have — a run
 * can meet every objective while having delivered only 82% of the traffic it offered, and neither
 * "pass" nor "fail" describes that.
 */
public enum FindingLevel {

    /** An objective was violated, or the run did not test what it set out to test. */
    FAIL("Failed", 0),

    /** Something a reader must weigh before trusting the verdict. */
    WARNING("Warning", 1),

    /** Something the run measured that is worth knowing but implies no judgement. */
    OBSERVATION("Observation", 2),

    /** An objective was met, or the run did what it intended. */
    PASS("Met", 3);

    private final String label;
    private final int rank;

    FindingLevel(String label, int rank) {
        this.label = label;
        this.rank = rank;
    }

    public String label() {
        return label;
    }

    /**
     * Sort order, most serious first.
     *
     * <p>Explicit rather than {@code ordinal()}, so that reordering the constants — which is the
     * sort of edit nobody expects to change behaviour — cannot silently reorder every report.
     */
    public int rank() {
        return rank;
    }

    public boolean isFailure() {
        return this == FAIL;
    }
}
