package com.acltabontabon.vortex.app.adapter.observation;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.capacity.ObservationSource;
import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.ObservationRequest;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.ObservedOperation;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.Retrieval;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.Retrieved;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.web.client.RestClient;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * {@link PrometheusObservationSource} against a real, containerized Prometheus scraping a real
 * (embedded) HTTP target — the strongest test this adapter has: nothing here is a fixture or a fake,
 * only the target being scraped is synthetic, standing in for what would otherwise be the demo
 * service.
 *
 * <p>Gated with {@code @EnabledIf("dockerIsAvailable")}, the exact convention already established by
 * {@code DockerK6RunnerResourceEnforcementIntegrationTest} — this class name ends in {@code Test},
 * so it is picked up by the default surefire include pattern and simply skips itself where Docker
 * isn't available, rather than needing a separate failsafe/IT-suffix build phase.
 */
@EnabledIf("dockerIsAvailable")
class PrometheusObservationSourceIntegrationTest {

    private static final List<ObservedOperation> CATALOG = List.of(
            new ObservedOperation(OperationId.of("getOrder"), "GET", "/orders/{id}"),
            new ObservedOperation(OperationId.of("createOrder"), "POST", "/orders"));

    static boolean dockerIsAvailable() {
        try {
            Process process = new ProcessBuilder("docker", "info").redirectErrorStream(true).start();
            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            return exited && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** Serves hand-written Prometheus exposition text, incrementing its counters a little on every
     *  scrape so {@code rate()}/{@code increase()} have real growth to measure — a static snapshot
     *  would leave every rate query answering zero, which is not what this test is for. */
    private static HttpServer target;
    private static int targetPort;
    private static final AtomicLong getCount = new AtomicLong();
    private static final AtomicLong postCount = new AtomicLong();
    private static boolean publishHistogram = true;

    private static GenericContainer<?> prometheus;

    @BeforeAll
    static void startTargetAndPrometheus() throws Exception {
        target = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        targetPort = target.getAddress().getPort();
        target.createContext("/actuator/prometheus", exchange -> {
            long get = getCount.addAndGet(3);
            long post = postCount.addAndGet(1);
            String body = expositionText(get, post, publishHistogram);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; version=0.0.4");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        target.start();

        Testcontainers.exposeHostPorts(targetPort);

        String scrapeConfig = """
                global:
                  scrape_interval: 1s
                scrape_configs:
                  - job_name: checkout-service
                    metrics_path: /actuator/prometheus
                    static_configs:
                      - targets: ["host.testcontainers.internal:%d"]
                        labels:
                          application: checkout-service
                """.formatted(targetPort);

        // The official image runs as `nobody` — a copy without an explicit world-readable mode
        // (Transferable's own default is owner-only) leaves the file unreadable to that user.
        prometheus = new GenericContainer<>(DockerImageName.parse("prom/prometheus:v2.55.1"))
                .withExposedPorts(9090)
                .withCopyToContainer(
                        org.testcontainers.images.builder.Transferable.of(scrapeConfig, 0644),
                        "/etc/prometheus/prometheus.yml");
        prometheus.start();

        waitForScrapes();
    }

    @AfterAll
    static void stop() {
        if (prometheus != null) {
            prometheus.stop();
        }
        if (target != null) {
            target.stop(0);
        }
    }

    private static String expositionText(long get, long post, boolean histogram) {
        StringBuilder text = new StringBuilder();
        text.append("# TYPE http_server_requests_seconds_count counter\n");
        text.append("http_server_requests_seconds_count{application=\"checkout-service\",method=\"GET\",uri=\"/orders/{id}\"} ")
                .append(get).append('\n');
        text.append("http_server_requests_seconds_count{application=\"checkout-service\",method=\"POST\",uri=\"/orders\"} ")
                .append(post).append('\n');
        if (histogram) {
            text.append("# TYPE http_server_requests_seconds_bucket histogram\n");
            long total = get + post;
            text.append("http_server_requests_seconds_bucket{application=\"checkout-service\",method=\"GET\",uri=\"/orders/{id}\",le=\"0.1\"} ")
                    .append(Math.max(0, get - 1)).append('\n');
            text.append("http_server_requests_seconds_bucket{application=\"checkout-service\",method=\"GET\",uri=\"/orders/{id}\",le=\"+Inf\"} ")
                    .append(get).append('\n');
        }
        return text.toString();
    }

    /** Polls Prometheus's own API rather than sleeping a fixed guess, so the test is only as slow as
     *  the container actually needs to be. */
    private static void waitForScrapes() {
        RestClient client = RestClient.create();
        String endpoint = "http://localhost:" + prometheus.getMappedPort(9090);
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                String body = client.get()
                        .uri(endpoint + "/api/v1/query?query=" + java.net.URLEncoder.encode(
                                "http_server_requests_seconds_count", StandardCharsets.UTF_8))
                        .retrieve().body(String.class);
                if (body != null && body.contains("\"result\":[{")) {
                    // A handful of scrapes, not just one, so rate()/increase() have real growth to
                    // measure rather than a single point with nothing before it.
                    Thread.sleep(4000);
                    return;
                }
            } catch (RuntimeException | InterruptedException ignored) {
                // Prometheus not ready yet, or hasn't scraped yet — keep polling.
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new IllegalStateException("Prometheus never scraped the target within 30s");
    }

    private ObservationSource sourceForContainer() {
        return new ObservationSource(ObservationSource.Kind.PROMETHEUS, ObservationSource.Transport.REST,
                "http://localhost:" + prometheus.getMappedPort(9090), "checkout-service",
                Duration.ofSeconds(30), Map.of(), Map.of());
    }

    @Test
    @DisplayName("retrieve() against a real Prometheus produces a real observation")
    void retrieveProducesARealObservation() {
        var adapter = new PrometheusObservationSource(RestClient.builder());
        ObservationSource source = sourceForContainer();
        Instant end = Instant.now();
        TimeWindow window = new TimeWindow(end.minusSeconds(10), end);

        Retrieval retrieval = adapter.retrieve(new ObservationRequest(source, window, Duration.ofSeconds(2), CATALOG));

        assertThat(retrieval).as("retrieval").isInstanceOf(Retrieved.class);
        var observation = ((Retrieved) retrieval).observation();
        assertThat(observation.peakRate().asDouble()).isGreaterThan(0);
        assertThat(observation.averageRateIfPresent()).isPresent();
        assertThat(observation.averageRate().asDouble()).isGreaterThan(0);
        assertThat(observation.observedMixIfPresent()).isPresent();
        assertThat(observation.mixCoverageIfPresent()).isPresent();
    }

    @Test
    @DisplayName("verify() reports a real p95 latency when histogram buckets are published")
    void verifyReportsRealLatencyWhenHistogramExists() {
        publishHistogram = true;
        // Tests run in an unspecified order — if the "no histogram" scenario ran first and just
        // flipped this back on, give the target a couple of fresh scrapes before asking; Prometheus's
        // instant existence query answers from the latest sample, which can otherwise still be a
        // pre-flip one taken up to one scrape interval ago.
        sleepQuietly(2500);
        var adapter = new PrometheusObservationSource(RestClient.builder());
        ObservationSource source = sourceForContainer();
        Instant end = Instant.now();
        TimeWindow window = new TimeWindow(end.minusSeconds(10), end);

        Retrieval retrieval = adapter.verify(source, window, Duration.ofSeconds(2));

        assertThat(retrieval).isInstanceOf(Retrieved.class);
        String note = ((Retrieved) retrieval).observation().note();
        assertThat(note).contains("Histogram data found").contains("Diagnostic only, not saved");
        // Never leaks into the field ProductionObservation deliberately does not have.
        assertThat(((Retrieved) retrieval).observation().p95ObservedRate()).isNull();
    }

    @Test
    @DisplayName("verify() reports latency as not published when the histogram doesn't exist")
    void verifyReportsLatencyUnavailableWithoutHistogram() {
        publishHistogram = false;
        try {
            var adapter = new PrometheusObservationSource(RestClient.builder());
            ObservationSource source = sourceForContainer();
            // Give Prometheus a couple more scrapes without histogram lines before asking.
            sleepQuietly(3000);
            Instant end = Instant.now();
            TimeWindow window = new TimeWindow(end.minusSeconds(10), end);

            Retrieval retrieval = adapter.verify(source, window, Duration.ofSeconds(2));

            assertThat(retrieval).isInstanceOf(Retrieved.class);
            String note = ((Retrieved) retrieval).observation().note();
            assertThat(note).contains("not published");
        } finally {
            publishHistogram = true;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
