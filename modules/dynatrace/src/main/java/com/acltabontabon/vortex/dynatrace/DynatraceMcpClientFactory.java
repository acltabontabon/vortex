package com.acltabontabon.vortex.dynatrace;

import com.acltabontabon.vortex.dynatrace.oauth.DynatraceOAuthTokenProvider;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final DynatraceOAuthTokenProvider oauth;

    public DynatraceMcpClientFactory(DynatraceMcpSettings settings) {
        this(settings, new DynatraceOAuthTokenProvider());
    }

    DynatraceMcpClientFactory(DynatraceMcpSettings settings, DynatraceOAuthTokenProvider oauth) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.oauth = Objects.requireNonNull(oauth, "oauth");
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
        if (settings.connectionMode() == DynatraceMcpSettings.ConnectionMode.LOCAL_NPX_BRIDGE) {
            return openBridge(settings.endpoint(), settings.queryTimeout(), null);
        }
        Map<String, String> headers = settings.authMode() == DynatraceMcpSettings.AuthMode.OAUTH_CLIENT_CREDENTIALS
                ? withOAuthBearer(settings.headers(), settings.clientId(), settings.clientSecret(),
                        settings.scope(), settings.resource(), settings.queryTimeout())
                : settings.headers();
        return open(settings.endpoint(), headers, settings.queryTimeout());
    }

    /** Opens a client against an explicit endpoint and pre-resolved headers — used to test a form
     *  before it is saved. Headers must already carry any OAuth bearer token the caller wants used;
     *  this overload never itself talks to Dynatrace's SSO endpoint. */
    public DynatraceTelemetryClient open(String uri, Map<String, String> headers, Duration timeout) {
        return new DynatraceMcpTelemetryClient(new DynatraceMcpEndpoint(uri, headers, timeout));
    }

    /** Opens a client that speaks MCP over a locally-spawned {@code npx mcp-remote <uri>} process
     *  instead of connecting directly — see {@link DynatraceMcpBridgeTelemetryClient}. Headers are
     *  never involved: {@code mcp-remote} performs its own OAuth. {@code onAuthPrompt}, if given, is
     *  invoked with an actionable message the moment a browser-authorization prompt is seen on the
     *  child process's stderr — including when this call ultimately throws. */
    public DynatraceTelemetryClient openBridge(String uri, Duration timeout, Consumer<String> onAuthPrompt) {
        return new DynatraceMcpBridgeTelemetryClient(new DynatraceMcpEndpoint(uri, Map.of(), timeout), onAuthPrompt);
    }

    /**
     * Resolves an OAuth Client Credentials bearer token against the given credentials, merging it into
     * {@code baseHeaders} as {@code Authorization} — used both by {@link #openIfConfigured()} and by
     * the Test Connection flow, which must be able to test unsaved OAuth credentials without a Save
     * first.
     */
    Map<String, String> withOAuthBearer(Map<String, String> baseHeaders, String clientId,
            String clientSecretRef, String scope, String resource, Duration timeout) {
        String resolvedSecret = DynatraceMcpSecretResolution.resolveValue(clientSecretRef);
        String token = oauth.bearerToken(clientId, resolvedSecret, scope, resource, timeout);
        Map<String, String> merged = new LinkedHashMap<>(baseHeaders);
        merged.put("Authorization", "Bearer " + token);
        return merged;
    }
}
