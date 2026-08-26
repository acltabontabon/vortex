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

class DynatraceMcpConnectionTestTest {

    /** A port nothing listens on — connection refused, deterministically and fast. */
    private static final String UNREACHABLE_URI = "https://127.0.0.1:1/mcp";

    private HttpServer tokenServer;

    @AfterEach
    void stopServer() {
        if (tokenServer != null) {
            tokenServer.stop(0);
        }
    }

    private DynatraceOAuthTokenProvider tokenProviderAnsweringWith(String body, int status) throws Exception {
        tokenServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tokenServer.createContext("/token", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
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
    void headerModeFailsAtMcpServerReachableForAnUnreachableEndpoint() {
        var connectionTest = new DynatraceMcpConnectionTest();

        var report = connectionTest.run(UNREACHABLE_URI, Map.of(), Duration.ofSeconds(2));

        assertThat(report.succeeded()).isFalse();
        assertThat(report.stages()).hasSize(1);
        assertThat(report.stages().get(0).stage()).isEqualTo("MCP server reachable");
        assertThat(report.stages().get(0).succeeded()).isFalse();
    }

    @Test
    void oauthModeStopsAtTokenFetchWhenSsoRejectsTheClient() throws Exception {
        var provider = tokenProviderAnsweringWith(
                """
                {"error":"invalid_client"}""", 401);
        var connectionTest = new DynatraceMcpConnectionTest(provider);
        var oauth = new DynatraceMcpConnectionTest.OAuthCredentials("bad-client", "bad-secret", "", "");

        var report = connectionTest.run(UNREACHABLE_URI, Map.of(), Duration.ofSeconds(2), oauth);

        assertThat(report.succeeded()).isFalse();
        assertThat(report.stages()).hasSize(1);
        assertThat(report.stages().get(0).stage()).isEqualTo("OAuth token obtained");
        assertThat(report.stages().get(0).succeeded()).isFalse();
        assertThat(report.stages().get(0).category()).isEqualTo(DynatraceMcpFailureCategory.AUTHENTICATION_FAILED);
    }

    @Test
    void oauthModeProceedsToMcpStagesOnceATokenIsObtained() throws Exception {
        var provider = tokenProviderAnsweringWith(
                """
                {"token_type":"Bearer","access_token":"abc123","expires_in":300}""", 200);
        var connectionTest = new DynatraceMcpConnectionTest(provider);
        var oauth = new DynatraceMcpConnectionTest.OAuthCredentials("client-1", "secret-1", "", "");

        var report = connectionTest.run(UNREACHABLE_URI, Map.of(), Duration.ofSeconds(2), oauth);

        assertThat(report.succeeded()).isFalse();
        assertThat(report.stages()).hasSize(2);
        assertThat(report.stages().get(0).stage()).isEqualTo("OAuth token obtained");
        assertThat(report.stages().get(0).succeeded()).isTrue();
        assertThat(report.stages().get(1).stage()).isEqualTo("MCP server reachable");
        assertThat(report.stages().get(1).succeeded()).isFalse();
    }

    @Test
    void runBridgeFailsAtLocalBridgeStartedForAnInvalidEndpointWithoutSpawningAnything() {
        // A non-https URL fails DynatraceMcpEndpoint's own validation before any process is
        // spawned — this is the one runBridge path exercisable without a real npx/mcp-remote/browser,
        // which the rest of this connection mode genuinely needs (see docs/adr/adr-051-...).
        var connectionTest = new DynatraceMcpConnectionTest();

        var report = connectionTest.runBridge("http://dynatrace-mcp.internal/mcp", Duration.ofSeconds(2));

        assertThat(report.succeeded()).isFalse();
        assertThat(report.stages()).hasSize(1);
        assertThat(report.stages().get(0).stage()).isEqualTo("Local bridge started");
        assertThat(report.stages().get(0).succeeded()).isFalse();
    }

    @Test
    void theThreeArgOverloadBehavesIdenticallyToPassingNullOAuthCredentials() {
        var connectionTest = new DynatraceMcpConnectionTest();

        var viaOverload = connectionTest.run(UNREACHABLE_URI, Map.of(), Duration.ofSeconds(2));
        var viaExplicitNull = connectionTest.run(UNREACHABLE_URI, Map.of(), Duration.ofSeconds(2), null);

        assertThat(viaOverload.stages()).isEqualTo(viaExplicitNull.stages());
    }
}
