package com.acltabontabon.vortex.k6;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.acltabontabon.vortex.core.environment.TargetUrl;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.port.PerformanceEngine.Cancellation;
import com.acltabontabon.vortex.core.port.PerformanceEngine.EngineAvailability;
import com.acltabontabon.vortex.core.target.CpuAllocation;
import com.acltabontabon.vortex.core.target.EffectiveResourceEnvelope;
import com.acltabontabon.vortex.core.target.MemoryAllocation;
import com.acltabontabon.vortex.core.target.ResourceEnvelopeRequest;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *
 * <h2>Resource enforcement, applied then confirmed</h2>
 * When {@link #run} is given a non-empty {@link ResourceEnvelopeRequest}, {@code --cpus}/{@code
 * --memory} are applied at {@code docker run} time and, once the container has stopped, re-confirmed
 * by inspecting what Docker actually recorded — the same apply-then-confirm discipline {@code
 * DockerImageTargetExecutor} already uses for the system under test, so a limit is never reported as
 * applied on the strength of the request alone. The container is never started with {@code --rm}:
 * Vortex removes it itself, after inspecting its exit state, because {@code --rm} would remove the
 * evidence of an out-of-memory kill in the same moment it becomes available to read.
 */
public final class DockerK6Runner implements K6Runner {

    private static final Logger log = LoggerFactory.getLogger(DockerK6Runner.class);

    /** Pinned rather than {@code latest}, so a run is reproducible and an image change is deliberate. */
    public static final String DEFAULT_IMAGE = "grafana/k6:1.3.0";

    private static final String HOST_GATEWAY = "host.docker.internal";

    /** Bound for {@code inspect}/{@code rm} — local CLI calls against an already-running daemon. */
    private static final Duration DOCKER_COMMAND_TIMEOUT = Duration.ofSeconds(30);

