package dev.vortex.core.execution;

/**
 * Why an execution did not complete.
 *
 * <p>Each reason carries what a user should do about it, because "process exited 1" is not an error
 * message — it is the absence of one.
 */
public enum FailureReason {

    PREFLIGHT_FAILED("Preflight checks failed",
            "One or more checks failed before any traffic was generated. The preflight report lists "
                    + "which, and what to do about each."),

    ENGINE_UNAVAILABLE("Load generator unavailable",
            "Vortex could not start k6. Install it, or configure its location under "
                    + "Settings → Execution engine."),

    ENGINE_FAILED("Load generator failed",
            "k6 started but exited unexpectedly. The captured standard error output is attached to "
                    + "this execution."),

    TARGET_UNREACHABLE("Target unreachable",
            "The service did not respond at the configured address. Check that it is running and "
                    + "that the URL and port are correct."),

    RESULTS_UNREADABLE("Results could not be read",
            "The run finished but Vortex could not parse its output. The raw artifacts are "
                    + "preserved so nothing is lost."),

    INTERRUPTED("Interrupted",
            "Vortex stopped while this run was in progress. Vortex does not adopt orphaned engine "
                    + "processes on restart, so this run was marked failed rather than left pending."),

    INTERNAL_ERROR("Internal error",
            "Something in Vortex itself went wrong. The application log holds the details.");

    private final String label;
    private final String guidance;

    FailureReason(String label, String guidance) {
        this.label = label;
        this.guidance = guidance;
    }

    public String label() {
        return label;
    }

    /** What the user can do next. */
    public String guidance() {
        return guidance;
    }
}
