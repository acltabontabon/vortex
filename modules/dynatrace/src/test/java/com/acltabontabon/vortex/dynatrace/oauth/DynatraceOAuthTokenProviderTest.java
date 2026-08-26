package com.acltabontabon.vortex.dynatrace.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DynatraceOAuthTokenProviderTest {

    private HttpServer server;
    private AtomicInteger requestCount;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private DynatraceOAuthTokenProvider providerAnsweringWith(String responseBody, int statusCode)
            throws IOException {
        requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            requestCount.incrementAndGet();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/token");
        return newProvider(endpoint);
    }

    private static DynatraceOAuthTokenProvider newProvider(URI endpoint) {
        return new DynatraceOAuthTokenProvider(endpoint, HttpClient.newHttpClient());
    }

    @Test
    void aSuccessfulResponseYieldsTheAccessToken() throws Exception {
        var provider = providerAnsweringWith(
                """
                {"token_type":"Bearer","access_token":"abc123","expires_in":300}""", 200);

        String token = provider.bearerToken("client-1", "secret-1", "scope-a", "urn:dtaccount:x",
                Duration.ofSeconds(5));

        assertThat(token).isEqualTo("abc123");
    }

    @Test
    void aFreshTokenIsCachedRatherThanRefetched() throws Exception {
        var provider = providerAnsweringWith(
                """
                {"token_type":"Bearer","access_token":"abc123","expires_in":300}""", 200);

        provider.bearerToken("client-1", "secret-1", "", "", Duration.ofSeconds(5));
        provider.bearerToken("client-1", "secret-1", "", "", Duration.ofSeconds(5));

        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void anExpiredTokenTriggersARefetch() throws Exception {
        var provider = providerAnsweringWith(
                """
                {"token_type":"Bearer","access_token":"abc123","expires_in":1}""", 200);

        provider.bearerToken("client-1", "secret-1", "", "", Duration.ofSeconds(5));
        Thread.sleep(1100); // past expires_in=1s plus the 30s refresh margin is unnecessary here:
        // the cache treats anything within 30s of expiry as stale, and a 1s token is stale immediately
        // on the very next call, so no sleep is even required for correctness — kept short regardless.
        provider.bearerToken("client-1", "secret-1", "", "", Duration.ofSeconds(5));

        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    void differentCredentialsAreCachedSeparately() throws Exception {
        var provider = providerAnsweringWith(
                """
                {"token_type":"Bearer","access_token":"abc123","expires_in":300}""", 200);

        provider.bearerToken("client-1", "secret-1", "", "", Duration.ofSeconds(5));
        provider.bearerToken("client-2", "secret-2", "", "", Duration.ofSeconds(5));

        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    void aRejectionCarriesTheStatusAndBodyButNeverLogsTheSecretByConstruction() throws Exception {
        var provider = providerAnsweringWith(
                """
                {"error":"invalid_client","error_description":"client authentication failed"}""", 401);

        assertThatThrownBy(() -> provider.bearerToken("client-1", "top-secret-value", "", "",
                Duration.ofSeconds(5)))
                .isInstanceOfSatisfying(DynatraceOAuthException.class, e -> {
                    assertThat(e.httpStatus()).isEqualTo(401);
                    assertThat(e.responseBodySnippet()).contains("invalid_client");
                    assertThat(e.responseBodySnippet()).doesNotContain("top-secret-value");
                    assertThat(e.getMessage()).doesNotContain("top-secret-value");
                });
    }

    @Test
    void aMalformedResponseIsRejectedRatherThanGuessed() throws Exception {
        var provider = providerAnsweringWith("not json", 200);

        assertThatThrownBy(() -> provider.bearerToken("client-1", "secret-1", "", "", Duration.ofSeconds(5)))
                .isInstanceOf(DynatraceOAuthException.class);
    }

    @Test
    void aResponseWithNoAccessTokenIsRejected() throws Exception {
        var provider = providerAnsweringWith("""
                {"token_type":"Bearer","expires_in":300}""", 200);

        assertThatThrownBy(() -> provider.bearerToken("client-1", "secret-1", "", "", Duration.ofSeconds(5)))
                .isInstanceOf(DynatraceOAuthException.class);
    }

    @Test
    void aNetworkFailureIsClassifiedWithStatusZero() {
        // No server started at this port — connection refused.
        DynatraceOAuthTokenProvider provider = newProvider(URI.create("http://127.0.0.1:1/token"));

        assertThatThrownBy(() -> provider.bearerToken("client-1", "secret-1", "", "", Duration.ofSeconds(2)))
                .isInstanceOfSatisfying(DynatraceOAuthException.class,
                        e -> assertThat(e.httpStatus()).isZero());
    }
}
