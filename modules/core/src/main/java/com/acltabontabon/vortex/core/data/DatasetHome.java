package com.acltabontabon.vortex.core.data;

import com.acltabontabon.vortex.core.shared.ProjectId;
import java.util.Objects;

/**
 * Which service's datasets are being asked about.
 *
 * <p>Carries the workspace path alongside the identifier because a portable dataset lives beside the
 * service's {@code vortex.yaml} and a local one does not. Passing both explicitly keeps the store
 * from reaching back into a repository to discover where it is allowed to write, which is the kind of
 * hidden lookup that makes a filesystem write hard to reason about.
 *
 * @param project       the service
 * @param workspacePath the service's directory, or empty when it has none
 */
public record DatasetHome(ProjectId project, String workspacePath) {

    public DatasetHome {
        Objects.requireNonNull(project, "project");
        workspacePath = workspacePath == null ? "" : workspacePath.trim();
    }

    public static DatasetHome of(ProjectId project, String workspacePath) {
        return new DatasetHome(project, workspacePath);
    }

    /** Whether this service has a directory that a portable dataset could be committed into. */
    public boolean hasWorkspace() {
        return !workspacePath.isBlank();
    }
}
