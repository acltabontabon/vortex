package dev.vortex.app.adapter.target.docker;

import dev.vortex.core.application.PreflightCheck;
import dev.vortex.core.environment.TargetUrl;
import dev.vortex.core.execution.FailureReason;
import dev.vortex.core.lab.ComposeFileReference;
import dev.vortex.core.port.TargetExecutor;
import dev.vortex.core.target.CleanupOutcome;
import dev.vortex.core.target.DockerComposeTarget;
import dev.vortex.core.target.ExecutionTarget;
import dev.vortex.core.target.PreparedTarget;
import dev.vortex.core.target.ResolvedTarget;
import dev.vortex.core.target.TargetCapability;
import dev.vortex.core.target.TargetOwnership;
import dev.vortex.core.target.TargetPreparationException;
import dev.vortex.core.target.TargetPreparationRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The {@link TargetExecutor} for {@link DockerComposeTarget} — attach-only. Vortex never runs
 * {@code docker compose up}/{@code down}/{@code rm} anywhere in this class, and never will: every
 * method here either reads Compose's own reported state through one of three narrow, stable
 * subcommands ({@code config --services}, {@code ps --services --status running}, {@code port}) or
 * refuses to proceed. {@link #cleanup} on the lease this returns is unconditionally {@link
 * CleanupOutcome#NOTHING_TO_DO} — there is no code path in this class capable of constructing a
 * teardown command, which is the single most important property of this executor and is covered by
 * a dedicated test asserting exactly that against every scenario, success and failure alike.
 *
 * <p>No Compose YAML topology is ever parsed here. Every fact this executor needs — which services a
 * file declares, which of those are currently running, and the host port Compose already assigned —
 * comes from Compose's own command output, never from reading the {@code .yaml} content directly.
 */
public final class DockerComposeTargetExecutor implements TargetExecutor {

    /** Bound for the three narrow {@code docker compose} subcommands this class issues — local CLI
     *  calls against an already-running daemon, not something that legitimately takes long. */
    private static final Duration DOCKER_COMMAND_TIMEOUT = Duration.ofSeconds(30);

    private final String dockerExecutable;
    private final DockerProcess dockerProcess;

    public DockerComposeTargetExecutor(String dockerExecutable, DockerProcess dockerProcess) {
        this.dockerExecutable = dockerExecutable == null || dockerExecutable.isBlank()
                ? "docker" : dockerExecutable.trim();
        this.dockerProcess = dockerProcess;
    }

    @Override
    public boolean supports(ExecutionTarget target) {
        return target instanceof DockerComposeTarget;
    }

    /**
     * {@code RESOURCE_OBSERVATION} only, and not implemented yet even for that — declared for future
     * extension, per the plan's v1 scope. No {@code MANAGED_LIFECYCLE} (Vortex never owns this
     * stack's lifecycle), no {@code RESOURCE_ENFORCEMENT} (Vortex never applies limits to a Compose
     * service), no {@code AUTOMATIC_PORT_MAPPING} (this executor reads the port Compose already
     * assigned, it does not map one itself), no {@code READINESS_CHECK} (attach-only: if the service
     * is not already running and ready, that is {@link FailureReason#COMPOSE_SERVICE_NOT_RUNNING},
     * not something this executor waits out).
     */
    @Override
    public Set<TargetCapability> capabilities() {
        return Set.of(TargetCapability.RESOURCE_OBSERVATION);
    }

    @Override
    public PreparedTarget prepare(TargetPreparationRequest request) {
        DockerComposeTarget target = (DockerComposeTarget) request.target();

        Path composeFilePath =
                resolveComposeService(target, request.workspacePath(), request.statusSink());

        TargetUrl endpoint = resolvePort(composeFilePath, target);
        request.statusSink().accept("Resolved port " + endpoint.port());

        ResolvedTarget resolved = new ResolvedTarget(endpoint, TargetOwnership.EXTERNAL, "", null);
        return new PreparedTarget() {
            @Override
            public ResolvedTarget resolvedTarget() {
                return resolved;
            }

            /** Unconditionally, always. This class never creates or starts anything, so there is
             *  never anything to release — see the class Javadoc. */
            @Override
            public CleanupOutcome cleanup() {
                return CleanupOutcome.NOTHING_TO_DO;
            }
        };
    }

    /**
     * The three non-mutating resolution stages shared by {@link #prepare} and {@link
     * #checkAvailability}: the Compose file exists, the target's service is declared in it, and that
     * service is currently running. Never resolves a port — that is {@link #prepare}'s own next step,
     * not part of what either caller here needs to confirm readiness.
     *
     * @throws TargetPreparationException at whichever stage fails first, with the {@link
     *                                     FailureReason} identifying which one
     */
    private Path resolveComposeService(DockerComposeTarget target, String workspacePath,
            Consumer<String> statusSink) {
        Path composeFilePath = ComposeFileReference.resolveAgainst(target.composeFile(), workspacePath);
        if (!Files.isRegularFile(composeFilePath)) {
            throw new TargetPreparationException(FailureReason.COMPOSE_FILE_NOT_FOUND,
                    "Compose file not found: " + composeFilePath);
        }
        statusSink.accept("Compose file found");

        Set<String> declaredServices = declaredServices(composeFilePath);
        if (!declaredServices.contains(target.serviceName())) {
            throw new TargetPreparationException(FailureReason.COMPOSE_SERVICE_NOT_FOUND,
                    "Service '" + target.serviceName() + "' is not declared in " + composeFilePath
                            + ". Declared services: " + declaredServices);
        }
        statusSink.accept("Service declared: " + target.serviceName());

        Set<String> runningServices = runningServices(composeFilePath);
        if (!runningServices.contains(target.serviceName())) {
            throw new TargetPreparationException(FailureReason.COMPOSE_SERVICE_NOT_RUNNING,
                    "Service '" + target.serviceName() + "' is declared in " + composeFilePath
                            + " but is not currently running. Vortex attaches to an already-running "
                            + "Compose service — start the stack yourself, then try again.");
        }
        statusSink.accept("Service running: " + target.serviceName());

        return composeFilePath;
    }

    /**
     * Runs the same three-stage resolution {@link #prepare} does — file found, service declared,
     * service running — never the port resolution or anything after it, and reports each stage as
     * its own {@link PreflightCheck} rather than one pass/fail verdict, so a form showing "Test
     * Connection" results can point at exactly which stage failed. A stage after the one that failed
     * is reported {@link PreflightCheck#skipped(String, String) skipped}, not silently absent.
     */
    @Override
    public List<PreflightCheck> checkAvailability(ExecutionTarget target, String workspacePath) {
        DockerComposeTarget composeTarget = (DockerComposeTarget) target;
        String serviceCheckName = composeTarget.serviceName() + " found";
        List<PreflightCheck> checks = new ArrayList<>();
        try {
            resolveComposeService(composeTarget, workspacePath, message -> { });
            checks.add(PreflightCheck.pass("Compose file found", composeTarget.composeFile()));
            checks.add(PreflightCheck.pass(serviceCheckName, composeTarget.serviceName()));
            checks.add(PreflightCheck.pass("Service running", composeTarget.serviceName()));
        } catch (TargetPreparationException e) {
            switch (e.reason()) {
                case COMPOSE_FILE_NOT_FOUND -> {
                    checks.add(PreflightCheck.fail("Compose file found", e.getMessage(),
                            e.reason().guidance()));
                    checks.add(PreflightCheck.skipped(serviceCheckName,
                            "Not checked — Compose file found failed."));
                    checks.add(PreflightCheck.skipped("Service running",
                            "Not checked — Compose file found failed."));
                }
                case COMPOSE_SERVICE_NOT_FOUND -> {
                    checks.add(PreflightCheck.pass("Compose file found", composeTarget.composeFile()));
                    checks.add(PreflightCheck.fail(serviceCheckName, e.getMessage(),
                            e.reason().guidance()));
                    checks.add(PreflightCheck.skipped("Service running",
                            "Not checked — " + serviceCheckName + " failed."));
                }
                case COMPOSE_SERVICE_NOT_RUNNING -> {
                    checks.add(PreflightCheck.pass("Compose file found", composeTarget.composeFile()));
                    checks.add(PreflightCheck.pass(serviceCheckName, composeTarget.serviceName()));
                    checks.add(PreflightCheck.fail("Service running", e.getMessage(),
                            e.reason().guidance()));
                }
                default -> throw e;
            }
        }
        return checks;
    }

    /**
     * Every service the Compose file declares, regardless of whether it is currently running.
     *
     * <p>A non-zero exit here — malformed YAML, or Docker itself unavailable — is reported as {@link
     * FailureReason#COMPOSE_FILE_NOT_FOUND} rather than a fourth failure reason: from the caller's
     * perspective, "the compose file doesn't give us anything to work with" is the same class of
     * problem whether the file is missing or simply unusable as Compose input, and the file's
     * presence has already been confirmed by the time this runs.
     */
    private Set<String> declaredServices(Path composeFilePath) {
        DockerProcess.DockerCommandResult result = dockerProcess.run(
                List.of(dockerExecutable, "compose", "-f", composeFilePath.toString(),
                        "config", "--services"),
                DOCKER_COMMAND_TIMEOUT);
        if (!result.succeeded()) {
            throw new TargetPreparationException(FailureReason.COMPOSE_FILE_NOT_FOUND,
                    "'" + composeFilePath + "' could not be read as a usable Compose file: "
                            + String.join(" ", result.stderr()));
        }
        return serviceNames(result.stdout());
    }

    /**
     * The subset of the Compose project's services that are currently running.
     *
     * <p>Same reasoning as {@link #declaredServices}: a non-zero exit this late means whatever
     * confirmed the file was usable a moment ago no longer holds (the daemon dropped, for instance),
     * which is the same "nothing to work with" class of problem — reused rather than invented anew.
     */
    private Set<String> runningServices(Path composeFilePath) {
        DockerProcess.DockerCommandResult result = dockerProcess.run(
                List.of(dockerExecutable, "compose", "-f", composeFilePath.toString(),
                        "ps", "--services", "--status", "running"),
                DOCKER_COMMAND_TIMEOUT);
        if (!result.succeeded()) {
            throw new TargetPreparationException(FailureReason.COMPOSE_FILE_NOT_FOUND,
                    "'" + composeFilePath + "' could not be read as a usable Compose file: "
                            + String.join(" ", result.stderr()));
        }
        return serviceNames(result.stdout());
    }

    private Set<String> serviceNames(List<String> lines) {
        Set<String> names = new LinkedHashSet<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return names;
    }

    /**
     * The host address Compose already assigned to this service's configured container port.
     *
     * <p>{@code docker compose port} reports {@code host:port}, one line, for example {@code
     * 0.0.0.0:32768}. The host half is discarded and the result is always built against {@code
     * localhost} — the same convention {@link DockerImageTargetExecutor#resolveHostPort} already
     * uses for a Vortex-managed container's own port mapping, kept identical here rather than
     * introducing a second rule for what "reachable from this machine" means.
     */
    private TargetUrl resolvePort(Path composeFilePath, DockerComposeTarget target) {
        DockerProcess.DockerCommandResult result = dockerProcess.run(
                List.of(dockerExecutable, "compose", "-f", composeFilePath.toString(),
                        "port", target.serviceName(), String.valueOf(target.containerPort().value())),
                DOCKER_COMMAND_TIMEOUT);
        if (!result.succeeded() || result.stdout().isEmpty()) {
            throw new TargetPreparationException(FailureReason.PORT_RESOLUTION_FAILED,
                    "Docker Compose did not report a host port for service '" + target.serviceName()
                            + "' container port " + target.containerPort().value() + ".");
        }

        String reported = result.firstStdoutLine().strip();
        int separator = reported.lastIndexOf(':');
        String portText = separator < 0 ? reported : reported.substring(separator + 1);
        if (portText.isBlank() || !portText.chars().allMatch(Character::isDigit)) {
            throw new TargetPreparationException(FailureReason.PORT_RESOLUTION_FAILED,
                    "Docker Compose reported an unparseable port mapping for service '"
                            + target.serviceName() + "': '" + reported + "'.");
        }

        return TargetUrl.of("http://localhost:" + portText);
    }
}
