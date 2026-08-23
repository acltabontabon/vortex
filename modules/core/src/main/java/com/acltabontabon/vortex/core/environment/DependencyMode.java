package com.acltabontabon.vortex.core.environment;

/** Whether the service under test is talking to real downstream systems. */
public enum DependencyMode {

    MOCKED("Mocked", "Downstream dependencies are simulated."),
    REAL("Real", "Downstream dependencies are the real systems."),
    MIXED("Mixed", "Some dependencies are real and some are simulated."),
    UNKNOWN("Unknown", "The dependency configuration has not been recorded.");

    private final String label;
    private final String description;

    DependencyMode(String label, String description) {
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
