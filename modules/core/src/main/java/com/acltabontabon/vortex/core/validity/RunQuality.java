package com.acltabontabon.vortex.core.validity;

/**
 * Whether the experiment was carried out as specified.
 *
 * <p>The fourth axis, beside {@code Verdict} (were the objectives met), {@code BoundaryStatus} (did
 * the tested levels form a boundary at all) and {@code EvidenceStrength} (how well corroborated is
 * this finding). None of those three asks the question underneath all of them.
 *
 * <h2>Not folded into the verdict, and never derived from it</h2>
 * A run can meet every objective and be {@link #INVALID}; a run can miss every objective and be
 * {@link #VALID} — indeed a stress test that breaks the service is the healthiest artefact in the
 * product. Collapsing the two would mean saying either "the objectives were not met" or "we could
 * not tell" in one word, which is the confusion this whole model exists to prevent.
 *
 * <h2>Three grades, and the state of not having been graded</h2>
 * {@link #VALID}, {@link #DEGRADED} and {@link #INVALID} are assessments. {@link #NOT_ASSESSED} is
 * not a grade at all — it describes a run recorded before this axis existed, or one that never
 * reached evaluation. It carries no reason codes and therefore withholds nothing, because what a
 * grade withholds is decided per reason code and a run with none has nothing to withhold.
 */
public enum RunQuality {

    VALID("Valid",
            "The experiment was carried out as specified. Conclusions stand as measured."),

    DEGRADED("Degraded",
            "Something reduces what this evidence supports. Conclusions are qualified."),

    INVALID("Not valid",
            "The experiment did not measure what it claims to. Conclusions are withheld."),

    NOT_ASSESSED("Not assessed",
            "This run's validity was never assessed. Nothing here is withheld on that account.");

    private final String label;
    private final String explanation;

    RunQuality(String label, String explanation) {
        this.label = label;
        this.explanation = explanation;
    }

    public String label() {
        return label;
    }

    public String explanation() {
        return explanation;
    }

    /** Whether an assessment was actually made. False only for {@link #NOT_ASSESSED}. */
    public boolean isAssessed() {
        return this != NOT_ASSESSED;
    }

    /**
     * Whether a capacity claim resting on this run may be quoted at all.
     *
     * <p>False only for {@link #INVALID}. A degraded run still supports a capacity figure with its
     * qualification attached, and an unassessed one is not evidence that anything went wrong.
     */
    public boolean permitsCapacityClaims() {
        return this != INVALID;
    }
}
