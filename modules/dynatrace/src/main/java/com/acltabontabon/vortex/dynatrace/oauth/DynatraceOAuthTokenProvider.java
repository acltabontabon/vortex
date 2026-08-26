package com.acltabontabon.vortex.dynatrace.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches and caches a bearer token from Dynatrace's OAuth 2.0 Client Credentials grant.
 *
 * <p>Machine-to-machine, no user in the loop: a backend service authenticates directly with a
 * confidential OAuth client's id and secret, matching Vortex's own headless architecture exactly —
 * the interactive Authorization Code grant an MCP client like Claude Code performs (open a browser,
 * catch a local callback) is deliberately not implemented here. See
 * {@code docs/adr/adr-050-dynatrace-mcp-oauth-client-credentials.adoc}.
 *
 * <p>Client Credentials issues no refresh token — an access token nearing expiry is simply
 * re-requested with the same client id and secret. The fetched token is cached in memory only, for
 * this process's lifetime, and is never written to disk, logged, or returned to a browser.
 */
public final class DynatraceOAuthTokenProvider {

    private static final URI DEFAULT_TOKEN_ENDPOINT = URI.create("https://sso.dynatrace.com/sso/oauth2/token");

    /** Refetch this long before the token actually expires, so a call never races an expiring token. */
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(30);

    private final URI tokenEndpoint;
    private final HttpClient httpClient;
    private final ObjectMapper json = new ObjectMapper();
    private final ConcurrentHashMap<CacheKey, CachedToken> cache = new ConcurrentHashMap<>();

    public DynatraceOAuthTokenProvider() {
        this(DEFAULT_TOKEN_ENDPOINT, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    /** Endpoint/client overridable for tests — never for production use. */
    DynatraceOAuthTokenProvider(URI tokenEndpoint, HttpClient httpClient) {
        this.tokenEndpoint = Objects.requireNonNull(tokenEndpoint, "tokenEndpoint");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
    }

    /**
     * A valid bearer token for these credentials, from cache when one is still fresh enough, otherwise
     * freshly fetched from the SSO token endpoint.
     *
     * @throws DynatraceOAuthException the token endpoint rejected the request, or could not be reached
     */
    public String bearerToken(String clientId, String resolvedClientSecret, String scope, String resource,
            Duration timeout) {
        CacheKey key = new CacheKey(clientId, resolvedClientSecret, scope, resource);
        CachedToken cached = cache.get(key);
        if (cached != null && cached.stillValid()) {
            return cached.accessToken();
        }
        CachedToken fetched = fetch(clientId, resolvedClientSecret, scope, resource, timeout);
        cache.put(key, fetched);
        return fetched.accessToken();
    }

    private CachedToken fetch(String clientId, String clientSecret, String scope, String resource,
            Duration timeout) {
        String body = formBody(clientId, clientSecret, scope, resource);
        HttpRequest request = HttpRequest.newBuilder(tokenEndpoint)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new DynatraceOAuthException(
                    "the Dynatrace SSO token endpoint did not respond in time", 0, "", e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new DynatraceOAuthException(
                    "could not reach the Dynatrace SSO token endpoint: " + e.getMessage(), 0, "", e);
        }

        if (response.statusCode() != 200) {
            throw new DynatraceOAuthException(
                    "the Dynatrace SSO token endpoint rejected the client credentials (HTTP "
                            + response.statusCode() + ")",
                    response.statusCode(), response.body(), null);
        }

        JsonNode payload;
        try {
            payload = json.readTree(response.body());
        } catch (IOException e) {
            throw new DynatraceOAuthException(
                    "the Dynatrace SSO token endpoint's response was not valid JSON",
                    response.statusCode(), response.body(), e);
        }

        String accessToken = payload.path("access_token").asText("");
        if (accessToken.isBlank()) {
            throw new DynatraceOAuthException(
                    "the Dynatrace SSO token endpoint's response had no access_token",
                    response.statusCode(), response.body(), null);
        }
        long expiresInSeconds = payload.path("expires_in").asLong(0);
        Instant expiresAt = expiresInSeconds > 0
                ? Instant.now().plusSeconds(expiresInSeconds)
                : Instant.now().plus(REFRESH_MARGIN); // no expires_in: don't cache past this call

        return new CachedToken(accessToken, expiresAt);
    }

    private static String formBody(String clientId, String clientSecret, String scope, String resource) {
        List<String> parameters = new ArrayList<>();
        parameters.add(parameter("grant_type", "client_credentials"));
        parameters.add(parameter("client_id", clientId));
        parameters.add(parameter("client_secret", clientSecret));
        if (scope != null && !scope.isBlank()) {
            parameters.add(parameter("scope", scope));
        }
        if (resource != null && !resource.isBlank()) {
            parameters.add(parameter("resource", resource));
        }
        return String.join("&", parameters);
    }

    private static String parameter(String name, String value) {
        return name + "=" + java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private record CacheKey(String clientId, String clientSecret, String scope, String resource) {
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
        boolean stillValid() {
            return Instant.now().isBefore(expiresAt.minus(REFRESH_MARGIN));
        }
    }
}
