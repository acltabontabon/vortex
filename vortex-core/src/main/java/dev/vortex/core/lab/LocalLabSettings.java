package dev.vortex.core.lab;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Which Compose file describes this service's local dependencies.
 *
 * <p>Vortex does not own the file. Most services that need a database and a couple of stubs already
 * have a working {@code compose.yaml}; generating a second one would ask a team to maintain the same
 * thing twice, and the two would drift. See {@code docs/02-architecture/architecture.adoc} (Local
 * lab).
 *
 * <p>The path is stored <em>relative to the repository</em> and an absolute path is refused. This
 * configuration round-trips to {@code vortex.yaml}, which belongs in version control next to the
 * service it describes — an absolute path stops being true the moment a colleague clones the repo on
 * a machine with a different home directory.
 *
 * <p>What is stored is the canonical normalised form, so {@code ./infra/../compose.yaml} is kept as
 * {@code compose.yaml}. Two spellings of one path are one setting, and the committed file should
 * show the one a reader can act on.
 *
 * @param composeFile the Compose file, relative to the service's repository, e.g. {@code
 *                    compose.yaml} or {@code infra/compose.yaml}
 */
public record LocalLabSettings(String composeFile) {

    public LocalLabSettings {
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

        composeFile = canonicalise(normalized);
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
     */
    public Path resolveAgainst(String workspacePath) {
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

    /** How to name this setting in the interface. */
    public String describe() {
        return composeFile;
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
