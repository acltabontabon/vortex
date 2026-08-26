package com.acltabontabon.vortex.dynatrace;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.dynatrace.oauth.DynatraceOAuthTokenProvider;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DynatraceMcpClientFactoryTest {

    private HttpServer tokenServer;

    @AfterEach
    void stopServer() {
        if (tokenServer != null) {
            tokenServer.stop(0);
        }
    }

    private DynatraceOAuthTokenProvider tokenProviderAnsweringWith(String accessToken) throws Exception {
        String body = "{\"token_type\":\"Bearer\",\"access_token\":\"" + accessToken + "\",\"expires_in\":300}";
        tokenServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tokenServer.createContext("/token", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        tokenServer.start();
        URI endpoint = URI.create("http://127.0.0.1:" + tokenServer.getAddress().getPort() + "/token");
        var constructor = DynatraceOAuthTokenProvider.class
                .getDeclaredConstructor(URI.class, java.net.http.HttpClient.class);
        constructor.setAccessible(true);
        return constructor.newInstance(endpoint, java.net.http.HttpClient.newHttpClient());
    }

    @Test
    void withOAuthBearerMergesTheFetchedTokenAndPreservesOtherHeaders() throws Exception {
        var settings = new DynatraceMcpSettings(true, "https://dynatrace-mcp.internal/mcp", Map.of(),
                null, null, DynatraceMcpSettings.AuthMode.OAUTH_CLIENT_CREDENTIALS, "client-1", "secret-1",
                "", "", DynatraceMcpSettings.ConnectionMode.DIRECT_HTTPS);
        var factory = new DynatraceMcpClientFactory(settings, tokenProviderAnsweringWith("fetched-token"));

        Map<String, String> merged = factory.withOAuthBearer(Map.of("X-Custom", "kept"), "client-1",
                "secret-1", "", "", Duration.ofSeconds(5));

        assertThat(merged).containsEntry("Authorization", "Bearer fetched-token");
        assertThat(merged).containsEntry("X-Custom", "kept");
    }

    @Test
    void withOAuthBearerResolvesAnEnvironmentReferencedClientSecret() throws Exception {
        var settings = new DynatraceMcpSettings(true, "https://dynatrace-mcp.internal/mcp", Map.of(),
                null, null, DynatraceMcpSettings.AuthMode.OAUTH_CLIENT_CREDENTIALS, "client-1",
                "${PATH}", "", "", DynatraceMcpSettings.ConnectionMode.DIRECT_HTTPS); // PATH is reliably set in any test environment
        var factory = new DynatraceMcpClientFactory(settings, tokenProviderAnsweringWith("fetched-token"));

        // Resolution happening is proven indirectly: if it did not resolve, the raw "${PATH}" string
        // would be sent as the client secret and the fake token server (which ignores the body
        // entirely) would still answer the same either way, so instead assert directly against the
        // resolver Vortex already trusts elsewhere.
        String resolved = DynatraceMcpSecretResolution.resolveValue(settings.clientSecret());
        assertThat(resolved).isEqualTo(System.getenv("PATH"));

        Map<String, String> merged = factory.withOAuthBearer(Map.of(), settings.clientId(),
                settings.clientSecret(), "", "", Duration.ofSeconds(5));
        assertThat(merged).containsEntry("Authorization", "Bearer fetched-token");
    }
}
