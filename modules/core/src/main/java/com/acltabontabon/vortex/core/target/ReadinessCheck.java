package com.acltabontabon.vortex.core.target;

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
        // A timeout no service could ever meet is not a stricter check, it is a check that always
        // fails — and it fails during preparation, so the run reports a target that never became
        // ready rather than a configuration that could not have worked.
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("a readiness timeout of " + timeout.toSeconds()
                    + "s leaves no time for the service to answer, so every run against this "
                    + "environment would fail before it measured anything. Give it the time this "
                    + "service actually needs to start — a JVM service on a fraction of a CPU core "
                    + "routinely needs 30s or more.");
        }
    }
}
