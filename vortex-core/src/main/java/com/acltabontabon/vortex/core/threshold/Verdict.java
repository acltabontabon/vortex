package com.acltabontabon.vortex.core.threshold;

/** The outcome of evaluating a threshold against measured results. */
public enum Verdict {

    PASS("Pass"),
    FAIL("Fail"),

    /**
     * The threshold could not be evaluated because the measurement it needs was not collected.
     *
     * <p>This is reported honestly rather than being folded into a pass: an unevaluated threshold
     * is not a satisfied threshold.
     */
    NOT_EVALUATED("Not evaluated");

    private final String label;

    Verdict(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isFailure() {
        return this == FAIL;
    }
}
