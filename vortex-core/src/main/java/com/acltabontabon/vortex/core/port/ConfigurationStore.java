package com.acltabontabon.vortex.core.port;

import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes the portable {@code vortex.yaml}.
 *
 * <p>The file is the source of truth for test intent and it belongs in version control next to the
 * service. A performance definition that only exists inside one person's installation of a UI is
 * not reproducible, and cannot be run from a pipeline.
 *
 * <p>Writing never includes runtime resolution: allocated per-operation rates, runner selection and
 * resolved secrets are products of execution and live in the effective plan, not in the user's
 * configuration.
 */
public interface ConfigurationStore {

    /** Loads configuration from a workspace directory. */
    LoadResult load(String workspacePath);

    /** Writes configuration, preserving user intent and never inlining resolved values. */
    void save(String workspacePath, ProjectConfiguration configuration);

    /** Renders configuration as YAML for the "View configuration" panel. */
    String render(ProjectConfiguration configuration);

    /** Parses and validates without writing anything, for {@code vortex validate}. */
    LoadResult parse(String yaml, String sourceLabel);

    /**
     * The outcome of loading configuration.
     *
     * @param configuration the parsed configuration, when parsing succeeded
     * @param problems      validation problems, each naming the field and what is wrong with it
     * @param sourcePath    where it was read from, for display
     */
    record LoadResult(ProjectConfiguration configuration, List<String> problems, String sourcePath) {

        public LoadResult {
            problems = problems == null ? List.of() : List.copyOf(problems);
            sourcePath = sourcePath == null ? "" : sourcePath;
        }

        public static LoadResult missing(String path) {
            return new LoadResult(null, List.of(), path);
        }

        public static LoadResult invalid(List<String> problems, String path) {
            return new LoadResult(null, problems, path);
        }

        public static LoadResult loaded(ProjectConfiguration configuration, String path) {
            return new LoadResult(configuration, List.of(), path);
        }

        public boolean isValid() {
            return configuration != null && problems.isEmpty();
        }

        public Optional<ProjectConfiguration> value() {
            return Optional.ofNullable(configuration);
        }
    }
}
