package com.acltabontabon.vortex.dynatrace;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Opens a fresh {@link DynatraceTelemetryClient} per call and nothing more.
 *
 * <p>No pooling, no cached connection across requests. Settings can change between one click of
 * "Test connection" and the next, and a cached session would either serve a stale configuration or
 * need its own invalidation logic — a per-call handshake is a small, bounded cost next to that
 * complexity, and it guarantees there is never an MCP session left open when Vortex is not actively
 * using it.
 *
 * <p>Not {@code final}: tests exercising {@code DynatraceMcpObservationSource} against a fake
 * {@link DynatraceTelemetryClient} (no real MCP server needed) subclass this and override
 * {@link #openIfConfigured()} — the same "swap the seam, not the domain" reason
 * {@code DynatraceTelemetryClient} exists as an interface at all.
 */
public class DynatraceMcpClientFactory {

    private final DynatraceMcpSettings settings;

    public DynatraceMcpClientFactory(DynatraceMcpSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Opens a client against the currently-saved global settings, or {@code null} when Dynatrace MCP
     * is disabled or has no endpoint configured — callers treat that as "not configured" rather than
     * attempting a connection that cannot succeed.
     */
    public DynatraceTelemetryClient openIfConfigured() {
        if (!settings.enabled() || settings.endpoint().isBlank()) {
            return null;
        }
        return openBridge(settings.endpoint(), settings.queryTimeout(), null);
    }

    /** Opens a client that speaks MCP over a locally-spawned {@code npx mcp-remote <uri>} process
     *  instead of connecting directly — see {@link DynatraceMcpBridgeTelemetryClient}. {@code
     *  onAuthPrompt}, if given, is invoked with an actionable message the moment a
     *  browser-authorization prompt is seen on the child process's stderr — including when this call
     *  ultimately throws. */
    public DynatraceTelemetryClient openBridge(String uri, Duration timeout, Consumer<String> onAuthPrompt) {
        return new DynatraceMcpBridgeTelemetryClient(new DynatraceMcpEndpoint(uri, timeout), onAuthPrompt);
    }
}
