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

    private record State(boolean enabled, String endpoint, Map<String, String> headers, Duration defaultWindow) {
    }

    private final AtomicReference<State> state;
    private final Duration queryTimeout;

    public DynatraceMcpSettings(boolean enabled, String endpoint, Map<String, String> headers,
            Duration defaultWindow, Duration queryTimeout) {
        this.state = new AtomicReference<>(new State(enabled, normalize(endpoint),
                headers == null ? Map.of() : Map.copyOf(headers),
                defaultWindow == null ? Duration.ofDays(30) : defaultWindow));
        this.queryTimeout = queryTimeout == null ? Duration.ofSeconds(30) : queryTimeout;
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

    /** Fixed at startup, unlike the other fields — a mid-flight timeout change has no safe moment to apply. */
    public Duration queryTimeout() {
        return queryTimeout;
    }

    /** Takes effect on the next call. Does not itself persist anything — the caller's job. */
    public void reconfigure(boolean enabled, String endpoint, Map<String, String> headers, Duration defaultWindow) {
        state.set(new State(enabled, normalize(endpoint), headers == null ? Map.of() : Map.copyOf(headers),
                defaultWindow == null ? Duration.ofDays(30) : defaultWindow));
    }

    public Set<String> referencedSecretNames() {
        Set<String> names = new java.util.TreeSet<>();
        headers().values().forEach(value -> names.addAll(SecretReferences.referencedNames(value)));
        return names;
    }

    public Map<String, String> maskedHeaders() {
        Map<String, String> masked = new LinkedHashMap<>();
        headers().forEach((name, value) -> masked.put(name, SecretReferences.mask(value)));
        return masked;
    }

    private static String normalize(String endpoint) {
        return endpoint == null ? "" : endpoint.trim();
    }
}
