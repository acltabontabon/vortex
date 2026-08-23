package dev.vortex.core.comparison;

/** The outcome of a formal regression evaluation. */
public enum RegressionVerdict {

    IMPROVED("Improved", "Performance moved in a favourable direction beyond the noise threshold."),
    UNCHANGED("Unchanged", "No change large enough to distinguish from run-to-run variance."),
    REGRESSED("Regressed", "Performance degraded beyond the noise threshold."),
    NOT_COMPARABLE("Not comparable", "These executions did not test the same thing.");

    private final String label;
    private final String description;

    RegressionVerdict(String label, String description) {
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
