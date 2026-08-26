package com.acltabontabon.vortex.app.web;

import com.acltabontabon.vortex.app.service.LocalLabRunner;
import com.acltabontabon.vortex.app.web.ConfigurationDtos.CatalogDto;
import com.acltabontabon.vortex.app.web.ConfigurationDtos.ConfigurationDto;
import com.acltabontabon.vortex.app.web.ConfigurationDtos.ConfigurationFileDto;
import com.acltabontabon.vortex.app.web.ConfigurationDtos.DependencyModeOptionDto;
import com.acltabontabon.vortex.app.web.ConfigurationDtos.EnvironmentDto;
import com.acltabontabon.vortex.app.web.ConfigurationDtos.EnvironmentTypeOptionDto;
import com.acltabontabon.vortex.app.web.ConfigurationDtos.ExecutionTargetSummaryDto;
import com.acltabontabon.vortex.app.web.ConfigurationDtos.FetchAndSaveProductionResponse;
import com.acltabontabon.vortex.app.web.ConfigurationDtos.FetchProductionResponse;
import com.acltabontabon.vortex.app.web.ConfigurationDtos.LabActivityDto;
import com.acltabontabon.vortex.app.web.ConfigurationDtos.LabStatusDto;
import com.acltabontabon.vortex.app.web.ConfigurationDtos.LocalLabDto;
import com.acltabontabon.vortex.app.web.ConfigurationDtos.ObservationSourceDto;
import com.acltabontabon.vortex.app.web.ConfigurationDtos.OperationDto;
import com.acltabontabon.vortex.app.web.ConfigurationDtos.TargetValidationResponse;
import com.acltabontabon.vortex.app.web.ConfigurationDtos.TestConnectionResponse;
import com.acltabontabon.vortex.app.web.ConfigurationDtos.ThresholdEditDto;
import com.acltabontabon.vortex.app.web.ConfigurationDtos.WorkloadSuggestionDto;
import com.acltabontabon.vortex.core.application.CalibrationService;
import com.acltabontabon.vortex.core.application.CatalogImportService;
import com.acltabontabon.vortex.core.application.PreflightCheck;
import com.acltabontabon.vortex.core.application.ProjectService;
import com.acltabontabon.vortex.core.calibration.CalibrationPolicy;
import com.acltabontabon.vortex.core.capacity.ObservationSource;
import com.acltabontabon.vortex.core.capacity.ProductionObservation;
import com.acltabontabon.vortex.core.catalog.Operation;
import com.acltabontabon.vortex.core.catalog.ServiceCatalog;
import com.acltabontabon.vortex.core.environment.DependencyMode;
import com.acltabontabon.vortex.core.environment.Environment;
import com.acltabontabon.vortex.core.environment.EnvironmentCapabilities;
import com.acltabontabon.vortex.core.environment.EnvironmentType;
import com.acltabontabon.vortex.core.environment.SecretReferences;
import com.acltabontabon.vortex.core.environment.TargetUrl;
import com.acltabontabon.vortex.core.lab.LocalLabSettings;
import com.acltabontabon.vortex.core.target.ContainerPort;
import com.acltabontabon.vortex.core.target.CpuAllocation;
import com.acltabontabon.vortex.core.target.DockerComposeTarget;
import com.acltabontabon.vortex.core.target.DockerImageTarget;
import com.acltabontabon.vortex.core.target.ExecutionTarget;
import com.acltabontabon.vortex.core.target.ExternalEndpointTarget;
import com.acltabontabon.vortex.core.target.ImageReference;
import com.acltabontabon.vortex.core.target.MemoryAllocation;
import com.acltabontabon.vortex.core.target.ReadinessCheck;
import com.acltabontabon.vortex.core.target.ResourceEnvelopeRequest;
import com.acltabontabon.vortex.core.target.TargetOwnership;
import com.acltabontabon.vortex.core.port.LocalLab;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.NotRetrieved;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.Retrieved;
import com.acltabontabon.vortex.core.port.ServiceCatalogImporter;
import com.acltabontabon.vortex.core.port.TargetExecutor;
import com.acltabontabon.vortex.core.project.Project;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.shared.EnvironmentId;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.shared.Percentile;
import com.acltabontabon.vortex.core.threshold.Durations;
import com.acltabontabon.vortex.core.threshold.ErrorRateThreshold;
import com.acltabontabon.vortex.core.threshold.LatencyThreshold;
import com.acltabontabon.vortex.core.threshold.Threshold;
import com.acltabontabon.vortex.core.threshold.ThresholdScope;
import com.acltabontabon.vortex.core.threshold.ThresholdSet;
import com.acltabontabon.vortex.core.workload.OperationMix;
import com.acltabontabon.vortex.core.workload.Observation;
import com.acltabontabon.vortex.core.workload.WeightedOperation;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Configuration: what Vortex currently knows about a service, and every form that changes it.
 *
 * <p>Consolidates what was Understand's eight Thymeleaf sections — environments, local lab,
 * release, production traffic, observation source, objectives, the committed YAML preview, and
 * operations/review — behind one page. Mirrors each Thymeleaf-era {@code ProjectController} and
 * {@code LocalLabController} method exactly: same validation, same domain calls, same messages.
 * Only the wire shape at the edge is new.
 *
 * <p>Every mutation here saves and returns a small outcome; the page refetches the whole
 * {@link #configuration} read afterwards rather than each action returning its own partial shape —
 * a full round trip is cheap for a local tool, and it is the one place all eight sections'
 * cross-dependencies (e.g. a new environment changing readiness) are guaranteed consistent.
 */
@RestController
@RequestMapping("/api/services/{id}")
public class ConfigurationApiController {

    /** Guards against pointing the importer at something enormous. Mirrors ProjectController. */
    private static final int MAX_SPECIFICATION_BYTES = 8 * 1024 * 1024;

    private final ProjectService projects;
    private final CatalogImportService catalogs;
    private final CalibrationPolicy calibration;
    private final CalibrationService calibrationService;
    private final LocalLabRunner lab;
    private final WorkspaceAssembler assembler;
    private final List<TargetExecutor> targetExecutors;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** What {@code /production/fetch} most recently retrieved for a service, so
     *  {@code /production/fetch-and-save} can persist exactly that instead of querying the
     *  observation source a second time — see the Javadoc on that method. */
    private final Map<ProjectId, Retrieved> lastFetchedProduction = new ConcurrentHashMap<>();

    public ConfigurationApiController(ProjectService projects, CatalogImportService catalogs,
            CalibrationPolicy calibration, CalibrationService calibrationService,
            LocalLabRunner lab, WorkspaceAssembler assembler, List<TargetExecutor> targetExecutors) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.calibration = Objects.requireNonNull(calibration, "calibration");
        this.calibrationService = Objects.requireNonNull(calibrationService, "calibrationService");
        this.lab = Objects.requireNonNull(lab, "lab");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.targetExecutors = List.copyOf(Objects.requireNonNull(targetExecutors, "targetExecutors"));
    }

    // ==================================================================== the read

    @GetMapping("/configuration")
    public ConfigurationDto configuration(@PathVariable String id) {
        ProjectId projectId = ProjectId.of(id);
        Project project = projects.find(projectId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No service with id " + id));
        ProjectConfiguration configuration = projects.configuration(projectId);
        ServiceCatalog catalog = projects.catalog(projectId).orElse(null);

        return new ConfigurationDto(
                configuration.serviceVersionIfPresent().orElse(null),
                configuration.environments().stream().map(this::toDto).toList(),
                environmentTypeOptions(),
                dependencyModeOptions(),
                localLab(project, configuration),
                assembler.production(configuration.productionObservation(), catalog),
                calibrationSuggestions(configuration),
                configuration.observationSourceIfPresent().map(this::toDto).orElse(null),
                thresholds(configuration),
                catalogDto(configuration, catalog),
                configurationFile(project, projectId));
    }

    private List<EnvironmentTypeOptionDto> environmentTypeOptions() {
        return List.of(EnvironmentType.values()).stream()
                .map(type -> new EnvironmentTypeOptionDto(type.name(), type.label(), type.description()))
                .toList();
    }

    private List<DependencyModeOptionDto> dependencyModeOptions() {
        return List.of(DependencyMode.values()).stream()
                .map(mode -> new DependencyModeOptionDto(mode.name(), mode.label(), mode.description()))
                .toList();
    }

    private EnvironmentDto toDto(Environment environment) {
        // Only an ExternalEndpointTarget has a genuine pre-run address; a Docker/Compose target
        // reports no baseUrl rather than a manufactured one.
        String baseUrl = environment.target() instanceof ExternalEndpointTarget endpoint
                ? endpoint.endpoint().value() : null;
        TargetDetail detail = targetDetail(environment.target());
        return new EnvironmentDto(
                environment.name(),
                baseUrl,
                environment.type().name(),
                environment.type().label(),
                environment.dependencyMode().name(),
                environment.dependencyMode().label(),
                environment.classification().name(),
                environment.classification().label(),
                environment.classification().caveat(),
                environment.hasSecretReferences(),
                environment.headerNames(),
                targetSummary(environment.target()),
                environment.capabilities().productionLikeInfrastructure(),
                detail.image, detail.containerPort, detail.cpuMillicores, detail.memoryMebibytes,
                detail.readinessPath, detail.readinessExpectedStatus, detail.readinessTimeoutSeconds,
                detail.composeFile, detail.composeService);
    }

    /**
     * The structured fields {@link EnvironmentRequest} accepts on write, read back off whichever
     * {@link ExecutionTarget} variant this environment actually has — {@code null} for every field
     * that variant doesn't carry. Exists so an edit form can prefill exactly what it would submit,
     * the read side of {@link #targetFrom}.
     */
    private TargetDetail targetDetail(ExecutionTarget target) {
        return switch (target) {
            case ExternalEndpointTarget ignored -> TargetDetail.EMPTY;
            case DockerImageTarget image -> new TargetDetail(
                    image.image().value(), image.containerPort().value(),
                    image.resources().cpuIfPresent().map(CpuAllocation::millicores).orElse(null),
                    image.resources().memoryIfPresent()
                            .map(m -> m.bytes() / (1024L * 1024L)).orElse(null),
                    image.readinessCheckIfPresent().map(ReadinessCheck::path).orElse(null),
                    image.readinessCheckIfPresent().map(ReadinessCheck::expectedStatus).orElse(null),
                    image.readinessCheckIfPresent()
                            .map(r -> (int) r.timeout().toSeconds()).orElse(null),
                    null, null);
            case DockerComposeTarget compose -> new TargetDetail(
                    null, compose.containerPort().value(), null, null, null, null, null,
                    compose.composeFile(), compose.serviceName());
        };
    }

    private record TargetDetail(String image, Integer containerPort, Integer cpuMillicores,
            Long memoryMebibytes, String readinessPath, Integer readinessExpectedStatus,
            Integer readinessTimeoutSeconds, String composeFile, String composeService) {
        static final TargetDetail EMPTY =
                new TargetDetail(null, null, null, null, null, null, null, null, null);
    }

    /** {@code kind} matches the wire vocabulary shared with {@code EnvironmentRequest.targetKind}
     *  and {@code vortex.yaml}'s {@code target.kind}. */
    private ExecutionTargetSummaryDto targetSummary(ExecutionTarget target) {
        String kind = switch (target) {
            case ExternalEndpointTarget ignored -> "EXTERNAL_ENDPOINT";
            case DockerImageTarget ignored -> "DOCKER_IMAGE";
            case DockerComposeTarget ignored -> "DOCKER_COMPOSE";
        };
        String ownershipLabel = target.ownership() == TargetOwnership.VORTEX_MANAGED
                ? "Vortex managed" : "Externally managed";
        return new ExecutionTargetSummaryDto(kind, target.summary(), ownershipLabel);
    }

    private List<WorkloadSuggestionDto> calibrationSuggestions(ProjectConfiguration configuration) {
        return configuration.productionObservationIfPresent()
                .map(calibration::propose).orElseGet(List::of).stream()
                .map(s -> new WorkloadSuggestionDto(s.name(), s.rate().display(), s.derivation()))
                .toList();
    }

    private ObservationSourceDto toDto(ObservationSource source) {
        return new ObservationSourceDto(source.kind().name(), source.transport().name(),
                source.endpoint(), source.serviceIdentifier(), Durations.display(source.window()),
                maskedHeaders(source));
    }

    private Map<String, String> maskedHeaders(ObservationSource source) {
        Map<String, String> masked = new LinkedHashMap<>();
        source.headers().forEach((key, value) -> masked.put(key, SecretReferences.mask(value)));
        return masked;
    }

    private ThresholdEditDto thresholds(ProjectConfiguration configuration) {
        ThresholdSet set = configuration.thresholds();
        Long p95 = null;
        Long p99 = null;
        Double errorPercent = null;
        for (Threshold threshold : set.thresholds()) {
            if (threshold instanceof LatencyThreshold latency && latency.scope().equals(ThresholdScope.OVERALL)) {
                if (latency.percentile().equals(Percentile.P95)) {
                    p95 = latency.maximum().toMillis();
                } else if (latency.percentile().equals(Percentile.P99)) {
                    p99 = latency.maximum().toMillis();
                }
            } else if (threshold instanceof ErrorRateThreshold errorRate
                    && errorRate.scope().equals(ThresholdScope.OVERALL)) {
                errorPercent = errorRate.maximum().asPercent();
            }
        }
        return new ThresholdEditDto(p95, p99, errorPercent,
                set.thresholds().stream().map(Threshold::describe).toList());
    }

    private ConfigurationFileDto configurationFile(Project project, ProjectId projectId) {
        String yaml = projects.renderConfiguration(projectId);
        String path = project.workspacePathIfPresent().map(p -> p + "/.vortex/vortex.yaml").orElse(null);
        return new ConfigurationFileDto(yaml, path);
    }

    private CatalogDto catalogDto(ProjectConfiguration configuration, ServiceCatalog catalog) {
        if (catalog == null) {
            return new CatalogDto(false, null, null, 0, 0, List.of());
        }
        List<OperationDto> operations = catalog.operations().stream()
                .map(op -> {
                    boolean reviewed = configuration.binding(op.id()).map(b -> b.reviewed()).orElse(false);
                    return new OperationDto(op.id().value(), op.method().name(), op.path(), op.summary(),
                            op.primaryTag(), op.kind().name(), op.requiresReview(), reviewed);
                })
                .toList();
        return new CatalogDto(true, catalog.title(), catalog.sourceRef(), catalog.operationCount(),
                catalog.mutatingOperations().size(), operations);
    }

    private LocalLabDto localLab(Project project, ProjectConfiguration configuration) {
        LocalLabSettings settings = configuration.localLabIfPresent().orElse(null);
        if (settings == null) {
            return new LocalLabDto(false, null, toDto(lab.status()), false, null);
        }
        ProjectId projectId = project.id();
        boolean running = lab.isRunning(projectId);
        LabActivityDto activityDto = lab.activity(projectId).map(this::toDto).orElse(null);
        return new LocalLabDto(true, settings.describe(), toDto(lab.status()), running, activityDto);
    }

    private LabStatusDto toDto(LocalLab.LabStatus status) {
        return new LabStatusDto(status.isUsable(), status.dockerAvailable(), status.daemonRunning(),
                status.composeAvailable(), status.version(), status.remedy());
    }

    private LabActivityDto toDto(LocalLabRunner.Activity activity) {
        boolean succeeded = activity.succeeded();
        boolean failed = activity.failed();
        var result = activity.resultIfPresent().orElse(null);
        return new LabActivityDto(activity.operation().label(), activity.operation().command(),
                activity.composeFile().toString(), succeeded, failed,
                result == null ? null : result.message(),
                result == null ? List.of() : result.output());
    }

    // ==================================================================== environments

    public record EnvironmentRequest(String name, String baseUrl, String type, String dependencies,
            Boolean productionLike, String headerNames, String headerValues,
            String targetKind, String image, Integer containerPort, Integer cpuMillicores,
            Long memoryMebibytes, String readinessPath, Integer readinessExpectedStatus,
            Integer readinessTimeoutSeconds, String composeFile, String composeService) {
    }

    public record MessageResponse(String message) {
    }

    @PostMapping("/environments")
    public MessageResponse addEnvironment(@PathVariable String id, @RequestBody EnvironmentRequest request) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);

        try {
            EnvironmentType type = parse(EnvironmentType.class, request.type(), "type",
                    "what kind of place this environment is, which is what decides whether a run "
                            + "against it may claim production capacity");
            DependencyMode dependencies = parse(DependencyMode.class, request.dependencies(),
                    "dependencies", "whether this environment's dependencies are real, simulated or "
                            + "a mixture, which a run's classification depends on");
            String slug = slug(request.name());

            EnvironmentCapabilities capabilities = new EnvironmentCapabilities(
                    dependencies == DependencyMode.MOCKED || dependencies == DependencyMode.MIXED,
                    false, Boolean.TRUE.equals(request.productionLike()),
                    type != EnvironmentType.LOCAL_ISOLATED, false);

            Map<String, String> existingHeaders = configuration.environmentByName(slug)
                    .map(Environment::headers).orElseGet(Map::of);
            Environment environment = new Environment(EnvironmentId.of(slug), slug, type,
                    targetFrom(request), capabilities,
                    dependencies, resolveHeaders(
                            parseHeaders(request.headerNames(), request.headerValues()), existingHeaders));

            List<Environment> updated = new ArrayList<>();
            boolean replaced = false;
            for (Environment existing : configuration.environments()) {
                if (existing.name().equalsIgnoreCase(slug)) {
                    updated.add(environment);
                    replaced = true;
                } else {
                    updated.add(existing);
                }
            }
            if (!replaced) {
                updated.add(environment);
            }

            projects.saveConfiguration(projectId, configuration.withEnvironments(updated));
            return new MessageResponse("Environment '" + slug + "' saved. Vortex classifies runs "
                    + "against it as " + environment.classification().label().toLowerCase() + "s.");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    /**
     * Removing a name Vortex doesn't have is not an error — the caller wanted it gone, and it
     * already is. No existence check, matching the only other delete endpoint in this controller
     * family ({@code RequestDataApiController.delete}), which behaves the same way.
     */
    @DeleteMapping("/environments/{name}")
    public MessageResponse deleteEnvironment(@PathVariable String id, @PathVariable String name) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);
        projects.saveConfiguration(projectId, configuration.withoutEnvironment(name));
        return new MessageResponse("Environment '" + name + "' removed. Runs already recorded "
                + "against it keep their own evidence — nothing already recorded is affected.");
    }

    /**
     * Checks whether the target an {@link EnvironmentRequest} declares is actually reachable, without
     * saving anything and without ever creating or starting anything — the "Test Connection" action
     * for a Docker/Compose target's configuration form. Calls only {@link
     * TargetExecutor#checkAvailability}, never {@link TargetExecutor#prepare}, under any
     * circumstance: a validation click may never start a long-lived container.
     *
     * <p>Validates whatever the request body describes, not necessarily what is currently saved for
     * this environment — the same "check what I'm about to save" contract {@code
     * observation/test} already has for an observation source.
     */
    @PostMapping("/environments/{name}/target/validate")
    public TargetValidationResponse validateTarget(@PathVariable String id, @PathVariable String name,
            @RequestBody EnvironmentRequest request) {
        ProjectId projectId = ProjectId.of(id);
        String workspacePath =
                projects.find(projectId).flatMap(Project::workspacePathIfPresent).orElse("");

        ExecutionTarget target;
        try {
            target = targetFrom(request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }

        TargetExecutor executor = targetExecutors.stream()
                .filter(candidate -> candidate.supports(target))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "'" + request.targetKind() + "' is not a target kind Vortex can validate."));

        List<PreflightCheck> checks = executor.checkAvailability(target, workspacePath);
        boolean valid = checks.stream().noneMatch(PreflightCheck::isFailure);
        List<String> descriptions = checks.stream()
                .map(check -> check.name() + ": " + check.status().label()
                        + (check.detail().isBlank() ? "" : " — " + check.detail()))
                .toList();
        return new TargetValidationResponse(valid, descriptions);
    }

    /**
     * Builds the {@link ExecutionTarget} an {@link EnvironmentRequest} declares.
     *
     * <p>{@code targetKind} absent or blank means {@code EXTERNAL_ENDPOINT}, matching {@code
     * vortex.yaml}'s default — {@code baseUrl} is required and used only in that case; a
     * Vortex-managed or attached target has no genuine pre-run address to demand one for.
     */
    private ExecutionTarget targetFrom(EnvironmentRequest request) {
        String kind = request.targetKind() == null ? "" : request.targetKind().trim().toUpperCase(java.util.Locale.ROOT);
        return switch (kind) {
            case "", "EXTERNAL_ENDPOINT" -> new ExternalEndpointTarget(TargetUrl.of(request.baseUrl()));
            case "DOCKER_IMAGE" -> dockerImageTarget(request);
            case "DOCKER_COMPOSE" -> dockerComposeTarget(request);
            default -> throw new IllegalArgumentException("'" + request.targetKind()
                    + "' is not a target kind Vortex understands. Use EXTERNAL_ENDPOINT, "
                    + "DOCKER_IMAGE or DOCKER_COMPOSE.");
        };
    }

    private DockerImageTarget dockerImageTarget(EnvironmentRequest request) {
        if (request.image() == null || request.image().isBlank()) {
            throw new IllegalArgumentException(
                    "A Docker-managed target needs the image to run, e.g. \"payment-service:1.4.2\"");
        }
        if (request.containerPort() == null) {
            throw new IllegalArgumentException(
                    "A Docker-managed target needs the port its container listens on");
        }
        CpuAllocation cpu = request.cpuMillicores() == null
                ? null : CpuAllocation.ofMillicores(request.cpuMillicores());
        MemoryAllocation memory = request.memoryMebibytes() == null
                ? null : MemoryAllocation.ofMebibytes(request.memoryMebibytes());
        ReadinessCheck readiness = readinessCheckFrom(request);
        return new DockerImageTarget(new ImageReference(request.image()),
                new ContainerPort(request.containerPort()),
                new ResourceEnvelopeRequest(cpu, memory), readiness);
    }

    private ReadinessCheck readinessCheckFrom(EnvironmentRequest request) {
        boolean hasPath = request.readinessPath() != null && !request.readinessPath().isBlank();
        boolean hasStatus = request.readinessExpectedStatus() != null;
        boolean hasTimeout = request.readinessTimeoutSeconds() != null;
        if (!hasPath && !hasStatus && !hasTimeout) {
            return null;
        }
        if (!(hasPath && hasStatus && hasTimeout)) {
            throw new IllegalArgumentException(
                    "A readiness check needs readinessPath, readinessExpectedStatus and "
                            + "readinessTimeoutSeconds together, not just some of them — or none of "
                            + "them, to fall back to a plain TCP connect once the port opens");
        }
        return new ReadinessCheck(request.readinessPath(), request.readinessExpectedStatus(),
                Duration.ofSeconds(request.readinessTimeoutSeconds()));
    }

    private DockerComposeTarget dockerComposeTarget(EnvironmentRequest request) {
        if (request.composeFile() == null || request.composeFile().isBlank()) {
            throw new IllegalArgumentException(
                    "An attached Compose target needs the Compose file this repository already owns");
        }
        if (request.composeService() == null || request.composeService().isBlank()) {
            throw new IllegalArgumentException(
                    "An attached Compose target needs the service name inside that Compose file");
        }
        if (request.containerPort() == null) {
            throw new IllegalArgumentException(
                    "An attached Compose target needs the port that service listens on in its container");
        }
        return new DockerComposeTarget(request.composeFile(), request.composeService(),
                new ContainerPort(request.containerPort()));
    }

    /**
     * One request field parsed into the enum it names, or an {@link IllegalArgumentException} the
     * caller can act on — which this endpoint turns into a 400.
     *
     * <p>{@code Enum.valueOf} answers a missing value with a {@link NullPointerException} that
     * escapes this endpoint's {@code catch} entirely and surfaces as a bare 500, and an unrecognised
     * one with {@code "No enum constant com.acltabontabon.vortex.core.…"} — a sentence that names a
     * Java class rather than the field the caller got wrong, and never says what the accepted values
     * are. Both are answered here by naming the field, saying what it decides, and listing what it
     * accepts.
     */
    private <E extends Enum<E>> E parse(Class<E> type, String value, String field, String decides) {
        String accepted = Arrays.stream(type.getEnumConstants()).map(Enum::name)
                .collect(Collectors.joining(", "));
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("'" + field + "' is required: it states " + decides
                    + ". Send one of: " + accepted + ".");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + value + "' is not a value Vortex understands "
                    + "for '" + field + "', which states " + decides + ". Use one of: " + accepted
                    + ".", e);
        }
    }

    private String slug(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("A name is required");
        }
        StringBuilder slug = new StringBuilder();
        for (char c : raw.trim().toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                slug.append(c);
            } else if (!slug.isEmpty() && slug.charAt(slug.length() - 1) != '-') {
                slug.append('-');
            }
        }
        while (!slug.isEmpty() && slug.charAt(slug.length() - 1) == '-') {
            slug.setLength(slug.length() - 1);
        }
        if (slug.isEmpty()) {
            throw new IllegalArgumentException(
                    "'" + raw + "' contains no letters or digits Vortex can use as a name");
        }
        return slug.toString();
    }

    private Map<String, String> parseHeaders(String names, String values) {
        List<String> nameLines = names == null ? List.of() : List.of(names.split("\\R", -1));
        List<String> valueLines = values == null ? List.of() : List.of(values.split("\\R", -1));
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 0; i < nameLines.size(); i++) {
            String name = nameLines.get(i).trim();
            if (name.isBlank()) {
                continue;
            }
            String value = i < valueLines.size() ? valueLines.get(i).trim() : "";
            headers.put(name, value);
        }
        return headers;
    }

    /**
     * Resolves a masked placeholder ({@link SecretReferences#MASK}) in {@code parsed} back to the
     * real value already stored under that header name, so leaving a masked header untouched on
     * save doesn't overwrite it with the literal placeholder string — {@code toDto} always masks a
     * literal header value on the way out (see {@code Environment#headerNames}), so the browser
     * never has the real value to resubmit. A masked value with nothing to recover it from (a new
     * header, or a renamed one) is rejected: Vortex never writes the placeholder as a real value.
     */
    private Map<String, String> resolveHeaders(Map<String, String> parsed, Map<String, String> existing) {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : parsed.entrySet()) {
            String value = entry.getValue();
            if (SecretReferences.MASK.equals(value)) {
                String real = existing.get(entry.getKey());
                if (real == null) {
                    throw new IllegalArgumentException("Header '" + entry.getKey() + "' shows "
                            + SecretReferences.MASK + " — retype its value to change it. Vortex never "
                            + "writes a masked placeholder as a real header value.");
                }
                value = real;
            }
            resolved.put(entry.getKey(), value);
        }
        return resolved;
    }

    // ==================================================================== release

    public record ReleaseRequest(String serviceVersion) {
    }

    @PostMapping("/release")
    public MessageResponse setReleaseUnderTest(@PathVariable String id, @RequestBody ReleaseRequest request) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);
        String release = request.serviceVersion() == null ? "" : request.serviceVersion().trim();
        projects.saveConfiguration(projectId, configuration.withServiceVersion(release));
        return new MessageResponse(release.isBlank()
                ? "Release identifier cleared. Runs will record no release until one is set."
                : "Runs will record release " + release + " until this changes.");
    }

    // ==================================================================== objectives

    public record ThresholdsRequest(Long p95Millis, Long p99Millis, Double errorPercent) {
    }

    @PostMapping("/thresholds")
    public MessageResponse setThresholds(@PathVariable String id, @RequestBody ThresholdsRequest request) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);

        List<Threshold> thresholds = new ArrayList<>();
        if (request.p95Millis() != null && request.p95Millis() > 0) {
            thresholds.add(LatencyThreshold.of(Percentile.P95, Duration.ofMillis(request.p95Millis())));
        }
        if (request.p99Millis() != null && request.p99Millis() > 0) {
            thresholds.add(LatencyThreshold.of(Percentile.P99, Duration.ofMillis(request.p99Millis())));
        }
        if (request.errorPercent() != null && request.errorPercent() >= 0) {
            thresholds.add(ErrorRateThreshold.ofPercent(request.errorPercent()));
        }

        try {
            projects.saveConfiguration(projectId, configuration.withThresholds(new ThresholdSet(thresholds)));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        return new MessageResponse(thresholds.isEmpty()
                ? "Objectives cleared. Runs will produce measurements but no verdict."
                : "Objectives saved.");
    }

    // ==================================================================== production

    public record ProductionRequest(Double averageRate, Double p95ObservedRate, double peakRate,
            List<String> mixOperation, List<Integer> mixWeight, String source, String observedFrom,
            String observedTo, String note) {
    }

    @PostMapping("/production")
    public MessageResponse setProductionObservation(@PathVariable String id, @RequestBody ProductionRequest request) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);

        try {
            Optional<OperationMix> mix = parseMix(request.mixOperation(), request.mixWeight());
            OperationMix resolvedMix = mix.orElseGet(() -> configuration.productionObservationIfPresent()
                    .flatMap(ProductionObservation::observedMixIfPresent).orElse(null));

            Observation observation = observationFrom(request.observedFrom(), request.observedTo());

            ProductionObservation built = new ProductionObservation(
                    request.averageRate() == null ? null : RequestsPerSecond.of(request.averageRate()),
                    request.p95ObservedRate() == null ? null : RequestsPerSecond.of(request.p95ObservedRate()),
                    RequestsPerSecond.of(request.peakRate()),
                    resolvedMix, request.source(), observation, request.note());

            projects.saveConfiguration(projectId, configuration.withProductionObservation(built));
            return new MessageResponse("Observed production traffic saved. Vortex can now propose "
                    + "workloads based on what your service actually receives, rather than an "
                    + "invented number.");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    private Optional<OperationMix> parseMix(List<String> operations, List<Integer> weights) {
        if (operations == null || operations.isEmpty()) {
            return Optional.empty();
        }
        List<WeightedOperation> entries = new ArrayList<>();
        for (int i = 0; i < operations.size(); i++) {
            String operationId = operations.get(i);
            if (operationId == null || operationId.isBlank()) {
                continue;
            }
            int weight = i < weights.size() && weights.get(i) != null ? weights.get(i) : 1;
            if (weight <= 0) {
                continue;
            }
            entries.add(WeightedOperation.of(OperationId.of(operationId), weight));
        }
        return entries.isEmpty() ? Optional.empty() : Optional.of(OperationMix.of(entries));
    }

    private Observation observationFrom(String from, String to) {
        Instant fromInstant = parseLocal(from);
        Instant toInstant = parseLocal(to);
        if (fromInstant == null) {
            return Observation.unknown();
        }
        if (toInstant == null || !toInstant.isAfter(fromInstant)) {
            return Observation.at(fromInstant);
        }
        return Observation.over(fromInstant, toInstant);
    }

    private Instant parseLocal(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    /** Never saves — see the class-level note on fetch/test being read-only actions. Remembers a
     *  successful retrieval so a following {@code /production/fetch-and-save} can reuse it instead of
     *  querying the observation source again; a failed retrieval clears whatever was remembered,
     *  since the preview now shown on screen no longer has anything successful behind it either. */
    @PostMapping("/production/fetch")
    public FetchProductionResponse fetchProductionObservation(@PathVariable String id) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);
        ServiceCatalog catalog = projects.catalog(projectId).orElse(null);

        var retrieval = calibrationService.fetch(configuration, catalog, null);
        return switch (retrieval) {
            case Retrieved retrieved -> {
                lastFetchedProduction.put(projectId, retrieved);
                yield new FetchProductionResponse(true, null, assembler.production(retrieved.observation(), catalog));
            }
            case NotRetrieved notRetrieved -> {
                lastFetchedProduction.remove(projectId);
                yield new FetchProductionResponse(false, notRetrieved.describe(), null);
            }
        };
    }

    /**
     * Persists exactly what the most recent successful {@code /production/fetch} for this service
     * already retrieved, consuming that remembered result — the preview shown to a person and what
     * gets saved are the same evidence, with no second live query. Falls back to a fresh fetch when
     * nothing is remembered (called without a prior fetch, or a source change invalidated it in
     * {@link #setObservationSource}), so the endpoint still works on its own.
     *
     * <p>Persists the adapter's own {@link ProductionObservation} directly — carrying its real {@code
     * provenance}, {@code mixCoverage} and {@code sampleResolution} — rather than routing through
     * {@link #setProductionObservation}'s hand-entry constructor, which nulls all three. That is what
     * lets the Configuration page tell a fetched observation from a typed-in one ({@code
     * ProductionObservation#wasFetched()}).
     */
    @PostMapping("/production/fetch-and-save")
    public FetchAndSaveProductionResponse fetchAndSaveProductionObservation(@PathVariable String id) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);
        ServiceCatalog catalog = projects.catalog(projectId).orElse(null);

        Retrieved remembered = lastFetchedProduction.remove(projectId);
        var retrieval = remembered != null ? remembered : calibrationService.fetch(configuration, catalog, null);
        return switch (retrieval) {
            case Retrieved retrieved -> {
                projects.saveConfiguration(projectId,
                        configuration.withProductionObservation(retrieved.observation()));
                yield new FetchAndSaveProductionResponse(true, null,
                        assembler.production(retrieved.observation(), catalog));
            }
            case NotRetrieved notRetrieved -> new FetchAndSaveProductionResponse(false, notRetrieved.describe(), null);
        };
    }

    // ==================================================================== observation source

    public record ObservationSourceRequest(String source, String transport, String endpoint,
            String serviceIdentifier, String window, List<String> headerName,
            List<String> headerValue) {
    }

    @PostMapping("/observation")
    public MessageResponse setObservationSource(@PathVariable String id, @RequestBody ObservationSourceRequest request) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);

        try {
            ObservationSource source = observationSourceFrom(request);
            projects.saveConfiguration(projectId, configuration.withObservationSource(source));
            // Whatever fetch-and-save might otherwise reuse was retrieved from a source this service
            // no longer points at.
            lastFetchedProduction.remove(projectId);
            return new MessageResponse("Saved. Vortex can now fetch observed production traffic from "
                    + source.kind().label() + " — test the connection, then fetch when you are ready.");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping("/observation/test")
    public TestConnectionResponse testObservationSource(@PathVariable String id, @RequestBody ObservationSourceRequest request) {
        try {
            ObservationSource source = observationSourceFrom(request);
            var retrieval = calibrationService.verify(source, null);
            return switch (retrieval) {
                case Retrieved retrieved -> new TestConnectionResponse(true,
                        source.kind().label() + " answered: a peak of "
                                + retrieved.observation().peakRate().display() + " requests/sec over the last "
                                + Durations.display(source.window()) + ". Nothing has been saved or fetched.");
                case NotRetrieved notRetrieved -> new TestConnectionResponse(false, notRetrieved.describe());
            };
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    private ObservationSource observationSourceFrom(ObservationSourceRequest request) {
        ObservationSource.Kind kind = switch (request.source() == null ? "" : request.source().toLowerCase()) {
            case "prometheus" -> ObservationSource.Kind.PROMETHEUS;
            case "dynatrace" -> ObservationSource.Kind.DYNATRACE;
            default -> throw new IllegalArgumentException("'" + request.source()
                    + "' is not a system Vortex can ask. Choose Prometheus or Dynatrace.");
        };
        ObservationSource.Transport transport =
                switch (request.transport() == null ? "" : request.transport().toLowerCase()) {
                    case "", "rest" -> ObservationSource.Transport.REST;
                    case "mcp" -> ObservationSource.Transport.MCP;
                    default -> throw new IllegalArgumentException("'" + request.transport()
                            + "' is not a transport Vortex knows. Choose REST or MCP.");
                };
        String names = request.headerName() == null ? "" : String.join("\n", request.headerName());
        String values = request.headerValue() == null ? "" : String.join("\n", request.headerValue());
        return new ObservationSource(kind, transport, request.endpoint(), request.serviceIdentifier(),
                Durations.parse(request.window()), parseHeaders(names, values), Map.of());
    }

    // ==================================================================== operations / import / review

    public record ImportRequest(String url, String content) {
    }

    public record ImportResponse(boolean succeeded, String message, String info, String error,
            List<String> errorDetails) {
    }

    @PostMapping("/import")
    public ImportResponse importSpecification(@PathVariable String id, @RequestBody ImportRequest request) {
        ProjectId projectId = ProjectId.of(id);
        String reference;
        String document;

        if (request.content() != null && !request.content().isBlank()) {
            reference = "pasted-openapi.yaml";
            document = request.content();
        } else if (request.url() != null && !request.url().isBlank()) {
            reference = request.url().trim();
            try {
                document = fetch(reference);
            } catch (RuntimeException e) {
                return new ImportResponse(false, null, null,
                        "Vortex could not read that API description: " + e.getMessage(), List.of());
            }
        } else {
            return new ImportResponse(false, null, null,
                    "Provide either a URL or the contents of your OpenAPI document.", List.of());
        }

        try {
            var catalog = catalogs.importCatalog(projectId, reference, document);
            String message = "Imported " + catalog.operationCount() + " operations from "
                    + (catalog.title().isBlank() ? reference : catalog.title()) + ".";
            String info = catalog.mutatingOperations().isEmpty() ? null
                    : catalog.mutatingOperations().size() + " of them can change data. Vortex will "
                            + "not run those until you have reviewed the request data it would send "
                            + "— schema-valid is not the same as business-valid.";
            return new ImportResponse(true, message, info, null, List.of());
        } catch (ServiceCatalogImporter.ImportException e) {
            return new ImportResponse(false, null, null, e.getMessage(), e.problems());
        }
    }

    private String fetch(String url) {
        return SpecificationFetch.fetch(http, url, MAX_SPECIFICATION_BYTES);
    }

    @PostMapping("/operations/{operationId}/review")
    public MessageResponse reviewOperation(@PathVariable String id, @PathVariable String operationId) {
        ProjectId projectId = ProjectId.of(id);
        projects.setOperationReviewed(projectId, OperationId.of(operationId), true);
        return new MessageResponse("Reviewed. This operation can now be used in a workload.");
    }

    // ==================================================================== local lab

    public record ComposeFileRequest(String composeFile) {
    }

    @PostMapping("/lab")
    public MessageResponse setComposeFile(@PathVariable String id, @RequestBody ComposeFileRequest request) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);
        try {
            LocalLabSettings settings = new LocalLabSettings(request.composeFile());
            projects.saveConfiguration(projectId, configuration.withLocalLab(settings));
            lab.forget(projectId);
            return new MessageResponse("Saved. Vortex will run " + settings.describe()
                    + " when you start this service's dependencies.");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping("/lab/clear")
    public MessageResponse clearComposeFile(@PathVariable String id) {
        ProjectId projectId = ProjectId.of(id);
        projects.saveConfiguration(projectId, projects.configuration(projectId).withLocalLab(null));
        lab.forget(projectId);
        return new MessageResponse("This service no longer has a local lab configured.");
    }

    @PostMapping("/lab/up")
    public MessageResponse labUp(@PathVariable String id) {
        return runLab(id, LocalLabRunner.Operation.UP);
    }

    @PostMapping("/lab/down")
    public MessageResponse labDown(@PathVariable String id) {
        return runLab(id, LocalLabRunner.Operation.DOWN);
    }

    @PostMapping("/lab/dismiss")
    public void dismissLab(@PathVariable String id) {
        lab.forget(ProjectId.of(id));
    }

    private MessageResponse runLab(String id, LocalLabRunner.Operation operation) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);

        Optional<LocalLabSettings> settings = configuration.localLabIfPresent();
        if (settings.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This service has no Compose file configured, so there is nothing to "
                            + operation.label() + ".");
        }

        String workspacePath = projects.find(projectId).flatMap(Project::workspacePathIfPresent).orElse(null);
        if (workspacePath == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This service has no repository on this machine, so Vortex cannot find "
                            + settings.get().describe() + ".");
        }

        java.nio.file.Path composeFile;
        try {
            composeFile = settings.get().resolveAgainst(workspacePath);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }

        if (!Files.isRegularFile(composeFile)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No Compose file was found at " + composeFile + ". Check the path against the repository.");
        }

        var status = lab.status();
        if (!status.isUsable()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, status.remedy());
        }

        boolean started = lab.start(projectId, operation, composeFile);
        return new MessageResponse(started
                ? "Running docker compose " + operation.command() + " on " + settings.get().describe() + "."
                : "A Compose command is already running for this service.");
    }
}
