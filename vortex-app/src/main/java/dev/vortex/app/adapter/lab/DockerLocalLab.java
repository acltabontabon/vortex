package dev.vortex.app.adapter.lab;

import dev.vortex.app.adapter.target.docker.DockerCapabilityProbe;
import dev.vortex.core.port.LocalLab;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts and stops the containers a service needs in order to be tested locally.
 *
 * <p>Vortex references a project's existing Compose file rather than owning one. Most services that
 * need a database and a couple of stubs already have a working {@code compose.yaml}, and a tool that
 * insisted on generating its own would be asking teams to maintain the same thing twice.
 *
 * <p>Docker is optional. Nothing about onboarding, configuration, execution, evaluation or reporting
 * requires it; it is only needed when the chosen workflow involves containerised dependencies or the
 * containerised load generator.
 *
 * <p>What this class reports is the outcome of a command it ran. It does not watch containers
 * afterwards, and its messages are worded so as not to suggest otherwise.
 */
public final class DockerLocalLab implements LocalLab {

    private static final Logger log = LoggerFactory.getLogger(DockerLocalLab.class);

    /**
     * How long to wait on a capability probe.
     *
     * <p>Short on purpose. A probe either answers quickly or the daemon is not reachable, and three
     * probes each waiting out a long timeout is how a settings page comes to hang for minutes.
     */
    private static final int PROBE_TIMEOUT_SECONDS = 10;

    /**
     * How long to wait on a Compose command when nobody configured a limit.
     *
     * <p>Generous, because a first run pulls images before it starts anything. The application
     * supplies its own configured value; this exists so direct construction still works.
     */
    public static final Duration DEFAULT_COMPOSE_TIMEOUT = Duration.ofMinutes(15);

    private final String dockerExecutable;
    private final int composeTimeoutSeconds;
    private final Consumer<Process> onProcessStarted;
    private final DockerCapabilityProbe capabilityProbe;

    public DockerLocalLab(String dockerExecutable) {
        this(dockerExecutable, DEFAULT_COMPOSE_TIMEOUT);
    }

    public DockerLocalLab(String dockerExecutable, Duration composeTimeout) {
        this(dockerExecutable, composeTimeout, process -> { });
    }

    /**
     * @param onProcessStarted notified with each live Docker/Compose process as soon as it starts, so
     *                         a caller can track it for shutdown cleanup.
     */
    public DockerLocalLab(String dockerExecutable, Duration composeTimeout,
            Consumer<Process> onProcessStarted) {
        this.dockerExecutable = dockerExecutable == null || dockerExecutable.isBlank()
                ? "docker" : dockerExecutable.trim();
        Duration timeout = composeTimeout == null || composeTimeout.isZero()
                || composeTimeout.isNegative() ? DEFAULT_COMPOSE_TIMEOUT : composeTimeout;
        this.composeTimeoutSeconds = (int) Math.min(timeout.toSeconds(), Integer.MAX_VALUE);
        this.onProcessStarted = onProcessStarted;
        this.capabilityProbe = new DockerCapabilityProbe(this.dockerExecutable);
    }

    @Override
    public LabStatus status() {
        DockerCapabilityProbe.DockerAvailability availability = capabilityProbe.check();
        if (!availability.installed()) {
            return new LabStatus(false, false, false, "", availability.remedy());
        }
        if (!availability.daemonReachable()) {
            return new LabStatus(true, false, false, availability.version(), availability.remedy());
        }

        CommandResult compose =
                run(List.of(dockerExecutable, "compose", "version"), null, PROBE_TIMEOUT_SECONDS);
        return new LabStatus(true, true, compose.succeeded(),
                availability.version() + (compose.succeeded() ? ", " + compose.firstLine() : ""),
                compose.succeeded() ? ""
                        : "Docker Compose was not found. It ships with Docker Desktop; on Linux "
                        + "install the docker-compose-plugin package.");
    }

