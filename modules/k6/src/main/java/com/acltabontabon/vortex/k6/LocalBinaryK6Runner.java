package com.acltabontabon.vortex.k6;

import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.port.PerformanceEngine.Cancellation;
import com.acltabontabon.vortex.core.port.PerformanceEngine.EngineAvailability;
import com.acltabontabon.vortex.core.target.ResourceEnvelopeRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs tests using a {@code k6} executable installed on this machine.
 *
 * <p>The default and recommended runner. It is the fastest path from a clean checkout to a real
 * measurement, and it introduces no networking indirection between the load generator and the
 * service under test.
 *
 * <p>Vortex will not download an executable on the user's behalf. Silently fetching and running a
 * binary is not a reasonable thing for a developer tool to do, so a missing k6 produces clear
 * installation guidance instead.
 */
public final class LocalBinaryK6Runner implements K6Runner {

    private final String executable;
    private final Consumer<Process> onProcessStarted;

    public LocalBinaryK6Runner(String executable) {
        this(executable, process -> { });
    }

    /**
     * @param onProcessStarted notified with the live process as soon as it starts, so a caller can
     *                         track it for shutdown cleanup; the load-generation process is otherwise
     *                         unreachable outside {@link ProcessExecution#run}'s own stack frame.
     */
    public LocalBinaryK6Runner(String executable, Consumer<Process> onProcessStarted) {
        this.executable = executable == null || executable.isBlank() ? "k6" : executable.trim();
        this.onProcessStarted = onProcessStarted;
    }

    public String executable() {
        return executable;
    }

    @Override
    public EngineAvailability availability() {
        Optional<String> detected = version();
        if (detected.isPresent()) {
            return EngineAvailability.ready(detected.get());
        }
        return EngineAvailability.unavailable(
                "Vortex expected to find '" + executable + "' but the executable was not available.",
                """
                Install k6, then try again:

                  macOS      brew install k6
                  Linux      see https://grafana.com/docs/k6/latest/set-up/install-k6/
                  Windows    winget install k6 --source winget

                If k6 is installed somewhere that is not on your PATH, set its full path under \
                Settings → Execution engine. Vortex will not download an executable for you.""");
    }

    @Override
    public Optional<String> version() {
        try {
            Process process = new ProcessBuilder(executable, "version")
                    .redirectErrorStream(true)
                    .start();
            String output;
            try (var reader = process.inputReader(java.nio.charset.StandardCharsets.UTF_8)) {
                output = reader.lines().findFirst().orElse("");
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return process.exitValue() == 0 && !output.isBlank()
                    ? Optional.of(output.trim())
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** A local binary reaches the host network directly, so no rewrite is ever needed. */
    @Override
    public Optional<TargetRewrite> targetRewriteFor(EffectiveTestPlan plan) {
        return Optional.empty();
    }

    /**
     * {@code resources} is accepted but never applied — a native process has no cross-platform
     * mechanism Vortex can rely on to enforce a CPU/memory ceiling (see {@code
     * AutomaticLoadGeneratorAllocation}'s Javadoc and the ADR this runner's resource story is
     * documented under). Claiming enforcement it cannot guarantee would be worse than not
     * attempting it, so {@link ProcessOutcome#effectiveResources} always comes back {@code null}
     * here, honestly, regardless of what was requested.
     */
    @Override
    public ProcessOutcome run(List<String> arguments, Path workingDir, Map<String, String> environment,
            ResourceEnvelopeRequest resources, Consumer<String> stdoutSink,
            Consumer<String> stderrSink, Cancellation cancellation) {

        if (!Files.isDirectory(workingDir)) {
            throw new K6ExecutionException("The execution directory does not exist.",
                    "Vortex expected to write artifacts to " + workingDir
                            + ". Check that the workspace directory is writable.");
        }

        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(arguments);
        return ProcessExecution.run(command, workingDir, environment, stdoutSink, stderrSink,
                cancellation, onProcessStarted);
    }
}
