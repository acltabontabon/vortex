package com.acltabontabon.vortex.dynatrace;

import java.util.Objects;

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
        return open(settings.endpoint(), settings.headers(), settings.queryTimeout());
    }

    /** Opens a client against an explicit endpoint — used to test a form before it is saved. */
    public DynatraceTelemetryClient open(String uri, java.util.Map<String, String> headers,
            java.time.Duration timeout) {
        return new DynatraceMcpTelemetryClient(new DynatraceMcpEndpoint(uri, headers, timeout));
    }
}
