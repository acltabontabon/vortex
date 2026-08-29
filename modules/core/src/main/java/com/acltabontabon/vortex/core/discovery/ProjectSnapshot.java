package com.acltabontabon.vortex.core.discovery;

import java.util.List;
import java.util.Optional;

/**
 * The bounded set of a project's own files Project Discovery read, offered to every detector.
 *
 * <p>Not a general filesystem view: whatever assembled this snapshot already applied the fixed
 * candidate-path probing, size limits and root-confinement checks — a detector sees only what
 * passed those checks, and never reaches back to disk itself.
 *
 * @param projectLabel a human name for the project being inspected, for display only
 * @param files        every project file Discovery could safely read
 */
public record ProjectSnapshot(String projectLabel, List<ProjectFile> files) {

    public ProjectSnapshot {
        projectLabel = projectLabel == null ? "" : projectLabel.trim();
        files = files == null ? List.of() : List.copyOf(files);
    }

    public Optional<ProjectFile> file(String relativePath) {
        return files.stream().filter(file -> file.relativePath().equals(relativePath)).findFirst();
    }
}
