package com.acltabontabon.vortex.dynatrace;

import java.time.Duration;
import java.util.Objects;

/**
 * Where and how to reach the Dynatrace MCP server: a plain HTTPS URL Vortex's local bridge connects
 * to.
 *
 * <p>Never a command, even though a config a user was handed for a different client is typically
 * shaped like {@code {"command": "npx", "args": ["mcp-remote", "<url>"]}} — Vortex only ever takes
 * the URL a person enters directly under Settings.
 *
 * @param uri     the MCP server's base URL, e.g. {@code https://dynatrace-mcp.internal/mcp}
 * @param timeout the per-call bound for every MCP request this endpoint makes
 */
public record DynatraceMcpEndpoint(String uri, Duration timeout) {

    public DynatraceMcpEndpoint {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(timeout, "timeout");
        uri = uri.trim();
        if (uri.isBlank()) {
            throw new IllegalArgumentException("a Dynatrace MCP endpoint needs a URL");
        }
        if (!uri.startsWith("https://")) {
            throw new IllegalArgumentException(
                    "the Dynatrace MCP endpoint must be an absolute https URL but was: " + uri);
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("a query timeout of " + timeout + " covers no request");
        }
    }
}
