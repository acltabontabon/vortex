package com.acltabontabon.vortex.app.adapter.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.acltabontabon.vortex.core.metrics.Aggregation;
import com.acltabontabon.vortex.core.metrics.MetricObservation;
import com.acltabontabon.vortex.core.metrics.MetricSource;
import com.acltabontabon.vortex.core.metrics.MetricUnit;
import com.acltabontabon.vortex.core.metrics.ObservationProvenance;
import com.acltabontabon.vortex.core.metrics.TelemetryAvailability;
import com.acltabontabon.vortex.core.metrics.TelemetryGap;
import com.acltabontabon.vortex.core.port.ObservabilityProvider;
import com.acltabontabon.vortex.core.resource.ResourceKind;
import com.acltabontabon.vortex.core.resource.ResourceLimit;
import com.acltabontabon.vortex.core.resource.ResourceScope;
import com.acltabontabon.vortex.core.resource.ResourceSignal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Watches the service under test through Prometheus while a run is in progress.
 *
 * <h2>Which measurements, and why these</h2>
 * Chosen because between them they separate one explanation from another, not because Prometheus
 * exposes them. When latency rises there are only a few families of answer — the machine ran out of
 * something, work queued somewhere, or a dependency got slower — and the set below is the smallest
 * one that can tell those apart:
 *
 * <ul>
 *   <li><strong>Saturation</strong> — CPU, heap, connection-pool utilisation. Something ran out.</li>
 *   <li><strong>Queueing</strong> — pending pool acquisitions, executor queue depth. Work is waiting
 *       for a resource that has not run out yet but cannot keep up.</li>
 *   <li><strong>Dependency health</strong> — downstream call latency and error share. The service is
 *       fine and is waiting for something else.</li>
 * </ul>
 *
 * <p>Adding more would produce a longer report, not a more answerable one. The question comes first.
 *
 * <h2>Correlation</h2>
 * Prometheus is queryable by window and offers nothing to mark a run in its own timeline, so this
 * provider reports {@link CorrelationCapability#QUERY_ONLY} permanently. That is a fact about
 * Prometheus, not a shortfall in the integration.
 */
public final class PrometheusObservabilityProvider implements ObservabilityProvider {

    private static final Logger log =
            LoggerFactory.getLogger(PrometheusObservabilityProvider.class);

    private static final String QUERY_PATH = "/api/v1/query";

    /**
     * The measurements Vortex asks for, as instant PromQL expressions.
     *
     * <p>Expressions rather than metric names because a bare counter is not a signal: what separates
     * one explanation from another is a rate, a ratio or a utilisation, and computing those in
     * Prometheus is both cheaper and more honest than deriving them from two half-read numbers here.
     */
    static final Map<String, Signal> SIGNALS = signals();

    private static Map<String, Signal> signals() {
        Map<String, Signal> signals = new LinkedHashMap<>();
        signals.put("system.cpu.utilization", new Signal(
                "CPU utilisation",
                "100 * (1 - avg(rate(node_cpu_seconds_total{mode=\"idle\"}[1m])))",
                MetricUnit.PERCENT,
                ResourceKind.CPU, ResourceScope.SYSTEM_UNDER_TEST,
                ResourceLimit.inherentToPercentage()));
        signals.put("jvm.memory.utilization", new Signal(
                "Heap utilisation",
                "100 * max(jvm_memory_used_bytes{area=\"heap\"}) "
                        + "/ max(jvm_memory_max_bytes{area=\"heap\"})",
                MetricUnit.PERCENT,
                ResourceKind.RUNTIME_MEMORY, ResourceScope.SYSTEM_UNDER_TEST,
                ResourceLimit.inherentToPercentage()));
        signals.put("pool.connections.utilization", new Signal(
                "Connection pool utilisation",
                "100 * max(hikaricp_connections_active) / max(hikaricp_connections_max)",
                MetricUnit.PERCENT,
                ResourceKind.POOL, ResourceScope.SYSTEM_UNDER_TEST,
                ResourceLimit.inherentToPercentage()));
        // Typed, but with no limit: how many callers are waiting is a real queue measurement, and
        // nothing here says how many waiting is too many. Reporting a limit would be inventing one.
        signals.put("pool.connections.pending", new Signal(
                "Connections waiting to be acquired",
                "max(hikaricp_connections_pending)",
                MetricUnit.COUNT,
                ResourceKind.QUEUE, ResourceScope.SYSTEM_UNDER_TEST, null));
        signals.put("executor.queued", new Signal(
                "Tasks queued for the executor",
                "max(executor_queued_tasks)",
                MetricUnit.COUNT,
                ResourceKind.QUEUE, ResourceScope.SYSTEM_UNDER_TEST, null));
        // Deliberately unclassified. A dependency's latency is a measurement about something else,
        // and the DEPENDENCY scope is reserved for a signal describing a dependency's own resources
        // rather than the service's experience of calling it.
        signals.put("dependency.latency.p95", new Signal(
                "Downstream call latency, p95",
                "1000 * histogram_quantile(0.95, "
                        + "sum by (le) (rate(http_client_requests_seconds_bucket[1m])))",
                MetricUnit.MILLISECONDS,
                null, null, null));
        signals.put("dependency.errors", new Signal(
                "Downstream call error share",
                "100 * sum(rate(http_client_requests_seconds_count{outcome=~\"SERVER_ERROR|CLIENT_ERROR\"}[1m])) "
                        + "/ clamp_min(sum(rate(http_client_requests_seconds_count[1m])), 1)",
                MetricUnit.PERCENT,
                null, null, null));
        return Map.copyOf(signals);
    }

    /**
     * One thing worth knowing, the expression that answers it, and what sort of thing it is.
     *
     * <p>Classification lives here because this table is the only place that knows what the
     * expression above actually measures. {@code vortex-core} reasons over {@link ResourceKind} and
     * never over a metric name, so a team renaming a recording rule changes this file and nothing
     * else. A signal with no {@code kind} stays an ordinary observation: collected, aligned, cited,
     * exported and rendered, but never a limiting-resource claim.
     */
    record Signal(String name, String expression, MetricUnit unit,
            ResourceKind kind, ResourceScope scope, ResourceLimit limit) {

        boolean isClassified() {
            return kind != null && scope != null;
        }
    }

    private final RestClient client;
    private final String endpoint;

    /**
     * @param endpoint the Prometheus API root; this provider is inert without one, because the
     *                 service under test's own address says nothing about where its metrics are
     *                 scraped to
     */
    public PrometheusObservabilityProvider(RestClient.Builder builder, String endpoint) {
        this.client = builder.requestFactory(timeoutFactory()).build();
        this.endpoint = endpoint == null ? "" : endpoint.trim();
    }

    private static ClientHttpRequestFactory timeoutFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }

    @Override
    public String id() {
        return "prometheus";
    }

    @Override
    public List<String> defaultMetrics() {
        return List.copyOf(SIGNALS.keySet());
    }

    @Override
    public boolean isAvailable(ObservabilityQuery query) {
        if (endpoint.isBlank()) {
            return false;
        }
        try {
            client.get()
                    .uri(endpoint + "/-/ready")
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            log.debug("Prometheus not reachable at {}: {}", endpoint, e.getMessage());
            return false;
        }
    }

    @Override
    public Collected collect(ObservabilityQuery query) {
        List<MetricObservation> observations = new ArrayList<>();
        List<TelemetryGap> gaps = new ArrayList<>();
        List<ResourceSignal> resources = new ArrayList<>();

        for (Map.Entry<String, Signal> entry : SIGNALS.entrySet()) {
            String id = entry.getKey();
            Signal signal = entry.getValue();
            try {
                Double value = scalar(signal.expression());
                if (value == null) {
                    // A service that does not publish the underlying metric produces an empty
                    // result, not an error. That is missing telemetry with a nameable cause, and
                    // it is reported as such rather than defaulted to zero.
                    gaps.add(new TelemetryGap(id(), id, TelemetryAvailability.NO_DATA,
                            "the query matched no series"));
                    continue;
                }
                MetricObservation observation = new MetricObservation(
                        "metric:" + id, signal.name(), MetricSource.PROMETHEUS, signal.unit(),
                        Aggregation.MAX, value, query.window(), Map.of(),
                        new ObservationProvenance(id(), signal.expression(), "",
                                browseUrl(signal.expression())),
                        null);
                observations.add(observation);
                // Classified signals appear in both lists. The observation is what gets rendered,
                // cited and exported; the typed signal is what may reach a conclusion about a limit.
                if (signal.isClassified()) {
                    resources.add(new ResourceSignal(
                            observation, signal.kind(), signal.scope(), signal.limit()));
                }
            } catch (RuntimeException e) {
                gaps.add(new TelemetryGap(id(), id, classify(e),
                        e.getMessage() == null ? "" : e.getMessage()));
            }
        }

        return new Collected(observations, gaps, resources);
    }

    private TelemetryAvailability classify(RuntimeException failure) {
        if (failure instanceof org.springframework.web.client.HttpClientErrorException.Unauthorized
                || failure instanceof org.springframework.web.client.HttpClientErrorException.Forbidden) {
            return TelemetryAvailability.UNAUTHORIZED;
        }
        if (failure instanceof org.springframework.web.client.ResourceAccessException) {
            return TelemetryAvailability.UNREACHABLE;
        }
        if (failure instanceof org.springframework.web.client.HttpClientErrorException) {
            return TelemetryAvailability.MALFORMED;
        }
        return TelemetryAvailability.UNREACHABLE;
    }

    /** The single value of an instant query, or null when Prometheus had nothing to say. */
    private Double scalar(String expression) {
        JsonNode body = client.get()
                .uri(endpoint + QUERY_PATH + "?query="
                        + URLEncoder.encode(expression, StandardCharsets.UTF_8))
                .retrieve()
                .body(JsonNode.class);

        if (body == null || !"success".equals(body.path("status").asText())) {
            return null;
        }
        JsonNode result = body.path("data").path("result");
        if (!result.isArray() || result.isEmpty()) {
            return null;
        }
        JsonNode value = result.get(0).path("value");
        if (!value.isArray() || value.size() < 2) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(value.get(1).asText());
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String browseUrl(String expression) {
        return endpoint + "/graph?g0.expr="
                + URLEncoder.encode(expression, StandardCharsets.UTF_8) + "&g0.tab=0";
    }
}
