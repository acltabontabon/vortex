package dev.vortex.app.adapter.target.docker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Whether the {@code docker} CLI and its daemon are usable — the one place that runs and parses
 * {@code docker --version} and checks daemon reachability, shared by {@link
 * dev.vortex.app.adapter.lab.DockerLocalLab} (which additionally checks for Compose) and {@link
 * DockerImageTargetExecutor} (which only needs to know whether Docker itself is usable before
 * attempting anything image-specific).
 */
public class DockerCapabilityProbe {

    /**
     * How long to wait on a capability probe.
     *
     * <p>Short on purpose. A probe either answers quickly or the daemon is not reachable, and a
     * probe waiting out a long timeout is how a settings page or a run's preparation step comes to
     * hang for minutes.
     */
    private static final int PROBE_TIMEOUT_SECONDS = 10;

    private final String dockerExecutable;

    public DockerCapabilityProbe(String dockerExecutable) {
        this.dockerExecutable = dockerExecutable == null || dockerExecutable.isBlank()
                ? "docker" : dockerExecutable.trim();
    }

    /** Whether the CLI is present and its daemon is reachable — the two facts either caller needs
     *  to know before attempting anything further. */
    public boolean isAvailable() {
        DockerAvailability availability = check();
        return availability.installed() && availability.daemonReachable();
    }

    /** The same check, with enough detail for a settings page to explain what is missing. */
    public DockerAvailability check() {
        CommandResult version = run(List.of(dockerExecutable, "--version"));
        if (!version.succeeded()) {
            return new DockerAvailability(false, false, "",
                    "Docker was not found. Install Docker Desktop, or Docker Engine on Linux. "
                            + "Docker is optional — Vortex only needs it for containerised "
                            + "dependencies or the containerised load generator.");
        }

        CommandResult daemon =
                run(List.of(dockerExecutable, "version", "--format", "{{.Server.Version}}"));
        if (!daemon.succeeded()) {
            return new DockerAvailability(true, false, version.firstLine(),
                    "Docker is installed but its daemon is not reachable. Start Docker Desktop, or "
                            + "check that the docker service is running and your user can access "
                            + "the socket.");
        }

        return new DockerAvailability(true, true, version.firstLine(), "");
    }

    private CommandResult run(List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            Process process = builder.start();
            List<String> output = new ArrayList<>();
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.add(line);
                }
            }
            boolean finished = process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(-1, List.of());
            }
            return new CommandResult(process.exitValue(), output);
        } catch (IOException e) {
            return new CommandResult(-1, List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, List.of());
        }
    }

    private record CommandResult(int exitCode, List<String> output) {

        boolean succeeded() {
            return exitCode == 0;
        }

        String firstLine() {
            return output.isEmpty() ? "" : output.getFirst();
        }
    }

    /**
     * @param installed       the Docker CLI is present
     * @param daemonReachable the daemon answered
     * @param version         detected version information (blank when not installed)
     * @param remedy          what to do when something is missing
     */
    public record DockerAvailability(boolean installed, boolean daemonReachable, String version,
            String remedy) {

        public boolean isUsable() {
            return installed && daemonReachable;
        }
    }
}
