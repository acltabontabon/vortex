package com.acltabontabon.vortex.app.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.acltabontabon.vortex.ai.AiSettings;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.web.client.RestClient;

/**
 * {@code vortex.ai.timeout} must actually bound the Ollama HTTP call, not just be parsed and
 * forgotten — see {@link AiConfiguration#ollamaApi}. Never touches a real model: the server here is a
 * plain {@link HttpServer} that responds slower than the configured timeout.
 */
class AiConfigurationTest {

    @Test
    void aSlowServerIsAbandonedWithinTheConfiguredTimeout() throws IOException {
        Duration configuredTimeout = Duration.ofMillis(150);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            try {
                // Comfortably longer than the configured timeout, so the client must give up first.
                Thread.sleep(configuredTimeout.multipliedBy(10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
        });
        server.start();

        try {
            AiSettings settings = new AiSettings("ollama",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "any-model", configuredTimeout,
                    false);
            OllamaApi api = new AiConfiguration().ollamaApi(RestClient.builder(), settings);

            Instant start = Instant.now();
            assertThatThrownBy(() -> api.chat(OllamaApi.ChatRequest.builder("any-model").build()))
                    .isInstanceOf(RuntimeException.class);
            Duration elapsed = Duration.between(start, Instant.now());

            // Well under the server's 10x delay — proves the client gave up on its own configured
            // timeout rather than the request eventually completing (or the test hanging).
            assertThat(elapsed).isLessThan(configuredTimeout.multipliedBy(5));
        } finally {
            server.stop(0);
        }
    }
}
