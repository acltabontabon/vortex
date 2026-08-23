package dev.vortex.app.adapter.target.docker;

import java.time.Duration;

/**
 * The two readiness signals {@link DockerImageTargetExecutor} polls: a bare TCP connect (the
 * mandatory, always-applied layer) and, only when a target configures one, an HTTP path/status
 * check on top. A separate, fakeable seam so tests can exercise the timeout/retry logic in {@link
 * DockerImageTargetExecutor} without opening real sockets or making real HTTP calls.
 */
public interface TargetReadinessProbe {

    /** One attempt at a raw TCP connect — no retrying here, the caller polls. */
    boolean tcpPortIsReachable(String host, int port, Duration connectTimeout);

    /** One attempt at the configured HTTP check — no retrying here, the caller polls. */
    boolean httpCheckSucceeds(String host, int port, String path, int expectedStatus,
            Duration requestTimeout);
}
