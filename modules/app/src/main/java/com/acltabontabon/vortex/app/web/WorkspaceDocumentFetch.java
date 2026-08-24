package com.acltabontabon.vortex.app.web;

import com.acltabontabon.vortex.core.project.OpenApiSource;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Fetches the content an {@link OpenApiSource} names — a repository file or a URL — so it can be
 * handed to {@code ServiceCatalogImporter.importFrom} exactly like a manually typed OpenAPI address.
 *
 * <p>A {@link OpenApiSource.Url} is delegated to {@link SpecificationFetch} unchanged. A {@link
 * OpenApiSource.File} is resolved against the repository actually being adopted right now — never a
 * path cached on a project row — which is what keeps a committed reference portable across clones.
 * Containment is enforced by {@link OpenApiSource.File#resolveAgainst(String)} itself.
 */
final class WorkspaceDocumentFetch {

    private WorkspaceDocumentFetch() {
    }

    /**
     * @throws IllegalArgumentException for anything the caller should report to the user: a source
     *                                   that escapes the repository, a missing file, or a document
     *                                   over {@code maxBytes}
     */
    static String fetch(HttpClient client, String workspacePath, OpenApiSource source, int maxBytes) {
        return switch (source) {
            case OpenApiSource.Url url -> SpecificationFetch.fetch(client, url.url(), maxBytes);
            case OpenApiSource.File file -> readFile(file.resolveAgainst(workspacePath), maxBytes);
        };
    }

    private static String readFile(Path resolved, int maxBytes) {
        byte[] bytes;
        try (var in = Files.newInputStream(resolved)) {
            bytes = in.readNBytes(maxBytes + 1);
        } catch (NoSuchFileException e) {
            throw new IllegalArgumentException("Vortex could not find " + resolved + ".");
        } catch (IOException e) {
            throw new IllegalArgumentException("Vortex could not read " + resolved + ": " + e.getMessage());
        }
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("That document is larger than Vortex will import.");
        }
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
