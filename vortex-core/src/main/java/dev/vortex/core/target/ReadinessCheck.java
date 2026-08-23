package dev.vortex.core.target;

import java.time.Duration;
import java.util.Objects;

/**
 * Optional on {@link DockerImageTarget} — absence is valid; "ready" then means the resolved port
 * accepts a TCP connection. No default HTTP path/status: many valid services don't return 200
 * from "/".
 */
public record ReadinessCheck(String path, int expectedStatus, Duration timeout) {

    public ReadinessCheck {
        Objects.requireNonNull(timeout, "timeout");
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("readiness path must not be blank");
        }
        if (expectedStatus < 100 || expectedStatus > 599) {
            throw new IllegalArgumentException("expected status must be a valid HTTP status code");
        }
    }
}
