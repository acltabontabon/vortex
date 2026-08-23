package dev.vortex.app.adapter.target.docker;

import dev.vortex.core.port.PerformanceEngine.Cancellation;
import dev.vortex.k6.ProcessExecution;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Thin wrapper over {@link ProcessExecution#run} for the short-lived {@code docker} CLI
 * invocations a target executor issues — {@code create}, {@code start}, {@code inspect}, {@code
 * rm} — as opposed to the 15-minute-and-longer k6 runs {@link ProcessExecution} was built for.
 *
 * <p>{@link ProcessExecution}'s cooperative cancellation (ask nicely, wait a 10-second grace
 * period, then kill) exists for a user cancelling an in-progress load test; it does not apply
 * here, so every call passes {@link Cancellation#never()}. A hung {@code docker} invocation calls
 * for a hard deadline instead, which this class enforces itself by force-destroying the process
 * once {@code timeout} elapses.
 *
 * <p>Not {@code final}, so a test can substitute a scripted subclass that returns canned {@link
 * DockerCommandResult}s and records the commands it was asked to run, without a real Docker
 * daemon.
 */
public class DockerProcess {

    private static final Path NEUTRAL_WORKING_DIR = Path.of(System.getProperty("user.dir"));

    /** Runs {@code command}, killing it if it has not finished within {@code timeout}. */
    public DockerCommandResult run(List<String> command, Duration timeout) {
        List<String> stdout = Collections.synchronizedList(new ArrayList<>());
        List<String> stderr = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Process> processHandle = new AtomicReference<>();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Integer> future = executor.submit(() -> ProcessExecution.run(command,
                    NEUTRAL_WORKING_DIR, Map.of(), stdout::add, stderr::add, Cancellation.never(),
                    processHandle::set).exitCode());
            try {
                int exitCode = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return new DockerCommandResult(exitCode, List.copyOf(stdout), List.copyOf(stderr));
            } catch (TimeoutException e) {
                Process process = processHandle.get();
                if (process != null) {
                    process.destroyForcibly();
                }
                future.cancel(true);
                stderr.add("docker command timed out after " + timeout);
                return new DockerCommandResult(-1, List.copyOf(stdout), List.copyOf(stderr));
            } catch (ExecutionException e) {
                stderr.add("docker command failed to run: "
                        + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
                return new DockerCommandResult(-1, List.copyOf(stdout), List.copyOf(stderr));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stderr.add("interrupted while waiting for the docker command");
            return new DockerCommandResult(-1, List.copyOf(stdout), List.copyOf(stderr));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Starts a long-lived command without waiting for it to exit — the one shape {@link #run} does
     * not cover, since that method blocks its caller until the process finishes or its bound is hit.
     * The only caller today is {@link DockerContainerObservabilityProvider}, whose {@code docker
     * stats <id>} stream must stay open for a whole run rather than being spawned anew for every
     * sample (see the class-level investigation writeup there for why).
     *
     * <p>Same discipline as {@link #run}: an argument array handed straight to {@link ProcessBuilder},
     * never a shell. Every stdout line is handed to {@code stdoutSink} as it arrives, on a virtual
     * thread, until the stream is stopped or the process ends on its own.
     */
    public StreamHandle stream(List<String> command, Consumer<String> stdoutSink) {
        ProcessBuilder builder = new ProcessBuilder(List.copyOf(command));
        builder.directory(NEUTRAL_WORKING_DIR.toFile());
        builder.redirectErrorStream(true);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new UncheckedIOException("could not start: " + String.join(" ", command), e);
        }
        Thread pump = Thread.ofVirtual().name("vortex-docker-stream").start(() -> {
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdoutSink.accept(line);
                }
            } catch (IOException e) {
                // A stream closed because the process was stopped, or exited on its own (the
                // container it was watching was removed) — either way, normal, not an error worth
                // surfacing.
            }
        });
        return new StreamHandle(process, pump);
    }

    /** A running long-lived command started by {@link #stream}. */
    public static final class StreamHandle {

        private final Process process;
        private final Thread pump;

        StreamHandle(Process process, Thread pump) {
            this.process = process;
            this.pump = pump;
        }

        /** A handle over nothing. For a test that substitutes a scripted {@link DockerProcess}
         *  subclass overriding {@link DockerProcess#stream} — matching this class's own documented
         *  testing seam — so it can hand back something {@link #stop} tolerates without ever having
         *  started a real process. */
        public static StreamHandle noop() {
            return new StreamHandle(null, null);
        }

        /** Stops the process and waits briefly for its reader thread to notice, so a caller that
         *  immediately starts something else does not race a pump thread still winding down. Safe to
         *  call more than once, and safe on a {@link #noop} handle. */
        public void stop() {
            if (process != null) {
                process.destroyForcibly();
            }
            if (pump == null) {
                return;
            }
            try {
                pump.join(Duration.ofSeconds(2).toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public record DockerCommandResult(int exitCode, List<String> stdout, List<String> stderr) {

        public DockerCommandResult {
            stdout = List.copyOf(stdout);
            stderr = List.copyOf(stderr);
        }

        public boolean succeeded() {
            return exitCode == 0;
        }

        /** The container/image id most single-line {@code docker} commands print on success. */
        public String firstStdoutLine() {
            return stdout.isEmpty() ? "" : stdout.getFirst();
        }
    }
}
