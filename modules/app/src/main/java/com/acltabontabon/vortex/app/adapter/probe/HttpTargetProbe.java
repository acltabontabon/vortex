package com.acltabontabon.vortex.app.adapter.probe;

import com.acltabontabon.vortex.core.application.PreflightService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Checks that a target responds before a test starts.
 *
 * <p>One request, generously timed out. The point is not to measure anything — it is to catch the
 * service that was never started, the port that was mistyped and the host that does not resolve,
 * before a fifteen-minute run produces fifteen minutes of connection errors.
 *
 * <p>Any HTTP response counts as reachable, including a 404. The base URL frequently has no handler
 * of its own, and treating that as a failure would block perfectly valid configurations.
 */
public final class HttpTargetProbe implements PreflightService.TargetProbe {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;

    public HttpTargetProbe() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // Redirects are not followed: knowing that the configured address itself answered
                // is the point, and a redirect to somewhere else would hide a misconfiguration.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public Optional<String> probe(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(REQUEST_TIMEOUT)
                    .header("User-Agent", "Vortex/preflight")
                    .build();

            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

            // Some servers reject HEAD outright; a GET confirms whether the service is really there.
            if (response.statusCode() == 405 || response.statusCode() == 501) {
                return probeWithGet(url);
            }
            return Optional.empty();
        } catch (java.net.ConnectException e) {
            return Optional.of("the connection was refused — the service may not be running");
        } catch (java.net.http.HttpConnectTimeoutException e) {
            return Optional.of("the connection timed out after " + CONNECT_TIMEOUT.toSeconds()
                    + " seconds");
        } catch (java.net.UnknownHostException e) {
            return Optional.of("the host name could not be resolved");
        } catch (IllegalArgumentException e) {
            return Optional.of("the address is not a valid URL");
        } catch (java.io.IOException e) {
            return Optional.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("the check was interrupted");
        }
    }

    private Optional<String> probeWithGet(String url) throws java.io.IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "Vortex/preflight")
                .build();
        client.send(request, HttpResponse.BodyHandlers.discarding());
        return Optional.empty();
    }
}
