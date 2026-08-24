package com.acltabontabon.vortex.core.project;

import com.acltabontabon.vortex.core.lab.ComposeFileReference;
import java.nio.file.Path;

/**
 * Where a service's OpenAPI description lives, as configured intent rather than a fetched result.
 *
 * <p>A sealed choice between a file the repository already owns and a URL, because those are
 * different claims: a {@link File} makes the repository self-describing — clone it, point Vortex at
 * it, and the operations come back without anyone re-typing an address — while a {@link Url} is an
 * address that has to be reachable independently of the repository. Recording a bare string instead
 * would blur that distinction and force every reader to guess which kind it was.
 *
 * <p>Neither variant stores a resolved location. A {@link File} is validated and normalised the same
 * way {@code LocalLabSettings} validates a Compose file — relative to the repository, refusing an
 * absolute path or one that escapes it — and is only resolved against an actual checkout at import
 * time, so the reference stays true after a clone or a move.
 */
public sealed interface OpenApiSource {

    /** How to describe this source in the interface, e.g. "file: openapi/checkout.yaml". */
    String describe();

    /**
     * A file the repository already owns, relative to it.
     *
     * @param relativePath the file, relative to the repository, e.g. {@code openapi/checkout.yaml}
     */
    record File(String relativePath) implements OpenApiSource {

        public File {
            relativePath = ComposeFileReference.normalise(relativePath);
        }

        /**
         * The absolute file for a service checked out at {@code workspacePath}.
         *
         * @see com.acltabontabon.vortex.core.lab.LocalLabSettings#resolveAgainst(String)
         */
        public Path resolveAgainst(String workspacePath) {
            return ComposeFileReference.resolveAgainst(relativePath, workspacePath);
        }

        @Override
        public String describe() {
            return "file: " + relativePath;
        }
    }

    /**
     * A URL Vortex can fetch the description from directly.
     *
     * @param url the address of the OpenAPI document
     */
    record Url(String url) implements OpenApiSource {

        public Url {
            url = url == null ? "" : url.trim();
            if (url.isBlank()) {
                throw new IllegalArgumentException(
                        "an OpenAPI URL source needs the address of the document");
            }
        }

        @Override
        public String describe() {
            return "url: " + url;
        }
    }
}
