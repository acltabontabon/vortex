package dev.vortex.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ServicesApiController} and {@link ConfigurationApiController} both let a user point Vortex
 * at an arbitrary http(s) URL to import — the response has to be bounded in memory regardless of what
 * that server sends, not just checked for size after being read in full.
 */
@DisplayName("fetching a specification document")
class SpecificationFetchTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("rejects an unsupported scheme without fetching anything")
    void rejectsAnUnsupportedScheme() {
        assertThatThrownBy(() -> SpecificationFetch.fetch(CLIENT, "ftp://example.com/spec.yaml", 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Vortex only fetches API descriptions over http or https.");
    }

    @Test
    @DisplayName("a document within the cap is returned in full")
    void returnsADocumentWithinTheCap() throws IOException {
        server = respond(200, "text/yaml", "openapi: 3.0.3".getBytes(StandardCharsets.UTF_8));

        String body = SpecificationFetch.fetch(CLIENT, urlOf(server), 1_000);

        assertThat(body).isEqualTo("openapi: 3.0.3");
    }

    @Test
    @DisplayName("a document over the cap is rejected without buffering it all")
    void rejectsAnOversizedDocument() throws IOException {
        int cap = 16;
        byte[] farTooMuch = "x".repeat(cap * 20).getBytes(StandardCharsets.UTF_8);
        server = respond(200, "text/yaml", farTooMuch);

        assertThatThrownBy(() -> SpecificationFetch.fetch(CLIENT, urlOf(server), cap))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("That document is larger than Vortex will import.");
    }

    @Test
    @DisplayName("an error status names the code rather than returning the error page as a document")
    void reportsAnErrorStatus() throws IOException {
        server = respond(503, "text/plain", "upstream unavailable".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> SpecificationFetch.fetch(CLIENT, urlOf(server), 1_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("returned HTTP 503");
    }

    @Test
    @DisplayName("a declared charset is honoured rather than assumed to be UTF-8")
    void honoursADeclaredCharset() throws IOException {
        // 0xE9 is 'é' in ISO-8859-1; interpreted as UTF-8 it would not decode to the same character.
        byte[] latin1 = { 'r', 'e', 's', 'u', 'm', (byte) 0xE9 };
        server = respond(200, "text/yaml; charset=iso-8859-1", latin1);

        String body = SpecificationFetch.fetch(CLIENT, urlOf(server), 1_000);

        assertThat(body).isEqualTo("resumé");
    }

    private static HttpServer respond(int status, String contentType, byte[] body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/spec", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        return server;
    }

    private static String urlOf(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/spec";
    }
}
