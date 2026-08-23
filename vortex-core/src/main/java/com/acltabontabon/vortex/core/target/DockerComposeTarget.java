package com.acltabontabon.vortex.core.target;

import com.acltabontabon.vortex.core.lab.ComposeFileReference;
import java.util.Objects;

/** An attach-only reference into an existing Compose stack. Vortex never runs {@code up}/{@code down}. */
public record DockerComposeTarget(String composeFile, String serviceName, ContainerPort containerPort)
        implements ExecutionTarget {

    public DockerComposeTarget {
        Objects.requireNonNull(containerPort, "containerPort");
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("compose service name must not be blank");
        }
        serviceName = serviceName.trim();
        composeFile = ComposeFileReference.normalise(composeFile);
    }

    public TargetOwnership ownership() {
        return TargetOwnership.EXTERNAL;
    }

    public String summary() {
        return "Compose: " + serviceName + " (" + composeFile + ")";
    }
}
