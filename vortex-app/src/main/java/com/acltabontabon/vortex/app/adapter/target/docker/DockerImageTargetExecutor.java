package com.acltabontabon.vortex.app.adapter.target.docker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.acltabontabon.vortex.core.application.PreflightCheck;
import com.acltabontabon.vortex.core.environment.TargetUrl;
import com.acltabontabon.vortex.core.execution.FailureReason;
import com.acltabontabon.vortex.core.port.TargetExecutor;
import com.acltabontabon.vortex.core.target.CleanupOutcome;
import com.acltabontabon.vortex.core.target.ContainerPort;
import com.acltabontabon.vortex.core.target.CpuAllocation;
import com.acltabontabon.vortex.core.target.DockerImageTarget;
import com.acltabontabon.vortex.core.target.EffectiveResourceEnvelope;
import com.acltabontabon.vortex.core.target.ExecutionTarget;
import com.acltabontabon.vortex.core.target.ImageReference;
import com.acltabontabon.vortex.core.target.MemoryAllocation;
import com.acltabontabon.vortex.core.target.PreparedTarget;
import com.acltabontabon.vortex.core.target.ReadinessCheck;
import com.acltabontabon.vortex.core.target.ResolvedTarget;
import com.acltabontabon.vortex.core.target.ResourceEnvelopeRequest;
import com.acltabontabon.vortex.core.target.TargetCapability;
import com.acltabontabon.vortex.core.target.TargetOwnership;
import com.acltabontabon.vortex.core.target.TargetPreparationException;
import com.acltabontabon.vortex.core.target.TargetPreparationRequest;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link TargetExecutor} for {@link DockerImageTarget} — one Vortex-managed, disposable
 * container built from an existing image.
 *
 * <p>{@link #prepare} is structured so that once {@code docker create} has returned a real
 * container id, every following step runs inside a single {@code try} whose {@code catch} removes
 * that container before translating and rethrowing — there is exactly one place a partial-creation
 * cleanup could be forgotten (the {@code try} boundary itself), rather than a per-branch discipline
 * every new step has to remember. See the plan's transactional-preparation design.
 *
 * <p>When a target requests CPU/memory, {@code createContainer} applies {@code --cpus}/{@code
 * --memory} at {@code docker create} time and, once the container has started, {@link
 * #confirmResourceEnvelope} re-reads the container's actual applied limits and compares them,
 * as exact integers, against what was requested — a mismatch (or a Docker inspect Vortex can't
 * read) fails preparation rather than reporting a limit that may not actually hold. Container
 * telemetry is a later step; this executor's job ends at producing a confirmed {@link
 * EffectiveResourceEnvelope} on the {@link ResolvedTarget} it returns.
 */
public final class DockerImageTargetExecutor implements TargetExecutor {

    private static final Logger log = LoggerFactory.getLogger(DockerImageTargetExecutor.class);

    /** Bound for {@code create}/{@code start}/{@code inspect}/{@code rm} — local CLI calls against
     *  an already-running daemon, not something that legitimately takes long. */
    private static final Duration DOCKER_COMMAND_TIMEOUT = Duration.ofSeconds(30);

    /** Readiness ceiling when a target configures no {@link ReadinessCheck} — only the mandatory
     *  TCP layer applies then, and an opened port typically follows moments after process start. */
    private static final Duration DEFAULT_READINESS_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration READINESS_POLL_INTERVAL = Duration.ofMillis(250);

    /** {@code docker image inspect} by tag is observed to intermittently answer "no such image" for
     *  an image that {@code docker images} and an id-based inspect both confirm is present — a
     *  Docker Desktop daemon flake, not a real absence. Retried briefly before it is trusted. */
    private static final Duration IMAGE_LOOKUP_RETRY_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration IMAGE_LOOKUP_RETRY_INTERVAL = Duration.ofMillis(500);

    private final String dockerExecutable;
    private final DockerProcess dockerProcess;
    private final DockerCapabilityProbe capabilityProbe;
    private final TargetReadinessProbe readinessProbe;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DockerImageTargetExecutor(String dockerExecutable, DockerProcess dockerProcess,
            DockerCapabilityProbe capabilityProbe, TargetReadinessProbe readinessProbe) {
        this.dockerExecutable = dockerExecutable == null || dockerExecutable.isBlank()
                ? "docker" : dockerExecutable.trim();
        this.dockerProcess = dockerProcess;
        this.capabilityProbe = capabilityProbe;
        this.readinessProbe = readinessProbe;
    }

    @Override
    public boolean supports(ExecutionTarget target) {
        return target instanceof DockerImageTarget;
    }

    @Override
    public Set<TargetCapability> capabilities() {
        return Set.of(TargetCapability.MANAGED_LIFECYCLE, TargetCapability.AUTOMATIC_PORT_MAPPING,
                TargetCapability.READINESS_CHECK, TargetCapability.RESOURCE_ENFORCEMENT);
    }

    @Override
    public PreparedTarget prepare(TargetPreparationRequest request) {
        DockerImageTarget target = (DockerImageTarget) request.target();

        requireDockerAndImageAvailable(target, request.statusSink());

        String containerId = createContainer(target, request);
        request.statusSink().accept("Container created");
        try {
            startContainer(containerId);
            request.statusSink().accept("Container started");

            EffectiveResourceEnvelope confirmedResources =
                    confirmResourceEnvelope(containerId, target.resources());
            if (confirmedResources != null) {
                request.statusSink().accept("Resource limits applied");
            }

            TargetUrl endpoint = resolveHostPort(containerId, target.containerPort());
            request.statusSink().accept("Resolved host port " + endpoint.port());

            awaitReadiness(endpoint, target.readinessCheckIfPresent());
            request.statusSink().accept("Target is ready");

            return preparedTarget(containerId, endpoint, confirmedResources);
        } catch (RuntimeException e) {
            removeContainer(containerId);
            if (e instanceof TargetPreparationException) {
                throw e;
            }
            throw new TargetPreparationException(FailureReason.CONTAINER_START_FAILED,
                    "Preparing the Docker target failed unexpectedly: " + e.getMessage());
        }
    }

    /**
     * The two non-mutating checks {@link #prepare} must clear before it creates anything — extracted
     * so {@link #checkAvailability} can reuse exactly this logic without ever reaching the
     * container-creating steps that follow it in {@link #prepare}.
     */
    private void requireDockerAndImageAvailable(DockerImageTarget target,
            java.util.function.Consumer<String> statusSink) {
        if (!capabilityProbe.isAvailable()) {
            throw new TargetPreparationException(FailureReason.DOCKER_UNAVAILABLE,
                    "Docker is not available on this machine. Install Docker Desktop, or Docker "
                            + "Engine on Linux, and make sure its daemon is running.");
        }
        statusSink.accept("Docker available");

        requireImageAvailable(target.image());
        statusSink.accept("Image found: " + target.image().value());
    }

    @Override
    public List<PreflightCheck> checkAvailability(ExecutionTarget target, String workspacePath) {
        DockerImageTarget dockerTarget = (DockerImageTarget) target;
        List<PreflightCheck> checks = new ArrayList<>();
        try {
            requireDockerAndImageAvailable(dockerTarget, message -> { });
            checks.add(PreflightCheck.pass("Docker available", "Docker is reachable on this machine."));
            checks.add(PreflightCheck.pass("Image available", dockerTarget.image().value()));
        } catch (TargetPreparationException e) {
            checks.add(PreflightCheck.fail(
                    e.reason() == FailureReason.DOCKER_UNAVAILABLE ? "Docker available" : "Image available",
                    e.getMessage(), e.reason().guidance()));
        }
        return checks;
    }

    private void requireImageAvailable(ImageReference image) {
        Instant deadline = Instant.now().plus(IMAGE_LOOKUP_RETRY_TIMEOUT);
        DockerProcess.DockerCommandResult result;
        while (true) {
            result = dockerProcess.run(
                    List.of(dockerExecutable, "image", "inspect", image.value()),
                    DOCKER_COMMAND_TIMEOUT);
            if (result.succeeded() || !Instant.now().isBefore(deadline)) {
                break;
            }
            try {
                Thread.sleep(IMAGE_LOOKUP_RETRY_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!result.succeeded()) {
            throw new TargetPreparationException(FailureReason.IMAGE_NOT_FOUND,
                    "Docker image not available: " + image.value());
        }
    }

    private String createContainer(DockerImageTarget target, TargetPreparationRequest request) {
        List<String> command = new ArrayList<>();
        command.add(dockerExecutable);
        command.add("create");
        command.add("--label");
        command.add("vortex.managed=true");
        command.add("--label");
        command.add("vortex.execution=" + request.executionId().value());
        command.add("--label");
        command.add("vortex.service=" + request.projectId().value());
        appendResourceFlags(command, target.resources());
        command.add("-p");
        command.add(String.valueOf(target.containerPort().value()));
        command.add(target.image().value());

        DockerProcess.DockerCommandResult result =
                dockerProcess.run(command, DOCKER_COMMAND_TIMEOUT);
        String containerId = result.firstStdoutLine().trim();
        if (!result.succeeded() || containerId.isEmpty()) {
            throw new TargetPreparationException(FailureReason.CONTAINER_START_FAILED,
                    "Docker could not create a container for image " + target.image().value()
                            + ". " + String.join(" ", result.stderr()));
        }
        return containerId;
    }

    /** Appends {@code --cpus}/{@code --memory} when {@code resources} requests them — omitted
     *  entirely when neither is present, so an unconstrained target's {@code docker create}
     *  command looks exactly as it did before this method existed. */
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
            // Docker's --memory accepts a bare integer as a plain byte count — no b/m/g suffix
            // needed.
            command.add("--memory");
            command.add(String.valueOf(memory.bytes()));
        });
    }

    private void startContainer(String containerId) {
        DockerProcess.DockerCommandResult result = dockerProcess.run(
                List.of(dockerExecutable, "start", containerId), DOCKER_COMMAND_TIMEOUT);
        if (!result.succeeded()) {
            throw new TargetPreparationException(FailureReason.CONTAINER_START_FAILED,
                    "Docker could not start container " + containerId + ". "
                            + String.join(" ", result.stderr()));
        }
    }

    private TargetUrl resolveHostPort(String containerId, ContainerPort containerPort) {
        DockerProcess.DockerCommandResult result = dockerProcess.run(List.of(dockerExecutable,
                "inspect", "--format", "{{json .NetworkSettings.Ports}}", containerId),
                DOCKER_COMMAND_TIMEOUT);
        if (!result.succeeded()) {
            throw new TargetPreparationException(FailureReason.PORT_RESOLUTION_FAILED,
                    "Docker could not report the port mapping for container " + containerId + ".");
        }

        String json = String.join("", result.stdout());
        JsonNode ports;
        try {
            ports = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new TargetPreparationException(FailureReason.PORT_RESOLUTION_FAILED,
                    "Docker's port mapping for container " + containerId
                            + " could not be parsed: " + e.getMessage());
        }

        JsonNode mapping = ports == null ? null : ports.get(containerPort.value() + "/tcp");
        if (mapping == null || !mapping.isArray() || mapping.isEmpty()) {
            throw new TargetPreparationException(FailureReason.PORT_RESOLUTION_FAILED,
                    "Docker did not report a host port mapped to container port "
                            + containerPort.value() + " for container " + containerId + ".");
        }

        String hostPort = mapping.get(0).path("HostPort").asText("");
        if (hostPort.isBlank()) {
            throw new TargetPreparationException(FailureReason.PORT_RESOLUTION_FAILED,
                    "Docker reported an empty host port for container " + containerId + ".");
        }

        return TargetUrl.of("http://localhost:" + hostPort);
    }

    /** Re-reads the container's actual applied CPU/memory limits and compares them, as exact
     *  integers, against what was requested — never floats or strings. Returns {@code null} when
     *  nothing was requested (nothing to confirm, so {@code EffectiveResourceEnvelope} stays
     *  absent on the resulting {@link ResolvedTarget}); throws {@link
     *  FailureReason#RESOURCE_LIMIT_APPLICATION_FAILED} on any mismatch, or if Docker's own report
     *  of the applied configuration can't be obtained or parsed. */
    private EffectiveResourceEnvelope confirmResourceEnvelope(String containerId,
            ResourceEnvelopeRequest requested) {
        if (requested.isEmpty()) {
            return null;
        }

        DockerProcess.DockerCommandResult result = dockerProcess.run(List.of(dockerExecutable,
                "inspect", "--format", "{{json .HostConfig}}", containerId), DOCKER_COMMAND_TIMEOUT);
        if (!result.succeeded()) {
            throw new TargetPreparationException(FailureReason.RESOURCE_LIMIT_APPLICATION_FAILED,
                    "Docker could not report the applied resource configuration for container "
                            + containerId + ".");
        }

        JsonNode hostConfig;
        try {
            hostConfig = objectMapper.readTree(String.join("", result.stdout()));
        } catch (JsonProcessingException e) {
            throw new TargetPreparationException(FailureReason.RESOURCE_LIMIT_APPLICATION_FAILED,
                    "Docker's resource configuration for container " + containerId
                            + " could not be parsed: " + e.getMessage());
        }

        CpuAllocation confirmedCpu = null;
        if (requested.cpuIfPresent().isPresent()) {
            CpuAllocation wanted = requested.cpuIfPresent().get();
            // NanoCpus is what --cpus stores (chosen over --cpu-quota/--cpu-period specifically
            // because it round-trips exactly — no period/quota rounding to reconcile), so integer
            // division by 1,000,000 recovers millicores exactly.
            long nanoCpus = hostConfig.path("NanoCpus").asLong(-1);
            int actualMillicores = (int) (nanoCpus / 1_000_000);
            if (nanoCpus < 0 || actualMillicores != wanted.millicores()) {
                throw new TargetPreparationException(FailureReason.RESOURCE_LIMIT_APPLICATION_FAILED,
                        "Requested a CPU limit of " + wanted.millicores() + "m but Docker reports "
                                + (nanoCpus < 0 ? "no limit applied" : actualMillicores + "m")
                                + " for container " + containerId + ".");
            }
            confirmedCpu = wanted;
        }

        MemoryAllocation confirmedMemory = null;
        if (requested.memoryIfPresent().isPresent()) {
            MemoryAllocation wanted = requested.memoryIfPresent().get();
            long actualBytes = hostConfig.path("Memory").asLong(-1);
            if (actualBytes != wanted.bytes()) {
                throw new TargetPreparationException(FailureReason.RESOURCE_LIMIT_APPLICATION_FAILED,
                        "Requested a memory limit of " + wanted.bytes() + " bytes but Docker "
                                + "reports " + actualBytes + " bytes for container " + containerId
                                + ".");
            }
            confirmedMemory = wanted;
        }

        return new EffectiveResourceEnvelope(confirmedCpu, confirmedMemory);
    }

    private void awaitReadiness(TargetUrl endpoint, Optional<ReadinessCheck> readinessCheck) {
        Duration timeout =
                readinessCheck.map(ReadinessCheck::timeout).orElse(DEFAULT_READINESS_TIMEOUT);

        boolean tcpReady = pollUntil(timeout, () -> readinessProbe.tcpPortIsReachable(
                endpoint.host(), endpoint.port(), READINESS_POLL_INTERVAL));
        if (!tcpReady) {
            throw new TargetPreparationException(FailureReason.TARGET_READINESS_TIMEOUT,
                    "The target at " + endpoint.value() + " did not accept a TCP connection within "
                            + timeout.toSeconds() + "s.");
        }

        if (readinessCheck.isPresent()) {
            ReadinessCheck check = readinessCheck.get();
            boolean httpReady = pollUntil(timeout, () -> readinessProbe.httpCheckSucceeds(
                    endpoint.host(), endpoint.port(), check.path(), check.expectedStatus(),
                    READINESS_POLL_INTERVAL));
            if (!httpReady) {
                throw new TargetPreparationException(FailureReason.TARGET_READINESS_TIMEOUT,
                        "The target at " + endpoint.value() + check.path()
                                + " did not return status " + check.expectedStatus() + " within "
                                + timeout.toSeconds() + "s.");
            }
        }
    }

    /** Polls {@code check} every {@link #READINESS_POLL_INTERVAL} until it succeeds or {@code
     *  timeout} elapses. */
    private boolean pollUntil(Duration timeout, BooleanSupplier check) {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            if (check.getAsBoolean()) {
                return true;
            }
            if (!Instant.now().isBefore(deadline)) {
                return false;
            }
            try {
                Thread.sleep(READINESS_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private PreparedTarget preparedTarget(String containerId, TargetUrl endpoint,
            EffectiveResourceEnvelope confirmedResources) {
        ResolvedTarget resolved = new ResolvedTarget(endpoint, TargetOwnership.VORTEX_MANAGED,
                containerId, confirmedResources);
        AtomicBoolean cleaned = new AtomicBoolean(false);

        return new PreparedTarget() {
            @Override
            public ResolvedTarget resolvedTarget() {
                return resolved;
            }

            @Override
            public CleanupOutcome cleanup() {
                if (!cleaned.compareAndSet(false, true)) {
                    return CleanupOutcome.NOTHING_TO_DO;
                }
                return removeContainerForCleanup(containerId);
            }
        };
    }

    /** Best-effort removal used while unwinding a failed preparation — logs a failure rather than
     *  letting it mask the real error that is already propagating. */
    private void removeContainer(String containerId) {
        CleanupOutcome outcome = removeContainerForCleanup(containerId);
        if (!outcome.succeeded()) {
            log.warn("Could not remove Docker container {} after a failed preparation: {}",
                    containerId, outcome.detail());
        }
    }

    private CleanupOutcome removeContainerForCleanup(String containerId) {
        try {
            DockerProcess.DockerCommandResult result = dockerProcess.run(
                    List.of(dockerExecutable, "rm", "-f", containerId), DOCKER_COMMAND_TIMEOUT);
            return result.succeeded()
                    ? new CleanupOutcome(true, true, "")
                    : new CleanupOutcome(true, false, "docker rm -f " + containerId + " exited "
                            + result.exitCode() + ": " + String.join(" ", result.stderr()));
        } catch (RuntimeException e) {
            return new CleanupOutcome(true, false,
                    "docker rm -f " + containerId + " failed: " + e.getMessage());
        }
    }
}
