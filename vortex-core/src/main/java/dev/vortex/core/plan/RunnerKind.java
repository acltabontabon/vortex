package dev.vortex.core.plan;

/** How the load generator is launched. */
public enum RunnerKind {

    /** A {@code k6} executable on this machine. */
    LOCAL_BINARY("Local k6 binary"),

    /** The official k6 container image. */
    DOCKER("k6 in Docker");

    private final String label;

    RunnerKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
