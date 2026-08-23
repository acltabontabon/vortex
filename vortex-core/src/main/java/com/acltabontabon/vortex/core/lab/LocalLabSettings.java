package com.acltabontabon.vortex.core.lab;

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
        composeFile = ComposeFileReference.normalise(composeFile);
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
        return ComposeFileReference.resolveAgainst(composeFile, workspacePath);
    }

    /** How to name this setting in the interface. */
    public String describe() {
        return composeFile;
    }
}
