package com.acltabontabon.vortex.dynatrace;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The global Dynatrace MCP connection: one endpoint, shared by every service that points its
 * production observation at {@code transport: mcp}.
 *
 * <p>Mutable at runtime, like {@code AiSettings} — a Settings-page Save takes effect on the next
 * call, without a restart.
 *
 * <p>Vortex reaches the endpoint through a locally-spawned {@code npx mcp-remote <endpoint>} bridge
 * (see {@code DynatraceMcpBridgeTelemetryClient}), which performs Dynatrace's own interactive OAuth
 * itself — a browser login on first use, cached and refreshed after. There is nothing else for
 * Vortex to authenticate: no header, no client credential.
 */
public final class DynatraceMcpSettings {

    private record State(boolean enabled, String endpoint, Duration defaultWindow) {
    }

    private final AtomicReference<State> state;
    private final Duration queryTimeout;

    public DynatraceMcpSettings(boolean enabled, String endpoint, Duration defaultWindow, Duration queryTimeout) {
        this.state = new AtomicReference<>(new State(enabled, normalize(endpoint),
                defaultWindow == null ? Duration.ofDays(30) : defaultWindow));
        this.queryTimeout = queryTimeout == null ? Duration.ofSeconds(30) : queryTimeout;
    }

    public boolean enabled() {
        return state.get().enabled();
    }

    public String endpoint() {
        return state.get().endpoint();
    }

    public Duration defaultWindow() {
        return state.get().defaultWindow();
    }

    /** Fixed at startup, unlike the other fields — a mid-flight timeout change has no safe moment to apply. */
    public Duration queryTimeout() {
        return queryTimeout;
    }

    /** Takes effect on the next call. Does not itself persist anything — the caller's job. */
    public void reconfigure(boolean enabled, String endpoint, Duration defaultWindow) {
        state.set(new State(enabled, normalize(endpoint), defaultWindow == null ? Duration.ofDays(30) : defaultWindow));
    }

    private static String normalize(String endpoint) {
        return endpoint == null ? "" : endpoint.trim();
    }
}
