package dev.vortex.core.application;

import dev.vortex.core.catalog.Operation;
import dev.vortex.core.catalog.ServiceCatalog;
import dev.vortex.core.project.ProjectConfiguration;
import dev.vortex.core.port.Repositories.ServiceCatalogRepository;
import dev.vortex.core.port.ServiceCatalogImporter;
import dev.vortex.core.shared.ProjectId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Imports an API description and turns it into a reviewable inventory of operations.
 *
 * <p>Two rules are enforced here, and both exist to keep a confident-looking import from becoming a
 * dangerous one.
 *
 * <p>First, discovery is not interpretation. The importer finds operations with certainty; it does
 * not decide what they are worth testing, how much traffic each receives, or whether its generated
 * request data is fit to send. Those are human decisions and they live in the project's
 * configuration, which is also why re-importing never destroys them.
 *
 * <p>Second, an operation that changes data needs review before it can run. Vortex may have produced
 * schema-valid JSON for {@code POST /orders}, but schema-valid is not business-valid, and repeatedly
 * creating records at a hundred a second is not something that should become possible by accident.
 * The requirement is a property of the operation ({@code Operation.requiresReview()}); the approval
 * is a property of the binding a person recorded, so it survives re-import by construction rather
 * than by a merge rule that could get it wrong.
 */
public final class CatalogImportService {

    private final List<ServiceCatalogImporter> importers;
    private final ServiceCatalogRepository catalogs;

    public CatalogImportService(List<ServiceCatalogImporter> importers,
            ServiceCatalogRepository catalogs) {
        this.importers = List.copyOf(Objects.requireNonNull(importers, "importers"));
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
    }

    public ServiceCatalog importCatalog(ProjectId projectId, String sourceRef, String content) {
        ServiceCatalog imported = findImporter(sourceRef).importFrom(sourceRef, content);
        catalogs.save(projectId, imported);
        return imported;
    }

    /**
     * Parses a description without committing it to any project — used to show someone what
     * Vortex would find before a service exists to attach it to.
     */
    public ServiceCatalog previewCatalog(String sourceRef, String content) {
        return findImporter(sourceRef).importFrom(sourceRef, content);
    }

    private ServiceCatalogImporter findImporter(String sourceRef) {
        return importers.stream()
                .filter(candidate -> candidate.supports(sourceRef))
                .findFirst()
                .orElseThrow(() -> new ServiceCatalogImporter.ImportException(
                        "Vortex does not recognise this kind of API description.",
                        List.of("Supported formats: OpenAPI 3.x as .yaml, .yml or .json.",
                                "Source was: " + sourceRef)));
    }

    public Optional<ServiceCatalog> catalog(ProjectId projectId) {
        return catalogs.findByProject(projectId);
    }

    /**
     * Operations that cannot yet be executed because their request data has not been reviewed.
     *
     * <p>Needs the configuration as well as the catalog: the catalog says which operations
     * <em>require</em> review, and only the configuration records which have <em>received</em> it.
     */
    public List<Operation> awaitingReview(ProjectId projectId, ProjectConfiguration configuration) {
        return catalogs.findByProject(projectId)
                .map(catalog -> catalog.operations().stream()
                        .filter(operation -> !operation.isExecutable(
                                configuration.bindingOrDefault(operation.id())))
                        .toList())
                .orElseGet(List::of);
    }
}
