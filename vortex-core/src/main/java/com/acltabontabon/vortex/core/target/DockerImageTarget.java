package com.acltabontabon.vortex.core.target;

import java.util.Objects;
import java.util.Optional;

/** One Vortex-managed disposable container, built from an existing image Vortex never builds. */
public record DockerImageTarget(ImageReference image, ContainerPort containerPort,
        ResourceEnvelopeRequest resources, ReadinessCheck readinessCheck) implements ExecutionTarget {

    public DockerImageTarget {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(containerPort, "containerPort");
        resources = resources == null ? ResourceEnvelopeRequest.none() : resources;
        // readinessCheck stays nullable — absence is valid, see ReadinessCheck's doc comment
    }

    public TargetOwnership ownership() {
        return TargetOwnership.VORTEX_MANAGED;
    }

    public Optional<ReadinessCheck> readinessCheckIfPresent() {
        return Optional.ofNullable(readinessCheck);
    }

    public String summary() {
        return "Docker: " + image.value();
    }
}
