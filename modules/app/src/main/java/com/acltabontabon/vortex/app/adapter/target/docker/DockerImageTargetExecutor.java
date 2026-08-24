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
import java.util.function.Predicate;
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

    /** How long to wait between readiness attempts. */
    private static final Duration READINESS_POLL_INTERVAL = Duration.ofMillis(250);

    /**
     * How long a single readiness attempt may take before it is abandoned and retried.
     *
     * <p>Deliberately not {@link #READINESS_POLL_INTERVAL}, which it used to be: passing the pause
     * between attempts as each attempt's own timeout capped every probe at 250ms, so a service whose
     * health endpoint took longer than that to answer could never be observed ready however generous
     * its configured timeout was. A starting JVM answering its first request on a fraction of a core
     * is precisely that service, which made the readiness check strictest exactly when the target was
     * slowest — the opposite of what it is for.
     */
    private static final Duration READINESS_PROBE_TIMEOUT = Duration.ofSeconds(5);

    /**
     * {@code docker image inspect} by tag is observed to answer "no such image" for an image that
     * {@code docker image ls} and an id-based inspect both confirm is present.
     *
     * <p>Originally treated as a brief flake and answered with the retry below. Observed since to
     * last <em>minutes</em>: every by-tag inspect in that window failed, while {@code docker image
     * ls}, {@code docker run} and an id-based inspect all resolved the same image throughout, and
     * the daemon eventually righted itself with no intervention. A three-second retry cannot bridge
     * a window that long, so the run is refused with "the configured image is not present locally"
     * about an image sitting right there.
     *
     * <p>The retry is kept for the short case it was written for, and {@link #imageIsPresent} no
     * longer asks only the one question that can be wrong for minutes at a time.
     */
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

    /**
     * Removes the containers this executor created for runs that have already ended.
     *
     * <p>Found by the {@code vortex.managed} label every {@link #createContainer} applies, and
     * attributed by the {@code vortex.execution} label beside it — the labels exist for exactly this,
     * and until now nothing ever read them back. Asking Docker rather than remembering across
     * restarts is the point: the process that could have remembered is the one that died.
     *
     * <p>Anything Docker reports without a readable execution label is removed too. It carries
     * {@code vortex.managed=true}, so Vortex created it; a container Vortex created and cannot
     * attribute to any run is orphaned by definition.
     */
    @Override
    public List<String> releaseOrphans(Set<String> liveExecutionIds) {
        DockerProcess.DockerCommandResult listed = dockerProcess.run(List.of(dockerExecutable, "ps",
                        "--all", "--filter", "label=vortex.managed=true",
                        "--format", "{{.ID}} {{.Label \"vortex.execution\"}}"),
                DOCKER_COMMAND_TIMEOUT);
        if (!listed.succeeded()) {
            // Not an error worth failing start-up over: Docker may simply not be running on a machine
            // whose runs all target an external endpoint, in which case there is nothing to sweep.
            log.debug("Could not list Vortex-managed containers: {}",
                    String.join(" ", listed.stderr()));
            return List.of();
        }

        List<String> released = new ArrayList<>();
        for (String line : listed.stdout()) {
            String[] fields = line.trim().split("\\s+", 2);
            String containerId = fields[0];
            if (containerId.isEmpty()) {
                continue;
            }
            String executionId = fields.length > 1 ? fields[1].trim() : "";
            if (liveExecutionIds.contains(executionId)) {
                continue;
            }
            CleanupOutcome outcome = removeContainerForCleanup(containerId);
            if (outcome.succeeded()) {
                released.add("container " + containerId + (executionId.isEmpty()
                        ? " (no run recorded)" : " from run " + executionId));
            } else {
                log.warn("Could not remove orphaned Docker container {}: {}",
                        containerId, outcome.detail());
            }
        }
        return List.copyOf(released);
    }

    private void requireImageAvailable(ImageReference image) {
        Instant deadline = Instant.now().plus(IMAGE_LOOKUP_RETRY_TIMEOUT);
        while (true) {
            if (imageIsPresent(image)) {
                return;
            }
            if (!Instant.now().isBefore(deadline)) {
                throw new TargetPreparationException(FailureReason.IMAGE_NOT_FOUND,
                        "Docker image not available: " + image.value());
            }
            try {
                Thread.sleep(IMAGE_LOOKUP_RETRY_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TargetPreparationException(FailureReason.IMAGE_NOT_FOUND,
                        "Docker image not available: " + image.value());
            }
        }
    }

    /**
     * Whether Docker holds this image, asked two ways because one of them can be wrong.
     *
     * <p>{@code image inspect} is asked first: it is the exact question, and it is the only one of
     * the two that understands a digest reference ({@code image@sha256:…}), which {@code --filter
     * reference=} does not match. When it says no, {@code image ls --filter reference=} is asked as
     * well, because a daemon whose tag index is temporarily confused answers the first question
     * wrongly and this one correctly — the observed failure being an image that {@code docker image
     * ls} lists, {@code docker run} runs, and an id-based inspect resolves, while a by-tag inspect
     * insists for minutes on end that it does not exist. Believing the first answer alone refuses
     * the run over an image that is right there.
     *
     * <p>Only a positive second answer overturns the first. Both saying no is a genuine absence, and
     * an image Vortex cannot find is still refused rather than attempted — Vortex never pulls or
     * builds on somebody's behalf, so a wrong yes here becomes a confusing {@code docker create}
     * failure instead of a clear one.
     */
    private boolean imageIsPresent(ImageReference image) {
        boolean inspected = dockerProcess.run(
                List.of(dockerExecutable, "image", "inspect", image.value()),
                DOCKER_COMMAND_TIMEOUT).succeeded();
        if (inspected || !isLiteralReference(image)) {
            return inspected;
        }
        DockerProcess.DockerCommandResult listed = dockerProcess.run(List.of(dockerExecutable,
                "image", "ls", "--filter", "reference=" + image.value(), "--format", "{{.ID}}"),
                DOCKER_COMMAND_TIMEOUT);
        // This command exits 0 whether or not anything matched, so presence is the output, not the
        // status — an empty listing is the honest "no", not a failure to ask.
        return listed.succeeded() && listed.stdout().stream().anyMatch(line -> !line.isBlank());
    }

    /**
     * Whether this reference names one image rather than describing a set of them.
     *
     * <p>{@code --filter reference=} matches a shell-style pattern, so {@code myservice:*} lists
     * every tag of {@code myservice} and would answer "present" for a reference {@code docker create}
     * then rejects outright as an invalid reference format. That turns a clear "this image is not
     * available, pull or build it" into an obscure failure one step later, which is the opposite of
     * why the second question is asked at all. A pattern is therefore left to {@code inspect} alone,
     * which correctly refuses it.
     */
    private boolean isLiteralReference(ImageReference image) {
        return image.value().chars().noneMatch(c -> c == '*' || c == '?' || c == '[');
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

        boolean tcpReady = pollUntil(timeout, attempt -> readinessProbe.tcpPortIsReachable(
                endpoint.host(), endpoint.port(), attempt));
        if (!tcpReady) {
            throw new TargetPreparationException(FailureReason.TARGET_READINESS_TIMEOUT,
                    "The container for this target started, but nothing was listening on "
                            + endpoint.value() + " within " + timeout.toSeconds() + "s. Either the "
                            + "service inside the container failed to start — check its own logs "
                            + "with `docker logs` — or it listens on a different port than the one "
                            + "this environment declares.");
        }

        if (readinessCheck.isPresent()) {
            ReadinessCheck check = readinessCheck.get();
            boolean httpReady = pollUntil(timeout, attempt -> readinessProbe.httpCheckSucceeds(
                    endpoint.host(), endpoint.port(), check.path(), check.expectedStatus(), attempt));
            if (!httpReady) {
                // Separated from the TCP message on purpose: reaching here means the container is up
                // and holding its port, so the service is starting and simply was not finished. That
                // is a timeout to raise, not a target to debug — and the two failures were previously
                // worded closely enough to be read as the same problem.
                throw new TargetPreparationException(FailureReason.TARGET_READINESS_TIMEOUT,
                        "The container for this target is running and accepting connections, but "
                                + endpoint.value() + check.path() + " had not returned status "
                                + check.expectedStatus() + " after " + timeout.toSeconds() + "s — it "
                                + "was still starting up. Raise this environment's readiness timeout: "
                                + "a JVM service on a fraction of a CPU core routinely needs 30s or "
                                + "more before it serves its first request.");
            }
        }
    }

    /**
     * Polls {@code attempt} every {@link #READINESS_POLL_INTERVAL} until it succeeds or {@code
     * timeout} elapses, handing each attempt its own timeout rather than letting it run unbounded
     * past the deadline it is racing.
     */
    private boolean pollUntil(Duration timeout, Predicate<Duration> attempt) {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            if (attempt.test(probeBudget(deadline))) {
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

    /** One attempt's own timeout: {@link #READINESS_PROBE_TIMEOUT}, shortened to whatever is left of
     *  the overall budget when that is less, so a single slow probe cannot overrun the deadline it is
     *  being measured against. Floored at the poll interval, so the last attempt before a deadline is
     *  still a real attempt rather than one guaranteed to time out instantly. */
    private static Duration probeBudget(Instant deadline) {
        Duration remaining = Duration.between(Instant.now(), deadline);
        if (remaining.compareTo(READINESS_POLL_INTERVAL) < 0) {
            return READINESS_POLL_INTERVAL;
        }
        return remaining.compareTo(READINESS_PROBE_TIMEOUT) < 0 ? remaining : READINESS_PROBE_TIMEOUT;
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
