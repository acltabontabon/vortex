package com.acltabontabon.vortex.dynatrace;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Where and how to reach the Dynatrace MCP server: a plain HTTPS URL Vortex connects to directly.
 *
 * <p>Never a command. Whatever an SRE-provided config said to run locally (typically an
 * {@code npx mcp-remote <url>} bridge) is not represented here — {@link DynatraceMcpConfigImport}
 * exists specifically to extract {@code uri} out of that shape and discard the rest.
 *
 * @param uri     the MCP server's base URL, e.g. {@code https://sre-mcp-server.internal/mcp}
 * @param headers request headers, whose values may be {@code ${NAME}} references. Empty when the
 *                server needs no credential — the common case behind a VPN perimeter
 * @param timeout the per-call bound for every MCP request this endpoint makes
 */
public record DynatraceMcpEndpoint(String uri, Map<String, String> headers, Duration timeout) {

    public DynatraceMcpEndpoint {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(timeout, "timeout");
        uri = uri.trim();
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        if (uri.isBlank()) {
            throw new IllegalArgumentException("a Dynatrace MCP endpoint needs a URL");
        }
        if (!uri.startsWith("http://") && !uri.startsWith("https://")) {
            throw new IllegalArgumentException(
                    "the Dynatrace MCP endpoint must be an absolute http or https URL but was: " + uri);
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("a query timeout of " + timeout + " covers no request");
        }
    }
}
