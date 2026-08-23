package dev.vortex.core.validity;

/**
 * Why an experiment was not carried out as specified.
 *
 * <p>A rule may exist here only if something measurable produces it. Every code below names the
 * evidence it requires, and a code whose evidence Vortex does not collect is not implemented, not
 * approximated — which is why {@link #WARM_UP_NOT_COMPLETED} is present as vocabulary and has no
 * assessor rule behind it.
 *
 * <p>There is no scoring and no weighting. A finding fires on a measurement or does not exist.
 *
 * <h2>Where the baseline-eligibility table lives</h2>
 * ADR-038 also decides what a <em>baseline</em> carrying each code may not be compared on. That
 * mapping is deliberately not a method here: it is about comparison, and putting it on this enum
 * would make the validity model depend on the comparison model to describe itself. It lives beside
 * the code that acts on it.
 */
public enum ValidityReason {

    /**
     * The load the workload asked for was never produced.
     *
     * <p>Evidence: k6 reported dropped iterations, or achieved rate fell materially below offered on
     * a stage where the service showed no distress — and "showed no distress" means distress was
     * looked for with the instruments to find it, not that nobody looked.
     */
    OFFERED_LOAD_NOT_GENERATED("Offered load was not generated"),

    /** A resource scoped to the load generator reached its declared limit. */
    GENERATOR_SATURATED("The load generator was saturated"),

    /**
     * The machine running the load generator — not the generator's own process or container — was
     * under resource pressure.
     *
     * <p>Weaker than {@link #GENERATOR_SATURATED} by design: shared host contention does not
     * establish that the generator's own request budget was constrained, only that something on that
     * machine was near a limit. Qualifies a run's confidence rather than withholding its capacity
     * conclusion.
     */
    GENERATOR_HOST_UNDER_PRESSURE("The load generator's host was under resource pressure"),

    /** The run was shorter than its test type's declared minimum. */
    RUN_TOO_SHORT("The run was too short"),

    /** A stage carried fewer requests than the declared minimum for a boundary edge. */
    INSUFFICIENT_SAMPLES("A stage had too few samples"),

    /**
     * A warm-up was declared and the run ended inside it.
     *
     * <p>Defined by ADR-038 but <em>not assessable in Phase 4</em>, because the workload model does
     * not yet express a declared warm-up. No assessor rule may fire until that evidence exists;
     * inventing an input so the rule has something to read would be exactly the approximation this
     * enum's contract forbids. P1's warm-up support activates it when the measurement model
     * genuinely supports it.
     */
    WARM_UP_NOT_COMPLETED("A declared warm-up did not complete"),

    /** Telemetry was missing over stages a conclusion rests on. */
    TELEMETRY_INCOMPLETE("Telemetry was incomplete"),

    /** Requests failed without reaching the service, in numbers that put its availability in doubt. */
    TARGET_UNAVAILABLE_DURING_RUN("The target may have been unavailable during the run"),

    /** The run was cancelled, or interrupted before it finished. */
    EXECUTION_INTERRUPTED("The run was interrupted"),

    /** A provider's telemetry window did not overlap the execution window within tolerance. */
    WINDOW_MISALIGNED("Telemetry did not cover the run");

    private final String label;

    ValidityReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Whether Vortex is able to assess this code at all yet.
     *
     * <p>Exists so the gate criterion — "every reason code in the implemented set cites a
     * measurement Vortex actually collects" — is something a test can assert rather than something a
     * reviewer has to remember.
     */
    public boolean isAssessable() {
        return this != WARM_UP_NOT_COMPLETED;
    }
}
