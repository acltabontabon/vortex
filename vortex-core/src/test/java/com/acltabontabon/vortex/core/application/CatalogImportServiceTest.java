package com.acltabontabon.vortex.core.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acltabontabon.vortex.core.catalog.ServiceCatalog;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.port.Repositories.ServiceCatalogRepository;
import com.acltabontabon.vortex.core.port.ServiceCatalogImporter;
import com.acltabontabon.vortex.core.shared.ProjectId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link CatalogImportService#previewCatalog} shares its importer lookup with
 * {@link CatalogImportService#importCatalog} but must never reach the repository — a preview is
 * shown before any project exists to attach it to.
 */
class CatalogImportServiceTest {

    private static final String SOURCE_REF = "http://localhost:8080/openapi.yaml";

    @Test
    void previewParsesWithoutSavingAnything() {
        var repository = new RecordingRepository();
        var service = new CatalogImportService(List.of(new FakeImporter(SOURCE_REF)), repository);

        ServiceCatalog previewed = service.previewCatalog(SOURCE_REF, "raw document");

        assertEquals(Fixtures.catalog(), previewed);
        assertFalse(repository.saveCalled, "preview must not persist a catalog");
    }

    @Test
    void previewOfAnUnsupportedSourceFailsTheSameWayImportDoes() {
        var repository = new RecordingRepository();
        var service = new CatalogImportService(List.of(new FakeImporter("supported-ref")), repository);

        ServiceCatalogImporter.ImportException previewFailure = assertThrows(
                ServiceCatalogImporter.ImportException.class,
                () -> service.previewCatalog(SOURCE_REF, "raw document"));
        ServiceCatalogImporter.ImportException importFailure = assertThrows(
                ServiceCatalogImporter.ImportException.class,
                () -> service.importCatalog(ProjectId.of("checkout"), SOURCE_REF, "raw document"));

        assertEquals(importFailure.getMessage(), previewFailure.getMessage());
        assertEquals(importFailure.problems(), previewFailure.problems());
        assertTrue(previewFailure.problems().contains("Source was: " + SOURCE_REF));
        assertFalse(repository.saveCalled);
    }

    private record FakeImporter(String supportedRef) implements ServiceCatalogImporter {
        @Override
        public boolean supports(String sourceRef) {
            return supportedRef.equals(sourceRef);
        }

        @Override
        public ServiceCatalog importFrom(String sourceRef, String content) {
            return Fixtures.catalog();
        }
    }

    private static final class RecordingRepository implements ServiceCatalogRepository {
        private boolean saveCalled;

        @Override
        public void save(ProjectId projectId, ServiceCatalog catalog) {
            saveCalled = true;
        }

        @Override
        public Optional<ServiceCatalog> findByProject(ProjectId projectId) {
            return Optional.empty();
        }
    }
}
