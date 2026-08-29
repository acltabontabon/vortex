package com.acltabontabon.vortex.app.discovery;

import com.acltabontabon.vortex.core.discovery.ProjectFile;
import com.acltabontabon.vortex.core.discovery.ProjectSnapshot;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the fixed, small set of files Project Discovery knows how to use, from a project directory
 * the user explicitly selected.
 *
 * <p>Deliberately not a recursive tree walk with an ignore-list: v1's detector scope is a closed,
 * enumerable set of well-known filenames and locations, so probing exactly those candidates is both
 * simpler and safer than walking the whole tree and excluding {@code .git}/{@code node_modules}/etc.
 * along the way. See {@code docs/04-reference/project-discovery.adoc}, "Project directory security."
 *
 * <p>Every candidate is confined to the project root by comparing real (symlink-resolved) paths, is
 * capped at {@link #MAX_FILE_BYTES}, and is decoded with a strict UTF-8 decoder that rejects rather
 * than mangles a binary file. None of these failures abort the scan — each becomes one line in
 * {@link Result#partialFailures()} and the candidate is simply skipped.
 */
public final class ProjectSnapshotBuilder {

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    private static final List<String> ROOT_CANDIDATES =
            List.of("pom.xml", ".env.example", ".env.template", ".env.sample");
    private static final List<String> DOCKERFILE_DIRECTORIES = List.of("", "docker/", "deploy/");
    private static final List<String> COMPOSE_DIRECTORIES = List.of("", "infra/", "deploy/");
    private static final List<String> COMPOSE_NAMES =
            List.of("compose.yaml", "compose.yml", "docker-compose.yml", "docker-compose.yaml");
    private static final String APPLICATION_CONFIG_DIRECTORY = "src/main/resources/";
    private static final List<String> APPLICATION_CONFIG_EXTENSIONS =
            List.of("yml", "yaml", "properties");
    private static final List<String> APPLICATION_PROFILES =
            List.of("", "-local", "-dev", "-test", "-prod", "-production");
    private static final List<String> OPENAPI_DIRECTORIES =
            List.of("", "src/main/resources/", "src/main/resources/openapi/", "docs/");
    private static final List<String> OPENAPI_NAMES = List.of(
            "openapi.yaml", "openapi.yml", "openapi.json",
            "swagger.yaml", "swagger.yml", "swagger.json",
            "api-docs.yaml", "api-docs.yml", "api-docs.json");

    /** A snapshot, and anything Discovery could not read while assembling it. */
    public record Result(ProjectSnapshot snapshot, List<String> partialFailures) {
    }

    /**
     * @throws IllegalArgumentException when {@code projectRoot} does not exist or is not a directory
     */
    public Result build(String projectRoot) {
        Path root = Path.of(projectRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException(
                    "Vortex could not find a project directory at " + projectRoot + ".");
        }
        Path realRoot;
        try {
            realRoot = root.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Vortex could not resolve the project directory at " + projectRoot + ".");
        }

        List<ProjectFile> files = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> partialFailures = new ArrayList<>();

        for (String relative : ROOT_CANDIDATES) {
            tryRead(root, realRoot, relative, files, seen, partialFailures);
        }
        for (String directory : DOCKERFILE_DIRECTORIES) {
            tryRead(root, realRoot, directory + "Dockerfile", files, seen, partialFailures);
        }
        for (String directory : COMPOSE_DIRECTORIES) {
            for (String name : COMPOSE_NAMES) {
                tryRead(root, realRoot, directory + name, files, seen, partialFailures);
            }
        }
        for (String profile : APPLICATION_PROFILES) {
            for (String extension : APPLICATION_CONFIG_EXTENSIONS) {
                tryRead(root, realRoot,
                        APPLICATION_CONFIG_DIRECTORY + "application" + profile + "." + extension,
                        files, seen, partialFailures);
            }
        }
        for (String directory : OPENAPI_DIRECTORIES) {
            for (String name : OPENAPI_NAMES) {
                tryRead(root, realRoot, directory + name, files, seen, partialFailures);
            }
        }

        String label = root.getFileName() == null ? projectRoot : root.getFileName().toString();
        return new Result(new ProjectSnapshot(label, files), partialFailures);
    }

    private void tryRead(Path root, Path realRoot, String relative, List<ProjectFile> files,
            Set<String> seen, List<String> partialFailures) {
        if (!seen.add(relative)) {
            return;
        }
        Path candidate = root.resolve(relative);
        if (!Files.isRegularFile(candidate)) {
            return;
        }
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(realRoot)) {
                partialFailures.add(relative + " was skipped: it resolves outside the project directory.");
                return;
            }
            if (Files.size(candidate) > MAX_FILE_BYTES) {
                partialFailures.add(
                        relative + " was skipped: it is larger than Vortex reads for discovery.");
                return;
            }
            files.add(new ProjectFile(relative, decodeStrict(Files.readAllBytes(candidate))));
        } catch (CharacterCodingException e) {
            partialFailures.add(relative + " was skipped: it does not look like a text file.");
        } catch (IOException | SecurityException e) {
            partialFailures.add(relative + " could not be read: " + e.getMessage());
        }
    }

    /** Rejects rather than silently mangles invalid UTF-8 — the "avoid binary files" safeguard. */
    private static String decodeStrict(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }
}
