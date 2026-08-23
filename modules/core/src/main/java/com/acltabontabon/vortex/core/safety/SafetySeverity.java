package com.acltabontabon.vortex.core.safety;

/** How serious a safety finding is, and therefore what it takes to proceed past it. */
public enum SafetySeverity {

    /** Worth knowing. Does not interrupt the run. */
    INFO("Note"),

    /** The user must acknowledge this before the run starts. */
    WARNING("Warning"),

    /** The run cannot start until the underlying problem is fixed. */
    BLOCKING("Blocked");

    private final String label;

    SafetySeverity(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
