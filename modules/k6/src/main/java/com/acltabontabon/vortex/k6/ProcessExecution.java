package com.acltabontabon.vortex.k6;

import com.acltabontabon.vortex.core.environment.SecretReferences;
import com.acltabontabon.vortex.core.port.PerformanceEngine.Cancellation;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a child process safely.
 *
 * <p>Shared by both k6 runners, and the single place where Vortex spawns anything. Several
 * properties here are deliberate:
 *
 * <ul>
 *   <li><strong>Argument arrays, never a shell.</strong> Nothing is concatenated into a command
 *       string, so a workload name or URL containing {@code ; rm -rf} is an argument, not a command.
 *       There is no shell in the picture to interpret it.</li>
 *   <li><strong>An explicit environment allowlist.</strong> The child receives only the variables
 *       the plan actually needs plus a minimal set for the process to function, rather than
 *       inheriting everything the user happens to have exported.</li>
 *   <li><strong>Masked command logging.</strong> The recorded command has secret-looking values
 *       replaced, so an execution's metadata never becomes the place a token leaks.</li>
 *   <li><strong>Cooperative cancellation.</strong> A cancelled run is asked to stop, then killed if
 *       it does not, so a stray load generator is never left pointed at someone's environment.</li>
 * </ul>
 */
public final class ProcessExecution {

    private static final Logger log = LoggerFactory.getLogger(ProcessExecution.class);

    /** How long a cancelled process is given to exit before it is killed. */
    private static final Duration GRACE = Duration.ofSeconds(10);

    /** Variables always passed through, because a process needs them to run at all — including the
     *  Docker CLI variables that select which daemon/context "docker" talks to (Colima, OrbStack,
     *  Rancher Desktop and remote hosts are commonly configured this way rather than via {@code
     *  docker context use}); without them a stripped-environment {@code docker} invocation silently
     *  falls back to the default context and reports an image or container missing that plainly
     *  exists on the daemon the user actually runs. */
    private static final Set<String> ALWAYS_PASS = Set.of("PATH", "HOME", "TMPDIR", "LANG", "LC_ALL",
            "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG", "DOCKER_CERT_PATH", "DOCKER_TLS_VERIFY");

    private ProcessExecution() {
    }

    private record Duration(long seconds) {

        static Duration ofSeconds(long seconds) {
            return new Duration(seconds);
        }
    }

    public static K6Runner.ProcessOutcome run(List<String> command, Path workingDir,
            Map<String, String> environment, Consumer<String> stdoutSink,
            Consumer<String> stderrSink, Cancellation cancellation, Consumer<Process> onStart) {
        return run(command, workingDir, environment, stdoutSink, stderrSink, cancellation, onStart,
                GRACE.seconds());
    }

    /** Package-visible so a test can shrink the grace period instead of waiting out the real one. */
    static K6Runner.ProcessOutcome run(List<String> command, Path workingDir,
            Map<String, String> environment, Consumer<String> stdoutSink,
            Consumer<String> stderrSink, Cancellation cancellation, Consumer<Process> onStart,
            long graceSeconds) {

        String maskedCommand = mask(command);
        log.info("Starting process: {}", maskedCommand);

        ProcessBuilder builder = new ProcessBuilder(List.copyOf(command));
        builder.directory(workingDir.toFile());
        builder.redirectErrorStream(false);

        Map<String, String> childEnvironment = builder.environment();
        Map<String, String> inherited = Map.copyOf(childEnvironment);
        childEnvironment.clear();
        for (String name : ALWAYS_PASS) {
            String value = inherited.get(name);
            if (value != null) {
                childEnvironment.put(name, value);
            }
        }
        childEnvironment.putAll(environment);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new K6ExecutionException(
                    "Vortex could not start the execution engine.",
                    "The command was: " + maskedCommand + "\nThe operating system reported: "
                            + e.getMessage(),
                    e);
        }
        onStart.accept(process);

        // Virtual threads: these tasks are almost entirely blocked on I/O, and a platform thread per
        // stream per concurrent run would be pure waste.
        Thread stdout = Thread.ofVirtual().name("k6-stdout").start(
                () -> pump(process.inputReader(StandardCharsets.UTF_8), stdoutSink));
        Thread stderr = Thread.ofVirtual().name("k6-stderr").start(
                () -> pump(process.errorReader(StandardCharsets.UTF_8), stderrSink));

        boolean cancelled = false;
        int exitCode;
        try {
            while (process.isAlive()) {
                if (cancellation.isCancelled()) {
                    cancelled = true;
                    log.info("Cancellation requested; asking the engine to stop");
                    process.destroy();
                    if (!process.waitFor(graceSeconds, TimeUnit.SECONDS)) {
                        log.warn("The engine did not stop within {}s; terminating it", graceSeconds);
                        process.destroyForcibly();
                    }
                    break;
                }
                process.waitFor(250, TimeUnit.MILLISECONDS);
            }
            exitCode = process.waitFor();
            stdout.join();
            stderr.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new K6ExecutionException("The execution was interrupted.",
                    "Vortex was stopped while the engine was running.", e);
        }

        log.info("Process finished with exit code {}{}", exitCode, cancelled ? " (cancelled)" : "");
        return new K6Runner.ProcessOutcome(exitCode, cancelled, maskedCommand);
    }

    private static void pump(BufferedReader reader, Consumer<String> sink) {
        try (reader) {
            String line;
            while ((line = reader.readLine()) != null) {
                sink.accept(line);
            }
        } catch (IOException e) {
            // A closed stream when the process exits is normal, not an error worth surfacing.
            log.debug("Output stream closed: {}", e.getMessage());
        }
    }

    /**
     * Renders a command for logging with anything credential-shaped removed.
     *
     * <p>Vortex passes secrets through the environment rather than the command line, so this is a
     * second line of defence rather than the primary one — but a command that ends up in a log,
     * an artifact or a bug report should never be the thing that leaks a token.
     */
    static String mask(List<String> command) {
        List<String> masked = new ArrayList<>(command.size());
        for (String argument : command) {
            if (SecretReferences.containsReference(argument)
                    || argument.toLowerCase(java.util.Locale.ROOT).contains("token")
                    || argument.toLowerCase(java.util.Locale.ROOT).contains("password")
                    || argument.toLowerCase(java.util.Locale.ROOT).contains("secret")
                    || argument.toLowerCase(java.util.Locale.ROOT).contains("authorization")) {
                masked.add(SecretReferences.MASK);
            } else {
                masked.add(argument);
            }
        }
        return String.join(" ", masked);
    }
}
