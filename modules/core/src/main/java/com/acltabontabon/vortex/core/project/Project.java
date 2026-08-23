package com.acltabontabon.vortex.core.project;

import com.acltabontabon.vortex.core.shared.ProjectId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A service that Vortex performance-tests.
 *
 * <p>A project is deliberately thin. It carries identity and a pointer to where the portable
 * configuration lives; the configuration itself — operations, environments, workloads, thresholds —
 * is {@link ProjectConfiguration}, which round-trips to {@code vortex.yaml} and belongs in version
 * control next to the service it describes.
 *
 * @param id             stable identifier
 * @param name           service name, e.g. {@code checkout-service}
 * @param description    what the service does
 * @param workspacePath  directory holding {@code .vortex/vortex.yaml}; empty when the project is
 *                       managed entirely inside the Vortex workspace
 * @param serviceVersion the version under test, recorded with executions so results can be compared
 *                       across releases
 * @param createdAt      when the project was created (UTC)
 * @param updatedAt      when the project was last changed (UTC)
 */
public record Project(
        ProjectId id,
        String name,
        String description,
        String workspacePath,
        String serviceVersion,
        Instant createdAt,
        Instant updatedAt) {

    public static final int MAX_NAME_LENGTH = 80;

    public Project {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        name = requireName(name);
        description = description == null ? "" : description.trim();
        workspacePath = workspacePath == null ? "" : workspacePath.trim();
        serviceVersion = serviceVersion == null ? "" : serviceVersion.trim();
    }

    public static Project create(String name, String description, String workspacePath, Instant now) {
        return new Project(ProjectId.generate(), name, description, workspacePath, "", now, now);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("project name must not be blank");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "project name must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    public Optional<String> serviceVersionIfPresent() {
        return serviceVersion.isBlank() ? Optional.empty() : Optional.of(serviceVersion);
    }

    public Optional<String> workspacePathIfPresent() {
        return workspacePath.isBlank() ? Optional.empty() : Optional.of(workspacePath);
    }

    public Project withDetails(String newName, String newDescription, String newServiceVersion,
            Instant now) {
        return new Project(id, newName, newDescription, workspacePath, newServiceVersion, createdAt, now);
    }

    public Project touch(Instant now) {
        return new Project(id, name, description, workspacePath, serviceVersion, createdAt, now);
    }
}
