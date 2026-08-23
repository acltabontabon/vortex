package dev.vortex.core.environment;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

/**
 * The base URL a test targets.
 *
 * <p>Validated on construction so that a malformed or unsupported target is rejected at
 * configuration time rather than surfacing as an opaque failure from the load generator. Only
 * {@code http} and {@code https} are accepted: Vortex drives HTTP services, and accepting other
 * schemes would only widen the surface for mistakes.
 */
public record TargetUrl(String value) {

    public TargetUrl {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("target URL must not be blank");
        }
        value = value.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "target URL is not a valid URL: " + value + " (" + e.getReason() + ")", e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(
                    "target URL must start with http:// or https:// but was: " + value);
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("target URL must include a host but was: " + value);
        }
    }

    public static TargetUrl of(String value) {
        return new TargetUrl(value);
    }

    public URI uri() {
        return URI.create(value);
    }

    public String host() {
        return Objects.requireNonNullElse(uri().getHost(), "");
    }

    public int port() {
        URI uri = uri();
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /** Whether the host is a loopback name or address. */
    public boolean isLoopback() {
        String host = host().toLowerCase(Locale.ROOT);
        return host.equals("localhost")
                || host.equals("127.0.0.1")
                || host.startsWith("127.")
                || host.equals("::1")
                || host.equals("[::1]")
                || host.endsWith(".localhost");
    }

    /** Replaces the host, preserving scheme, port and path. Used by the Docker runner. */
    public TargetUrl withHost(String newHost) {
        URI uri = uri();
        StringBuilder rebuilt = new StringBuilder(uri.getScheme()).append("://").append(newHost);
        if (uri.getPort() != -1) {
            rebuilt.append(':').append(uri.getPort());
        }
        if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
            rebuilt.append(uri.getRawPath());
        }
        return new TargetUrl(rebuilt.toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
