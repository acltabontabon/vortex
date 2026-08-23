package com.acltabontabon.vortex.core.analysis;

/**
 * How much a conclusion can be relied upon, given the evidence that was actually collected.
 *
 * <p>Confidence is not an AI-only concept in Vortex, and treating it as one would be a mistake.
 * Deterministic findings vary in strength too: an SLO breakpoint identified from twelve stages with
 * dense sampling is a much firmer statement than one inferred from two stages, even though both are
 * computed by the same arithmetic.
 *
 * <p>Reporting that strength alongside the finding is what keeps a number honest.
 */
public enum EvidenceStrength {

    HIGH("High", "Multiple independent observations support this."),
    MEDIUM("Medium", "The observations support this, but corroborating signals are limited."),
    LOW("Low", "This is consistent with the observations but only weakly supported."),
    INSUFFICIENT("Insufficient", "There is not enough evidence to state this at all.");

    private final String label;
    private final String description;

    EvidenceStrength(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
