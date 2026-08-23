package com.acltabontabon.vortex.core.port;

import com.acltabontabon.vortex.core.catalog.ServiceCatalog;
import java.util.List;

/**
 * Turns an API description into a catalog of operations.
 *
 * <p>Importing is strictly deterministic. An OpenAPI document declares its paths, methods,
 * parameters and schemas unambiguously, so parsing it is ordinary software work — and asking a
 * language model to do it would introduce variability into the one part of onboarding that has none.
 *
 * <p>The interface also isolates the parser. Swagger's model classes never leave the adapter, so
 * replacing the parsing library — should it prove awkward under native compilation, for instance —
 * changes nothing outside that module.
 *
 * <p>Imported documents are untrusted input. Descriptions can contain text engineered to look like
 * instructions, so nothing from a specification is ever placed in a privileged position.
 */
public interface ServiceCatalogImporter {

    /** Whether this importer handles the given source. */
    boolean supports(String sourceRef);

    /**
     * Parses a description into a catalog.
     *
     * @param sourceRef a file path or URL, for display and re-import
     * @param content   the raw document
     * @return the catalog, including any non-fatal warnings
     * @throws ImportException when the document cannot be parsed at all
     */
    ServiceCatalog importFrom(String sourceRef, String content);

    /** Thrown when a description cannot be parsed, carrying an explanation a user can act on. */
    class ImportException extends RuntimeException {

        private final transient List<String> problems;

        public ImportException(String message, List<String> problems) {
            super(message);
            this.problems = problems == null ? List.of() : List.copyOf(problems);
        }

        public ImportException(String message, Throwable cause) {
            super(message, cause);
            this.problems = List.of();
        }

        public List<String> problems() {
            return problems;
        }
    }
}
