package com.acltabontabon.vortex.core.threshold.recommend;

/**
 * Where a threshold's number came from.
 *
 * <p>A threshold with no source is not an error — see {@link #MANUAL_OBJECTIVE} — but a threshold
 * whose source is invisible is how "p95 below 500 ms" turns into an opinion nobody can defend later.
 * Every value shown to a user renders {@link #label()}, never the constant name: raw enum names read
 * as implementation detail, not evidence.
 */
public enum ThresholdSource {

    /** Derived from what the service currently experiences in production. */
    PRODUCTION_BASELINE("Production baseline"),

    /** Derived from the best qualifying prior Vortex execution(s) of this workload. */
    VORTEX_BASELINE("Vortex baseline"),

    /** A named, externally-defined service objective the user attributed by hand. */
    SLO("Existing SLO"),

    /** Carried over from the immediately preceding execution of this workload, unchanged. */
    PREVIOUS_EXECUTION("Previous run"),

    /** Typed in directly, with no supporting evidence. Honest, not a failure state. */
    MANUAL_OBJECTIVE("Manual objective"),

    /** An externally imposed requirement (contractual, regulatory) the user attributed by hand. */
    EXTERNAL_REQUIREMENT("External requirement");

    private final String label;

    ThresholdSource(String label) {
        this.label = label;
    }

    /** Natural-language label — always shown instead of the constant name. */
    public String label() {
        return label;
    }

    /** Whether this source carries deterministically-computed evidence, as opposed to attribution. */
    public boolean isDerived() {
        return this == PRODUCTION_BASELINE || this == VORTEX_BASELINE;
    }
}
