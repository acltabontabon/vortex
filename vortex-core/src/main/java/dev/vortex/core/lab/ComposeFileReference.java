package dev.vortex.core.lab;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Shared relative-path/containment validation for a Compose file reference.
 *
 * <p>Extracted out of {@link LocalLabSettings} so {@code dev.vortex.core.target.DockerComposeTarget}
 * can apply the same repository-containment rules to the Compose file it attaches to, without
 * duplicating (and risking drifting from) the validation logic. {@link LocalLabSettings} delegates
 * here; its own public API and behavior are unchanged.
 */
public final class ComposeFileReference {

    private ComposeFileReference() {
    }

    /**
     * Normalises and validates a Compose file path, relative to the service's repository.
     *
     * @throws IllegalArgumentException when the path is blank, absolute, unusable, or escapes the
     *                                   repository once normalised
     */
    public static String normalise(String composeFile) {
        composeFile = composeFile == null ? "" : composeFile.trim();
        if (composeFile.isBlank()) {
            throw new IllegalArgumentException(
                    "a local lab needs the Compose file that describes this service's dependencies, "
                            + "relative to the repository, e.g. 'compose.yaml'");
        }

        Path candidate;
        try {
            candidate = Path.of(composeFile);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(
                    "'" + composeFile + "' is not a usable file path: " + e.getReason());
        }

        if (candidate.isAbsolute()) {
            throw new IllegalArgumentException(
                    "the Compose file must be given relative to the repository, but '" + composeFile
                            + "' is an absolute path. vortex.yaml is committed and travels between "
                            + "machines, where that path will not exist — use something like "
                            + "'compose.yaml' instead");
        }

        Path normalized = candidate.normalize();
        if (normalized.toString().isBlank()) {
            throw new IllegalArgumentException(
                    "'" + composeFile + "' names a directory rather than a Compose file");
        }
        if (escapesRepository(normalized)) {
            throw new IllegalArgumentException(
                    "'" + composeFile + "' points outside the service's repository. Vortex runs the "
                            + "Compose file the repository owns, so the path has to stay inside it");
        }

        return canonicalise(normalized);
    }

    /**
     * The absolute Compose file for a service checked out at {@code workspacePath}.
     *
     * <p>Containment is re-checked here rather than trusted from construction, because the workspace
     * is only known at this point.
     *
     * <p>The check is lexical on purpose. Vortex is a local developer tool and repositories
     * legitimately contain symlinks — resolving them would refuse ordinary, working setups in the
     * name of a threat model that does not apply to a file the developer already owns and could run
     * themselves.
     *
     * @param composeFile   an already-normalised Compose file path, as returned by {@link
     *                      #normalise(String)}
     * @param workspacePath the service's checkout on this machine
     */
    public static Path resolveAgainst(String composeFile, String workspacePath) {
        if (workspacePath == null || workspacePath.isBlank()) {
            throw new IllegalArgumentException(
                    "the service has no repository on this machine, so there is nowhere to resolve '"
                            + composeFile + "' against");
        }
        Path workspace = Path.of(workspacePath).toAbsolutePath().normalize();
        Path resolved = workspace.resolve(composeFile).normalize();
        if (!resolved.startsWith(workspace)) {
            throw new IllegalArgumentException(
                    "'" + composeFile + "' resolves to " + resolved + ", which is outside the "
                            + "service's repository at " + workspace);
        }
        return resolved;
    }

    /** Whether a normalised relative path starts by climbing out of the repository. */
    private static boolean escapesRepository(Path normalized) {
        return normalized.getNameCount() > 0 && "..".equals(normalized.getName(0).toString());
    }

    /**
     * The stored spelling: elements joined with {@code /}, whatever the platform separator is.
     *
     * <p>A path written on Windows and read on Linux has to mean the same thing, and {@code vortex
     * .yaml} is expected to move between them.
     */
    private static String canonicalise(Path normalized) {
        StringBuilder canonical = new StringBuilder();
        for (Path element : normalized) {
            if (!canonical.isEmpty()) {
                canonical.append('/');
            }
            canonical.append(element);
        }
        return canonical.toString();
    }
}