    /**
     * Runs {@code compose up}, and reports whether that command succeeded.
     *
     * <p>The message says the dependencies started, not that a lab "is running". A successful
     * {@code up -d --wait} establishes that Compose brought its services up and their healthchecks
     * passed at that moment. Nothing here watches them afterwards, so nothing here should imply it.
     */
    @Override
    public LabResult up(String composeFilePath) {
        return compose(composeFilePath, List.of("up", "-d", "--wait"),
                "Dependencies started successfully.");
    }

    @Override
    public LabResult down(String composeFilePath) {
        return compose(composeFilePath, List.of("down"),
                "Docker Compose stopped the dependencies it manages.");
    }

    private LabResult compose(String composeFilePath, List<String> operation, String successMessage) {
        Path composeFile = Paths.get(composeFilePath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(composeFile)) {
            return new LabResult(false,
                    "No Compose file was found at " + composeFile + ". Point Vortex at your "
                            + "project's existing compose.yaml under the project's settings.",
                    List.of());
        }

        LabStatus status = status();
        if (!status.isUsable()) {
            return new LabResult(false, status.remedy(), List.of());
        }

        List<String> command = new ArrayList<>(
                List.of(dockerExecutable, "compose", "-f", composeFile.toString()));
        command.addAll(operation);

        CommandResult result = run(command, composeFile.getParent(), composeTimeoutSeconds);
        return new LabResult(result.succeeded(),
                result.succeeded() ? successMessage
                        : "Docker Compose exited with code " + result.exitCode()
                        + ". The captured output is below.",
                result.output());
    }

    /** How many of the most recent output lines are kept for diagnosis. */
    private static final int MAX_OUTPUT_LINES = 200;

    /** How long the drain thread is given, after the process ends, to capture its last lines. */
    private static final Duration DRAIN_GRACE_PERIOD = Duration.ofSeconds(5);

    CommandResult run(List<String> command, Path workingDirectory, int timeoutSeconds) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            if (workingDirectory != null) {
                builder.directory(workingDirectory.toFile());
            }
            Process process = builder.start();
            onProcessStarted.accept(process);

            // Drained continuously from the moment the process starts, on its own thread, so the
            // OS pipe buffer can never fill and block the child on its own stdout write — which is
            // exactly what a bounded read that stops early would risk on a verbose first run (an
            // image pull can easily print past a couple hundred lines before the real result).
            BoundedLines output = new BoundedLines(MAX_OUTPUT_LINES);
            Thread drain = Thread.ofVirtual()
                    .name("docker-compose-output-" + process.pid())
                    .start(() -> drain(process, output));

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            try {
                drain.join(DRAIN_GRACE_PERIOD);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (!finished) {
                return new CommandResult(-1, List.of("The command timed out after "
                        + timeoutSeconds + " seconds."));
            }
            return new CommandResult(process.exitValue(), output.snapshot());
        } catch (IOException e) {
            log.debug("Docker command failed: {}", e.getMessage());
            return new CommandResult(-1, List.of(e.getMessage() == null ? "not found" : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, List.of("interrupted"));
        }
    }

    private void drain(Process process, BoundedLines output) {
        try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
        } catch (IOException e) {
            log.debug("Could not read Docker Compose output: {}", e.getMessage());
        }
    }

    record CommandResult(int exitCode, List<String> output) {

        boolean succeeded() {
            return exitCode == 0;
        }

        String firstLine() {
            return output.isEmpty() ? "" : output.getFirst();
        }
    }

    /**
     * Keeps only the most recent {@code capacity} lines.
     *
     * <p>The tail is what a diagnosis needs: Compose can print pages of image-pull progress before
     * the line that actually explains a failure, so keeping the head instead would tend to lose
     * exactly the useful part on the runs where output is long enough to matter.
     */
    private static final class BoundedLines {

        private final int capacity;
        private final ArrayDeque<String> lines = new ArrayDeque<>();

        BoundedLines(int capacity) {
            this.capacity = capacity;
        }

        synchronized void add(String line) {
            if (lines.size() == capacity) {
                lines.removeFirst();
            }
            lines.addLast(line);
        }

        // Synchronized as a cheap safety net: if the grace period elapses before the drain thread
        // notices the process has closed its output, this may still be read concurrently with one
        // last add().
        synchronized List<String> snapshot() {
            return List.copyOf(lines);
        }
    }
}
