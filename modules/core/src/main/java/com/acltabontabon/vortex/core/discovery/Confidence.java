package com.acltabontabon.vortex.core.discovery;

/**
 * How certain a {@link Finding} is, in three levels rather than a fabricated percentage — Vortex has
 * no statistical model behind detection, so a number like {@code 93.72%} would claim precision that
 * does not exist.
 */
public enum Confidence {

    HIGH("High", "Explicit, structural evidence — a declared dependency, an image tag, or a "
            + "document Vortex could parse successfully."),
    MEDIUM("Medium", "A plausible but indirect signal, or one of several similarly likely "
            + "candidates."),
    LOW("Low", "A naming or heuristic guess, or evidence Vortex could only partially confirm.");

    private final String label;
    private final String explanation;

    Confidence(String label, String explanation) {
        this.label = label;
        this.explanation = explanation;
    }

    public String label() {
        return label;
    }

    public String explanation() {
        return explanation;
    }
}
