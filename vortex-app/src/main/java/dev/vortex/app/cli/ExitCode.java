package dev.vortex.app.cli;

/**
 * The exit codes {@code vortex} returns.
 *
 * <p>The essential distinction is between {@link #THRESHOLDS_VIOLATED} and {@link #ERROR}: a
 * pipeline must be able to tell "the service did not meet its performance objectives" apart from
 * "Vortex could not run the test". Collapsing both into 1 means a broken k6 installation looks
 * exactly like a performance regression, and teams learn to ignore the gate entirely.
 *
 * <p>These values are part of Vortex's contract with continuous integration and are covered by
 * tests. Changing one is a breaking change.
 */
public enum ExitCode {

    /** The run completed and every objective was met. */
    SUCCESS(0),

    /**
     * Vortex itself failed: a missing executable, an unreachable workspace, an internal error.
     * The test result, if any, is unknown.
     */
    ERROR(1),

    /**
     * The run completed and at least one objective was violated.
     *
     * <p>The test worked. The service did not meet its objectives. This is the code a performance
     * gate should fail the build on.
     */
    THRESHOLDS_VIOLATED(2),

    /**
     * Configuration or preflight checks failed, so no traffic was generated.
     *
     * <p>Distinct from a violation: nothing was measured, so nothing can be concluded about the
     * service.
     */
    VALIDATION_FAILED(3),

    /** The run was cancelled before it finished. */
    CANCELLED(4),

    /**
     * The run finished, and did not measure what it claims to.
     *
     * <p>None of the codes above fits. It is not an objective violation — the objectives may all
     * have been met — and it is not a preflight failure, because preflight runs before any traffic
     * and this is discovered afterwards. Returning {@code 0} is the option that gets somebody
     * burned: a pipeline green-lighting a deploy on a run which never generated the load it asked
     * for.
     *
     * <p>Adding a code is safe in the direction that matters. Every existing script treating
     * non-zero as failure keeps working, whereas redefining {@code 2} to sometimes mean "we could
     * not tell" would silently change what an existing gate asserts.
     *
     * <p>Only invalidity reaches here. A degraded run exits on its verdict with the qualification
     * printed beside it — failing a build because telemetry was incomplete would train teams to stop
     * collecting telemetry, which is the opposite of the intent.
     */
    EVIDENCE_NOT_VALID(5);

    private final int value;

    ExitCode(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}
