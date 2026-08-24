package com.acltabontabon.vortex.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acltabontabon.vortex.core.project.OpenApiSource;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Fetching the document an {@link OpenApiSource} names, for the "Add service" onboarding flow that
 * reconciles operations from a discovered {@code vortex.yaml}.
 */
@DisplayName("fetching a workspace-relative OpenAPI document")
class WorkspaceDocumentFetchTest {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a file relative to the repository is read")
    void readsAFileUnderTheRepository(@TempDir Path repository) throws IOException {
        Files.writeString(repository.resolve("openapi.yaml"), "openapi: 3.0.3");

        String content = WorkspaceDocumentFetch.fetch(CLIENT, repository.toString(),
                new OpenApiSource.File("openapi.yaml"), 1_000);

        assertThat(content).isEqualTo("openapi: 3.0.3");
    }

    @Test
    @DisplayName("a file reference escaping the repository is refused before anything is read")
    void rejectsAnEscapingReference(@TempDir Path repository) {
        assertThatThrownBy(() -> WorkspaceDocumentFetch.fetch(CLIENT, repository.toString(),
                new OpenApiSource.File("../outside.yaml"), 1_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a missing file is reported rather than throwing an unhandled exception")
    void reportsAMissingFile(@TempDir Path repository) {
        assertThatThrownBy(() -> WorkspaceDocumentFetch.fetch(CLIENT, repository.toString(),
                new OpenApiSource.File("openapi.yaml"), 1_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("could not find");
    }

    @Test
    @DisplayName("a file over the cap is rejected without buffering it all")
    void rejectsAnOversizedFile(@TempDir Path repository) throws IOException {
        Files.writeString(repository.resolve("openapi.yaml"), "x".repeat(200));

        assertThatThrownBy(() -> WorkspaceDocumentFetch.fetch(CLIENT, repository.toString(),
                new OpenApiSource.File("openapi.yaml"), 16))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("That document is larger than Vortex will import.");
    }

    @Test
    @DisplayName("a URL source delegates to the ordinary HTTP fetch")
    void delegatesAUrlSourceToHttpFetch(@TempDir Path repository) throws IOException {
        server = respond(200, "openapi: 3.0.3".getBytes(StandardCharsets.UTF_8));

        String content = WorkspaceDocumentFetch.fetch(CLIENT, repository.toString(),
                new OpenApiSource.Url(urlOf(server)), 1_000);

        assertThat(content).isEqualTo("openapi: 3.0.3");
    }

    private static HttpServer respond(int status, byte[] body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/spec", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/yaml");
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
