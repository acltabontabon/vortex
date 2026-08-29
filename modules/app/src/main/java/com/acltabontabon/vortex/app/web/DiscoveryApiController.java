package com.acltabontabon.vortex.app.web;

import com.acltabontabon.vortex.app.discovery.DiscoveryConfigurationAssembler;
import com.acltabontabon.vortex.app.discovery.ProjectSnapshotBuilder;
import com.acltabontabon.vortex.core.application.CatalogImportService;
import com.acltabontabon.vortex.core.application.ProjectDiscoveryService;
import com.acltabontabon.vortex.core.application.ProjectService;
import com.acltabontabon.vortex.core.discovery.DiscoveryConflict;
import com.acltabontabon.vortex.core.discovery.DiscoveryProposal;
import com.acltabontabon.vortex.core.discovery.EnvironmentProposal;
import com.acltabontabon.vortex.core.discovery.Finding;
import com.acltabontabon.vortex.core.environment.DependencyMode;
import com.acltabontabon.vortex.core.environment.EnvironmentType;
import com.acltabontabon.vortex.core.lab.LocalLabSettings;
import com.acltabontabon.vortex.core.project.OpenApiSource;
import com.acltabontabon.vortex.core.project.Project;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.target.ContainerPort;
import com.acltabontabon.vortex.core.target.DockerComposeTarget;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Project Discovery for an already-created service: scanning its project directory for a proposal,
 * and applying whatever subset of it a person approved.
 *
 * <p>Scanning is a pure read — nothing is cached or persisted anywhere, and re-scanning is just
 * calling {@link #scan} again. Applying reuses the exact write paths a manual edit already takes
 * ({@link CatalogImportService#importCatalog}, {@link ProjectService#saveConfiguration}) rather than
 * a parallel persistence path. See
 * {@code docs/adr/adr-063-project-discovery-is-synchronous-and-stateless.adoc}.
 *
 * <p>The onboarding equivalent — scanning a directory before a service exists to attach an id to —
 * lives on {@code ServicesApiController.discoveryScan}, sharing {@link ProjectDiscoveryService} and
 * {@link ProjectSnapshotBuilder} with this controller.
 */
@RestController
@RequestMapping("/api/services/{id}/discovery")
public class DiscoveryApiController {

    private static final int MAX_SPECIFICATION_BYTES = 8 * 1024 * 1024;

    private final ProjectService projects;
    private final ProjectDiscoveryService discovery;
    private final ProjectSnapshotBuilder snapshotBuilder;
    private final CatalogImportService catalogs;
    private final DiscoveryConfigurationAssembler assembler;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public DiscoveryApiController(ProjectService projects, ProjectDiscoveryService discovery,
            ProjectSnapshotBuilder snapshotBuilder, CatalogImportService catalogs,
            DiscoveryConfigurationAssembler assembler) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.snapshotBuilder = Objects.requireNonNull(snapshotBuilder, "snapshotBuilder");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
    }

    public record ProposedEnvironmentDto(String name, String type, String targetKind,
            String targetSummary, String composeFile, String composeService, Integer containerPort,
            String dependencyMode) {
    }

    public record FindingDto(String kind, String label, String sourceFile, List<String> evidence,
            String confidence, String confidenceExplanation, Map<String, String> attributes) {
    }

    public record DiscoveryConflictDto(String field, String existingDescription,
            String discoveredDescription) {
    }

    public record DiscoveryScanResponse(boolean ok, String error, String proposedServiceName,
            String proposedServiceDescription, String proposedOpenApiSourceFile,
            ProposedEnvironmentDto proposedEnvironment, String proposedLocalLabComposeFile,
            List<FindingDto> findings, List<DiscoveryConflictDto> conflicts,
            List<String> partialFailures) {

        static DiscoveryScanResponse failure(String error) {
            return new DiscoveryScanResponse(false, error, null, null, null, null, null,
                    List.of(), List.of(), List.of());
        }
    }

    public record ApplyDiscoveryRequest(boolean applyOpenApiSource, String openApiSourceFile,
            boolean applyEnvironment, ProposedEnvironmentDto environment, boolean applyLocalLab,
            String localLabComposeFile) {
    }

    public record MessageResponse(String message) {
    }

    @PostMapping("/scan")
    public DiscoveryScanResponse scan(@PathVariable String id) {
        ProjectId projectId = ProjectId.of(id);
        Project project = require(projectId);
        return project.workspacePathIfPresent()
                .map(path -> scan(discovery, snapshotBuilder, path, projects.configuration(projectId)))
                .orElseGet(() -> DiscoveryScanResponse.failure(
                        "This service has no project directory recorded, so there is nothing for "
                                + "Vortex to inspect."));
    }

    @PostMapping("/apply")
    public MessageResponse apply(@PathVariable String id, @RequestBody ApplyDiscoveryRequest request) {
        ProjectId projectId = ProjectId.of(id);
        Project project = require(projectId);
        ProjectConfiguration configuration = projects.configuration(projectId);

        try {
            if (request.applyOpenApiSource() && hasText(request.openApiSourceFile())) {
                OpenApiSource source = new OpenApiSource.File(request.openApiSourceFile());
                String content = WorkspaceDocumentFetch.fetch(
                        http, project.workspacePath(), source, MAX_SPECIFICATION_BYTES);
                catalogs.importCatalog(projectId, source.describe(), content);
                configuration = configuration.withOpenApiSource(source);
            }
            if (request.applyEnvironment() && request.environment() != null) {
                configuration = assembler.withEnvironment(
                        configuration, toEnvironmentProposal(request.environment()));
            }
            if (request.applyLocalLab() && hasText(request.localLabComposeFile())) {
                configuration = assembler.withLocalLab(
                        configuration, new LocalLabSettings(request.localLabComposeFile()));
            }
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }

        projects.saveConfiguration(projectId, configuration);
        return new MessageResponse("Discovered setup applied.");
    }

    private Project require(ProjectId id) {
        return projects.find(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No service with id " + id));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Shared with {@code ServicesApiController.discoveryScan}, the onboarding equivalent. */
    static DiscoveryScanResponse scan(ProjectDiscoveryService discovery,
            ProjectSnapshotBuilder snapshotBuilder, String workspacePath,
            ProjectConfiguration existing) {
        ProjectSnapshotBuilder.Result snapshotResult;
        try {
            snapshotResult = snapshotBuilder.build(workspacePath);
        } catch (RuntimeException e) {
            return DiscoveryScanResponse.failure(e.getMessage());
        }

        DiscoveryProposal proposal = discovery.discover(snapshotResult.snapshot(), existing);

        List<String> combinedFailures = new ArrayList<>(snapshotResult.partialFailures());
        combinedFailures.addAll(proposal.partialFailures());

        return new DiscoveryScanResponse(true, null,
                blankToNull(proposal.proposedServiceName()),
                blankToNull(proposal.proposedServiceDescription()),
                proposal.proposedOpenApiSourceIfPresent()
                        .filter(source -> source instanceof OpenApiSource.File)
                        .map(source -> ((OpenApiSource.File) source).relativePath())
                        .orElse(null),
                proposal.proposedEnvironmentIfPresent().map(DiscoveryApiController::toEnvironmentDto)
                        .orElse(null),
                proposal.proposedLocalLabIfPresent().map(LocalLabSettings::composeFile).orElse(null),
                proposal.findings().stream().map(DiscoveryApiController::toFindingDto).toList(),
                proposal.conflicts().stream().map(DiscoveryApiController::toConflictDto).toList(),
                combinedFailures);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static FindingDto toFindingDto(Finding finding) {
        return new FindingDto(finding.kind().name(), finding.kind().label(), finding.sourceFile(),
                finding.evidence(), finding.confidence().name(), finding.confidence().explanation(),
                finding.attributes());
    }

    private static DiscoveryConflictDto toConflictDto(DiscoveryConflict conflict) {
        return new DiscoveryConflictDto(conflict.field().name(), conflict.existingDescription(),
                conflict.discoveredDescription());
    }

    /** {@code v1} only ever proposes a Compose-attached target — see {@code ProjectDiscoveryService}. */
    private static ProposedEnvironmentDto toEnvironmentDto(EnvironmentProposal proposal) {
        if (!(proposal.target() instanceof DockerComposeTarget compose)) {
            throw new IllegalStateException(
                    "Discovery only ever proposes a Compose-attached execution target, got: "
                            + proposal.target());
        }
        return new ProposedEnvironmentDto(proposal.name(), proposal.type().name(), "DOCKER_COMPOSE",
                compose.summary(), compose.composeFile(), compose.serviceName(),
                compose.containerPort().value(), proposal.dependencyMode().name());
    }

    static EnvironmentProposal toEnvironmentProposal(ProposedEnvironmentDto dto) {
        if (!"DOCKER_COMPOSE".equals(dto.targetKind())) {
            throw new IllegalArgumentException(
                    "Vortex only applies a Compose-attached execution target from discovery.");
        }
        DockerComposeTarget target = new DockerComposeTarget(dto.composeFile(), dto.composeService(),
                new ContainerPort(dto.containerPort()));
        return new EnvironmentProposal(dto.name(), EnvironmentType.valueOf(dto.type()), target,
                DependencyMode.valueOf(dto.dependencyMode()));
    }
}
