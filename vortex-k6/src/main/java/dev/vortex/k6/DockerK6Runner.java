package dev.vortex.k6;

import dev.vortex.core.environment.TargetUrl;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.port.PerformanceEngine.Cancellation;
import dev.vortex.core.port.PerformanceEngine.EngineAvailability;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs tests using the official k6 container image.
 *
 * <p>Useful when k6 is not installed, or when a specific engine version needs pinning for
 * reproducibility. It is the secondary runner, because it adds a network boundary between the load
 * generator and the service under test, and that boundary has consequences a user should know about.
 *
 * <h2>The target has to change, and Vortex says so</h2>
 * A container's {@code localhost} is the container, not the host. Testing a service running on the
 * developer's machine therefore requires rewriting the target — typically to
 * {@code host.docker.internal}, which Docker Desktop provides on macOS and Windows and which
 * requires an explicit host mapping on Linux.
 *
 * <p>Vortex never performs that substitution quietly. The preflight screen shows both the address
 * that was configured and the address that will actually be called, because a run whose traffic went
 * somewhere other than where the user thinks is a run whose results cannot be trusted — and because
 * on Linux the rewrite may simply not resolve, which is far easier to diagnose when it is visible.
 *
 * <h2>What containerisation does and does not give you</h2>
 * Running an imported script in a container limits its access to the host filesystem, which is
 * worth something. It is not a sandbox: the container shares the host kernel, has network access,
 * and can reach anything the host can reach. Vortex describes this accurately rather than presenting
 * the Docker runner as a way to safely execute untrusted scripts.
 */
public final class DockerK6Runner implements K6Runner {

    /** Pinned rather than {@code latest}, so a run is reproducible and an image change is deliberate. */
    public static final String DEFAULT_IMAGE = "grafana/k6:1.3.0";

    private static final String HOST_GATEWAY = "host.docker.internal";

    private final String dockerExecutable;
    private final String image;
    private final Consumer<Process> onProcessStarted;

    public DockerK6Runner(String dockerExecutable, String image) {
        this(dockerExecutable, image, process -> { });
    }

    /**
     * @param onProcessStarted notified with the live process as soon as it starts, so a caller can
     *                         track it for shutdown cleanup; the load-generation process is otherwise
     *                         unreachable outside {@link ProcessExecution#run}'s own stack frame.
     */
    public DockerK6Runner(String dockerExecutable, String image, Consumer<Process> onProcessStarted) {
        this.dockerExecutable = dockerExecutable == null || dockerExecutable.isBlank()
                ? "docker" : dockerExecutable.trim();
        this.image = image == null || image.isBlank() ? DEFAULT_IMAGE : image.trim();
        this.onProcessStarted = onProcessStarted;
    }

    public String image() {
        return image;
    }

    @Override
    public EngineAvailability availability() {
        Optional<String> daemon = dockerVersion();
        if (daemon.isEmpty()) {
            return EngineAvailability.unavailable(
                    "Vortex could not reach the Docker daemon.",
                    """
                    Start Docker and try again. On macOS and Windows that means launching Docker \
                    Desktop; on Linux, check that the docker service is running and that your user \
                    can access the socket.

                    Alternatively, install k6 directly and switch to the local runner under \
                    Settings → Execution engine, which avoids Docker entirely.""");
        }
        return EngineAvailability.ready("k6 in Docker (" + image + "), " + daemon.get());
    }

    @Override
    public Optional<String> version() {
        return dockerVersion().map(version -> image + " via " + version);
    }

    private Optional<String> dockerVersion() {
        try {
            Process process = new ProcessBuilder(dockerExecutable, "version", "--format",
                    "{{.Server.Version}}")
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
                    ? Optional.of("Docker " + output.trim())
                    : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Reports the rewrite this runner requires, so preflight can display it.
     *
     * <p>Only loopback targets need it: a container can reach an external hostname perfectly well.
     */
    @Override
    public Optional<TargetRewrite> targetRewriteFor(EffectiveTestPlan plan) {
        // A target with no resolvable pre-run URL (Docker/Compose) has nothing to rewrite here yet —
        // by the time this runs against the transient, post-resolution plan copy (see
        // ExecutionService), configuredTarget is always the run's real resolved endpoint.
        if (plan.configuredTargetIfPresent().filter(TargetUrl::isLoopback).isEmpty()) {
            return Optional.empty();
        }
        String platform = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String note = platform.contains("linux")
                ? " On Linux this name is not provided automatically; Vortex adds an explicit host "
                + "mapping so it resolves."
                : "";
        return Optional.of(new TargetRewrite(HOST_GATEWAY,
                "a container's 'localhost' is the container itself, not your machine, so the target "
                        + "must be rewritten to reach the service running on your host." + note));
    }

    @Override
    public ProcessOutcome run(List<String> arguments, Path workingDir, Map<String, String> environment,
            Consumer<String> stdoutSink, Consumer<String> stderrSink, Cancellation cancellation) {

        List<String> command = new ArrayList<>();
        command.add(dockerExecutable);
        command.add("run");
        command.add("--rm");
        command.add("-i");
        // A deterministic name, derived from the same execution id ObservabilityTelemetryCollector
        // already receives, so it can watch this exact container's own CPU/memory via `docker stats`
        // without Vortex having to thread a container id back across the module boundary — see
        // LoadGeneratorObservabilityProvider's class Javadoc for why that measurement matters.
        command.add("--name");
        command.add("vortex-k6-" + workingDir.getFileName());
        command.add("--add-host");
        command.add(HOST_GATEWAY + ":host-gateway");
        command.add("-v");
        command.add(workingDir.toAbsolutePath() + ":/vortex");
        command.add("-w");
        command.add("/vortex");

        // Secrets travel as environment variables into the container, not as command arguments,
        // so they never appear in `docker ps` output or in a logged command line.
        for (String name : environment.keySet()) {
            command.add("-e");
            command.add(name);
        }

        command.add(image);
        command.addAll(arguments);

        return ProcessExecution.run(command, workingDir, environment, stdoutSink, stderrSink,
                cancellation, onProcessStarted);
    }
}
