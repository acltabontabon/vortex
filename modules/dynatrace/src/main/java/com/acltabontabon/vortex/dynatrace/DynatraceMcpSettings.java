package com.acltabontabon.vortex.dynatrace;

import com.acltabontabon.vortex.core.environment.SecretReferences;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The global Dynatrace MCP connection: one endpoint, shared by every service that points its
 * production observation at {@code transport: mcp}.
 *
 * <p>Mutable at runtime, like {@code AiSettings} — a Settings-page Save takes effect on the next
 * call, without a restart. Header values may be {@code ${NAME}} secret references; the resolved
 * value is never held here (see {@link DynatraceMcpSecretResolution}, which resolves it only at the
 * moment a connection is opened).
 */
public final class DynatraceMcpSettings {

    /** How the connection authenticates. Dynatrace's own recommended path is a static header. */
    public enum AuthMode {
        HEADER,
        OAUTH_CLIENT_CREDENTIALS
    }

    /**
     * How Vortex reaches the MCP endpoint at the transport level. {@link #LOCAL_NPX_BRIDGE} spawns
     * {@code npx mcp-remote <endpoint>} and speaks MCP over its stdio instead of connecting directly
     * — see {@code DynatraceMcpBridgeTelemetryClient}. {@code mcp-remote} performs Dynatrace's own
     * interactive OAuth itself (a browser login on first use, cached and refreshed after), so
     * {@link AuthMode}, {@link #headers()}, and the OAuth fields below are inert in that mode: they
     * are not read, sent, or otherwise consulted.
     */
    public enum ConnectionMode {
        DIRECT_HTTPS,
        LOCAL_NPX_BRIDGE
    }

    private record State(boolean enabled, String endpoint, Map<String, String> headers,
            Duration defaultWindow, AuthMode authMode, String clientId, String clientSecret,
            String scope, String resource, ConnectionMode connectionMode) {
    }

    private final AtomicReference<State> state;
    private final Duration queryTimeout;

    public DynatraceMcpSettings(boolean enabled, String endpoint, Map<String, String> headers,
            Duration defaultWindow, Duration queryTimeout, AuthMode authMode, String clientId,
            String clientSecret, String scope, String resource, ConnectionMode connectionMode) {
        this.state = new AtomicReference<>(new State(enabled, normalize(endpoint),
                headers == null ? Map.of() : Map.copyOf(headers),
                defaultWindow == null ? Duration.ofDays(30) : defaultWindow,
                authMode == null ? AuthMode.HEADER : authMode,
                clientId == null ? "" : clientId.trim(),
                clientSecret == null ? "" : clientSecret.trim(),
                scope == null ? "" : scope.trim(),
                resource == null ? "" : resource.trim(),
                connectionMode == null ? ConnectionMode.DIRECT_HTTPS : connectionMode));
        this.queryTimeout = queryTimeout == null ? Duration.ofSeconds(30) : queryTimeout;
    }

    /** Every existing caller means header auth over a direct connection — anything else is opted
     *  into explicitly, never inferred. */
    public DynatraceMcpSettings(boolean enabled, String endpoint, Map<String, String> headers,
            Duration defaultWindow, Duration queryTimeout) {
        this(enabled, endpoint, headers, defaultWindow, queryTimeout, AuthMode.HEADER, "", "", "", "",
                ConnectionMode.DIRECT_HTTPS);
    }

    public boolean enabled() {
        return state.get().enabled();
    }

    public String endpoint() {
        return state.get().endpoint();
    }

    public Map<String, String> headers() {
        return state.get().headers();
    }

    public Duration defaultWindow() {
        return state.get().defaultWindow();
    }

    public AuthMode authMode() {
        return state.get().authMode();
    }

    public String clientId() {
        return state.get().clientId();
    }

    /** Raw, may be a {@code ${NAME}} reference — never resolved here, same discipline as {@link #headers()}. */
    public String clientSecret() {
        return state.get().clientSecret();
    }

    public String scope() {
        return state.get().scope();
    }

    public String resource() {
        return state.get().resource();
    }

    public ConnectionMode connectionMode() {
        return state.get().connectionMode();
    }

    /** Fixed at startup, unlike the other fields — a mid-flight timeout change has no safe moment to apply. */
    public Duration queryTimeout() {
        return queryTimeout;
    }

    /** Takes effect on the next call. Does not itself persist anything — the caller's job. */
    public void reconfigure(boolean enabled, String endpoint, Map<String, String> headers,
            Duration defaultWindow, AuthMode authMode, String clientId, String clientSecret,
            String scope, String resource, ConnectionMode connectionMode) {
        state.set(new State(enabled, normalize(endpoint), headers == null ? Map.of() : Map.copyOf(headers),
                defaultWindow == null ? Duration.ofDays(30) : defaultWindow,
                authMode == null ? AuthMode.HEADER : authMode,
                clientId == null ? "" : clientId.trim(),
                clientSecret == null ? "" : clientSecret.trim(),
                scope == null ? "" : scope.trim(),
                resource == null ? "" : resource.trim(),
                connectionMode == null ? ConnectionMode.DIRECT_HTTPS : connectionMode));
    }

    public Set<String> referencedSecretNames() {
        Set<String> names = new java.util.TreeSet<>();
        headers().values().forEach(value -> names.addAll(SecretReferences.referencedNames(value)));
        names.addAll(SecretReferences.referencedNames(clientSecret()));
        return names;
    }

    public Map<String, String> maskedHeaders() {
        Map<String, String> masked = new LinkedHashMap<>();
        headers().forEach((name, value) -> masked.put(name, SecretReferences.mask(value)));
        return masked;
    }

    public String maskedClientSecret() {
        return SecretReferences.mask(clientSecret());
    }

    private static String normalize(String endpoint) {
        return endpoint == null ? "" : endpoint.trim();
    }
}
