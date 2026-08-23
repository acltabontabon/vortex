package com.acltabontabon.vortex.core.target;

import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.shared.ProjectId;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * What a {@link com.acltabontabon.vortex.core.port.TargetExecutor} needs to prepare one run's target.
 *
 * @param statusSink    receives human-readable status text as preparation proceeds (e.g. "Docker
 *                      available", "Waiting for service readiness") — this is a status line for the
 *                      UI, carried through the existing {@code ExecutionProgress} message/stageLabel
 *                      fields rather than a new structured channel, so callers must not parse it.
 * @param workspacePath the service's checkout on this machine, blank when none is known. Needed to
 *                      resolve a {@code DockerComposeTarget}'s Compose file path against the
 *                      repository it belongs to ({@code
 *                      com.acltabontabon.vortex.core.lab.ComposeFileReference#resolveAgainst}); an executor that
 *                      does not need a workspace (e.g. {@code ExternalEndpointTargetExecutor},
 *                      {@code DockerImageTargetExecutor}) simply ignores it. Blank-defaulted, not
 *                      null-checked, the same treatment as {@code statusSink}'s no-op default —
 *                      absence here is routine, not a caller mistake.
 */
public record TargetPreparationRequest(ExecutionId executionId, ProjectId projectId,
        ExecutionTarget target, Consumer<String> statusSink, String workspacePath) {

    public TargetPreparationRequest {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(target, "target");
        statusSink = statusSink == null ? message -> { } : statusSink;
        workspacePath = workspacePath == null ? "" : workspacePath;
    }
}
