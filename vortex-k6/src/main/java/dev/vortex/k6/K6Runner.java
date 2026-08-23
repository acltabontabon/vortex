package dev.vortex.k6;

import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.port.PerformanceEngine.EngineAvailability;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Launches the k6 process.
 *
 * <p>Two implementations exist — a local binary and a container — and they differ in more than
 * mechanics. The container runner cannot reach {@code localhost} on the host, so the target has to
 * be rewritten; Vortex surfaces that rewrite in preflight rather than performing it silently,
 * because a run whose traffic went somewhere other than the address the user typed is a run whose
 * results they cannot interpret.
 */
public interface K6Runner {

    /** Whether this runner can execute a test right now, and what to do if not. */
    EngineAvailability availability();

    /** The detected engine version, for the reproducibility record. */
    Optional<String> version();

    /**
     * Whether this runner needs the target address changed, and why.
     *
     * <p>Empty when the configured target can be used as-is.
     */
    Optional<TargetRewrite> targetRewriteFor(EffectiveTestPlan plan);

    /**
     * Runs a k6 command to completion.
     *
     * @param arguments      command arguments, already split — never a shell string
     * @param workingDir     the execution's artifact directory; all file paths are relative to it
     * @param environment    variables to pass through, resolved as late as possible
     * @param stdoutSink     receives standard output lines as they arrive
     * @param stderrSink     receives standard error lines as they arrive
     * @param cancellation   polled so a run can be stopped
     */
    ProcessOutcome run(List<String> arguments, Path workingDir, Map<String, String> environment,
            java.util.function.Consumer<String> stdoutSink,
            java.util.function.Consumer<String> stderrSink,
            dev.vortex.core.port.PerformanceEngine.Cancellation cancellation);

    /**
     * @param newHost the host the engine must actually call
     * @param reason  why, in plain language, for display on the preflight screen
     */
    record TargetRewrite(String newHost, String reason) {
    }

    /**
     * @param exitCode  the process exit code
     * @param cancelled whether Vortex stopped it deliberately
     * @param command   the command as executed, with secret values already masked
     */
    record ProcessOutcome(int exitCode, boolean cancelled, String command) {

        public boolean succeeded() {
            return exitCode == 0;
        }

        /**
         * k6 exits 99 when the run completed but its declared thresholds were violated. Vortex
         * evaluates thresholds itself, so this is a successful measurement, not a process failure.
         */
        public boolean completedWithThresholdViolation() {
            return exitCode == 99;
        }

        public boolean producedAResult() {
            return succeeded() || completedWithThresholdViolation();
        }
    }
}