    private final String dockerExecutable;
    private final String image;
    private final Consumer<Process> onProcessStarted;
    private final ObjectMapper objectMapper = new ObjectMapper();

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
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
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
            ResourceEnvelopeRequest resources, Consumer<String> stdoutSink,
            Consumer<String> stderrSink, Cancellation cancellation) {

        // A deterministic name, derived from the same execution id ObservabilityTelemetryCollector
        // already receives, so it can watch this exact container's own CPU/memory via `docker stats`
        // without Vortex having to thread a container id back across the module boundary — see
        // LoadGeneratorObservabilityProvider's class Javadoc for why that measurement matters.
        String containerName = "vortex-k6-" + workingDir.getFileName();

        List<String> command = new ArrayList<>();
        command.add(dockerExecutable);
        command.add("run");
        command.add("-i");
        command.add("--name");
        command.add(containerName);
        appendResourceFlags(command, resources);
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

        ProcessOutcome outcome = ProcessExecution.run(command, workingDir, environment, stdoutSink,
                stderrSink, cancellation, onProcessStarted);

        return confirmAndClean(containerName, resources, outcome);
    }

    /** Appends {@code --cpus}/{@code --memory} when {@code resources} requests them — omitted
     *  entirely when neither is present, so an unconstrained run's command looks exactly as it did
     *  before this feature existed. */
    private void appendResourceFlags(List<String> command, ResourceEnvelopeRequest resources) {
        if (resources.isEmpty()) {
            return;
        }
        resources.cpuIfPresent().ifPresent(cpu -> {
            // millicores/1000 is always exact at 3 decimal places (e.g. 500 -> "0.500") — exact
            // decimal arithmetic, never a double division that could round.
            BigDecimal cpus = BigDecimal.valueOf(cpu.millicores(), 3);
            command.add("--cpus");
            command.add(cpus.toPlainString());
        });
        resources.memoryIfPresent().ifPresent(memory -> {
            command.add("--memory");
            command.add(String.valueOf(memory.bytes()));
        });
    }

    /**
     * Re-reads what Docker actually did with {@code containerName} once it has stopped — the
     * applied resource limits, when any were requested, and whether the container was killed for
     * exceeding its memory limit — then removes it unconditionally. A cancelled run skips
     * confirmation entirely: a container Vortex is deliberately stopping is not evidence of anything
     * about resource enforcement, only about the cancellation.
     */
    private ProcessOutcome confirmAndClean(String containerName, ResourceEnvelopeRequest resources,
            ProcessOutcome outcome) {
        try {
            if (outcome.cancelled()) {
                return outcome;
            }
            EffectiveResourceEnvelope effectiveResources = resources.isEmpty()
                    ? null : confirmResourceEnvelope(containerName, resources);
            // Reported only when a memory budget was actually requested — Docker's OOMKilled flag
            // can reflect host-level conditions unrelated to a limit Vortex never configured, and
            // this runner never claims a cause it did not confirm.
            boolean oomKilled = resources.memoryIfPresent().isPresent()
                    && wasOomKilled(containerName);
            return new ProcessOutcome(outcome.exitCode(), outcome.cancelled(), outcome.command(),
                    effectiveResources, oomKilled);
        } finally {
            removeContainer(containerName);
        }
    }

    /** Re-reads the container's actual applied CPU/memory limits and compares them, as exact
     *  integers, against what was requested — never floats or strings. Throws {@link
     *  K6ExecutionException} on any mismatch, or if Docker's own report of the applied
     *  configuration can't be obtained or parsed — mirrors {@code
     *  DockerImageTargetExecutor#confirmResourceEnvelope}. */
    private EffectiveResourceEnvelope confirmResourceEnvelope(String containerName,
            ResourceEnvelopeRequest requested) {
        JsonNode hostConfig = inspect(containerName, "{{json .HostConfig}}");
        if (hostConfig == null) {
            throw new K6ExecutionException(
                    "Vortex could not confirm the load generator's resource limits.",
                    "Docker did not report the applied configuration for container " + containerName
                            + ".");
        }

        CpuAllocation confirmedCpu = null;
        if (requested.cpuIfPresent().isPresent()) {
            CpuAllocation wanted = requested.cpuIfPresent().get();
            // NanoCpus is what --cpus stores, and integer division by 1,000,000 recovers millicores
            // exactly.
            long nanoCpus = hostConfig.path("NanoCpus").asLong(-1);
            int actualMillicores = (int) (nanoCpus / 1_000_000);
            if (nanoCpus < 0 || actualMillicores != wanted.millicores()) {
                throw new K6ExecutionException(
                        "Vortex could not confirm the load generator's CPU limit.",
                        "Requested " + wanted.millicores() + "m but Docker reports "
                                + (nanoCpus < 0 ? "no limit applied" : actualMillicores + "m")
                                + " for container " + containerName + ".");
            }
            confirmedCpu = wanted;
        }

        MemoryAllocation confirmedMemory = null;
        if (requested.memoryIfPresent().isPresent()) {
            MemoryAllocation wanted = requested.memoryIfPresent().get();
            long actualBytes = hostConfig.path("Memory").asLong(-1);
            if (actualBytes != wanted.bytes()) {
                throw new K6ExecutionException(
                        "Vortex could not confirm the load generator's memory limit.",
                        "Requested " + wanted.bytes() + " bytes but Docker reports " + actualBytes
                                + " bytes for container " + containerName + ".");
            }
            confirmedMemory = wanted;
        }

        return new EffectiveResourceEnvelope(confirmedCpu, confirmedMemory);
    }

    private boolean wasOomKilled(String containerName) {
        JsonNode state = inspect(containerName, "{{json .State}}");
        return state != null && state.path("OOMKilled").asBoolean(false);
    }

    /** Runs {@code docker inspect --format <format> <containerName>}, returning {@code null} rather
     *  than throwing when it fails — every caller here treats "could not read" and "nothing to
     *  read" as the same honest absence. */
    private JsonNode inspect(String containerName, String format) {
        try {
            Process process = new ProcessBuilder(dockerExecutable, "inspect", "--format", format,
                    containerName)
                    .redirectErrorStream(false)
                    .start();
            String output;
            try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                output = reader.lines().reduce("", (a, b) -> a + b);
            }
            if (!process.waitFor(DOCKER_COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0 || output.isBlank()) {
                return null;
            }
            return objectMapper.readTree(output);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Best-effort — logs a failure rather than letting cleanup mask the run's real outcome. */
    private void removeContainer(String containerName) {
        try {
            Process process = new ProcessBuilder(dockerExecutable, "rm", "-f", containerName)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(DOCKER_COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (IOException e) {
            log.warn("Could not remove Docker container {}: {}", containerName, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
