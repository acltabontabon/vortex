package dev.vortex.app.web;

import dev.vortex.core.application.CatalogImportService;
import dev.vortex.core.application.ProjectService;
import dev.vortex.core.catalog.ServiceCatalog;
import dev.vortex.core.port.ServiceCatalogImporter;
import dev.vortex.core.project.Project;
import dev.vortex.core.shared.ProjectId;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
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
 * The service list, and creating a service.
 *
 * <p>{@link #list()} backs both the app shell's service switcher — a small, cheap read the top
 * bar needs on every page — and the standalone Services page, which shows a little more (what the
 * service does, what release is under test). One endpoint, since a switcher entry and a list row
 * describe the same thing and should never quietly disagree about it.
 */
@RestController
@RequestMapping("/api/services")
public class ServicesApiController {

    /** Guards against pointing the importer at something enormous. Mirrors ProjectController. */
    private static final int MAX_SPECIFICATION_BYTES = 8 * 1024 * 1024;

    /** How many discovered operations a preview shows before saying "+N more". */
    private static final int PREVIEW_SAMPLE_SIZE = 3;

    private final ProjectService projects;
    private final CatalogImportService catalogs;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ServicesApiController(ProjectService projects, CatalogImportService catalogs) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
    }

    public record ServiceSummaryDto(String id, String name, String description,
            String serviceVersion) {}

    public record CreateServiceRequest(String name, String description, String workspacePath,
            String openApiUrl) {}

    /**
     * What came of an optional OpenAPI import attempted in the same act as creation.
     *
     * <p>Deliberately not symmetrical with creation failure: by the time an import is attempted the
     * service already exists, so a failed import is reported beside it rather than in place of it.
     */
    public record ImportOutcomeDto(boolean attempted, boolean succeeded, String message,
            String info, String error, List<String> errorDetails) {

        static ImportOutcomeDto notAttempted() {
            return new ImportOutcomeDto(false, false, null, null, null, List.of());
        }
    }

    public record CreateServiceResponse(ServiceSummaryDto service, ImportOutcomeDto importOutcome) {}

    public record OpenApiPreviewRequest(String url) {}

    public record OperationPreviewDto(String label) {}

    /**
     * What Vortex found in an API description before any service exists to attach it to — the
     * same parse {@link #create} would do, minus the commit. Always {@code 200}: an empty or
     * unreachable URL is the normal state of a field the user is still typing into, not a server
     * error, so callers branch on {@code ok} rather than on HTTP status.
     */
    public record OpenApiPreviewResponse(boolean ok, String title, int operationCount,
            List<OperationPreviewDto> sample, String error, List<String> errorDetails) {

        static OpenApiPreviewResponse failure(String error, List<String> errorDetails) {
            return new OpenApiPreviewResponse(false, null, 0, List.of(), error, errorDetails);
        }
    }

    public record WorkspaceCheckRequest(String path) {}

    /** Filesystem facts about a candidate repository path — see {@link OpenApiPreviewResponse}. */
    public record WorkspaceCheckResponse(boolean exists, boolean isDirectory, boolean writable,
            boolean gitRepository, String error) {

        static WorkspaceCheckResponse failure(String error) {
            return new WorkspaceCheckResponse(false, false, false, false, error);
        }
    }

    @GetMapping
    public List<ServiceSummaryDto> list() {
        return projects.all().stream().map(this::toDto).toList();
    }

    /**
     * Creates a service, and imports its API description in the same act when one was given.
     *
     * <p>Mirrors the Thymeleaf-era {@code ProjectController.create} exactly: the address is one
     * optional field on the creation form so that someone with it to hand goes from nothing to a
     * list of operations in one submit, and a failed import does not hide the service it landed on.
     */
    @PostMapping
    public CreateServiceResponse create(@RequestBody CreateServiceRequest request) {
        Project project;
        try {
            project = projects.create(request.name(), request.description(), request.workspacePath());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }

        String openApiUrl = request.openApiUrl();
        if (openApiUrl == null || openApiUrl.isBlank()) {
            return new CreateServiceResponse(toDto(project), ImportOutcomeDto.notAttempted());
        }

        String reference = openApiUrl.trim();
        ImportOutcomeDto outcome;
        try {
            outcome = importFrom(project.id(), reference, fetch(reference));
        } catch (RuntimeException e) {
            outcome = new ImportOutcomeDto(true, false, null, null,
                    "The service was created, but Vortex could not read that API description: "
                            + e.getMessage(),
                    List.of());
        }
        return new CreateServiceResponse(toDto(project), outcome);
    }

    /**
     * Deletes a service and every run, analysis and piece of evidence recorded against it.
     * Irreversible, and refused while a run is still in progress.
     */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        try {
            projects.delete(ProjectId.of(id));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    /**
     * Shows what an OpenAPI address holds before a service exists to import it into — the "Add
     * service" form's live evidence for the API definition field.
     */
    @PostMapping("/openapi-preview")
    public OpenApiPreviewResponse previewOpenApi(@RequestBody OpenApiPreviewRequest request) {
        String url = request.url() == null ? "" : request.url().trim();
        if (url.isEmpty()) {
            return OpenApiPreviewResponse.failure("Enter a URL to preview it.", List.of());
        }
        try {
            ServiceCatalog catalog = catalogs.previewCatalog(url, fetch(url));
            List<OperationPreviewDto> sample = catalog.operations().stream()
                    .limit(PREVIEW_SAMPLE_SIZE)
                    .map(operation -> new OperationPreviewDto(operation.label()))
                    .toList();
            return new OpenApiPreviewResponse(true, catalog.title(), catalog.operationCount(),
                    sample, null, List.of());
        } catch (ServiceCatalogImporter.ImportException e) {
            return OpenApiPreviewResponse.failure(e.getMessage(), e.problems());
        } catch (RuntimeException e) {
            // fetch() throws IllegalArgumentException for a bad scheme, an unreachable host, an
            // oversized body or a non-2xx response — all normal outcomes of a field still being typed.
            return OpenApiPreviewResponse.failure(e.getMessage(), List.of());
        }
    }

    /**
     * Shows what Vortex can already tell about a candidate repository path — the "Add service"
     * form's live evidence for the (optional) workspace path field. Directory presence and a
     * {@code .git} directory only; a git worktree or submodule's {@code .git} *file* reads as "not
     * a repository" here, which is an acceptable simplification for an advisory hint.
     */
    @PostMapping("/workspace-check")
    public WorkspaceCheckResponse checkWorkspace(@RequestBody WorkspaceCheckRequest request) {
        String raw = request.path() == null ? "" : request.path().trim();
        if (raw.isEmpty()) {
            return WorkspaceCheckResponse.failure("Enter a path to check it.");
        }
        Path path;
        try {
            path = Path.of(raw);
        } catch (InvalidPathException e) {
            return WorkspaceCheckResponse.failure("That is not a valid path.");
        }
        if (!Files.exists(path)) {
            return WorkspaceCheckResponse.failure("Vortex could not find that path.");
        }
        if (!Files.isDirectory(path)) {
            return new WorkspaceCheckResponse(true, false, false, false,
                    "That path is not a directory.");
        }
        return new WorkspaceCheckResponse(true, true, Files.isWritable(path),
                Files.isDirectory(path.resolve(".git")), null);
    }

    /** Shared with Understand's import form — see the Configuration migration. */
    private ImportOutcomeDto importFrom(ProjectId projectId, String reference, String document) {
        try {
            var catalog = catalogs.importCatalog(projectId, reference, document);
            String message = "Imported " + catalog.operationCount() + " operations from "
                    + (catalog.title().isBlank() ? reference : catalog.title()) + ".";
            String info = catalog.mutatingOperations().isEmpty() ? null
                    : catalog.mutatingOperations().size() + " of them can change data. Vortex will "
                            + "not run those until you have reviewed the request data it would send "
                            + "— schema-valid is not the same as business-valid.";
            return new ImportOutcomeDto(true, true, message, info, null, List.of());
        } catch (ServiceCatalogImporter.ImportException e) {
            return new ImportOutcomeDto(true, false, null, null, e.getMessage(), e.problems());
        }
    }

    /**
     * Fetches a specification over HTTP. Only {@code http}/{@code https}, and only what the user
     * explicitly typed — Vortex does not follow references out of the document to other hosts.
     */
    private String fetch(String url) {
        return SpecificationFetch.fetch(http, url, MAX_SPECIFICATION_BYTES);
    }

    private ServiceSummaryDto toDto(Project project) {
        return new ServiceSummaryDto(project.id().value(), project.name(), project.description(),
                project.serviceVersion());
    }
}
