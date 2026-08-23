package dev.vortex.app.adapter.target.docker;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * The real readiness checks — a raw socket connect, and a plain GET issued through {@link
 * HttpClient}. No assumption that a target exposes any particular health path; the HTTP layer
 * only runs when a {@code DockerImageTarget} configures one.
 */
public final class SocketTargetReadinessProbe implements TargetReadinessProbe {

    @Override
    public boolean tcpPortIsReachable(String host, int port, Duration connectTimeout) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) connectTimeout.toMillis());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean httpCheckSucceeds(String host, int port, String path, int expectedStatus,
            Duration requestTimeout) {
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        HttpClient client = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + normalizedPath))
                .timeout(requestTimeout)
                .GET()
                .build();
        try {
            HttpResponse<Void> response =
                    client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == expectedStatus;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
