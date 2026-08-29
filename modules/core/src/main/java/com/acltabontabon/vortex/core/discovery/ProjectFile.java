package com.acltabontabon.vortex.core.discovery;

/**
 * One project file already read from disk, offered to detectors as plain text.
 *
 * <p>Detectors never touch the filesystem themselves — only whatever built this snapshot did, and
 * only inside the project root the user selected. See {@code docs/04-reference/project-discovery.adoc},
 * "Project directory security."
 *
 * @param relativePath the file's path relative to the project root, using {@code /} as separator
 * @param content      the file's text content, already size-checked and decoded
 */
public record ProjectFile(String relativePath, String content) {

    public ProjectFile {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("a project file needs a relative path");
        }
        content = content == null ? "" : content;
    }
}
