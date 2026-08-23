package com.acltabontabon.vortex.core.target;

/** The port a managed container listens on. */
public record ContainerPort(int value) {

    public ContainerPort {
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException("container port must be between 1 and 65535");
        }
    }
}
