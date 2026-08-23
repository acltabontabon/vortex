package dev.vortex.app.adapter.observability;

import com.fasterxml.jackson.databind.JsonNode;
import dev.vortex.core.metrics.Aggregation;
import dev.vortex.core.metrics.MetricObservation;
import dev.vortex.core.metrics.MetricSource;
import dev.vortex.core.metrics.MetricUnit;
import dev.vortex.core.metrics.ObservationProvenance;
import dev.vortex.core.metrics.TelemetryAvailability;
import dev.vortex.core.metrics.TelemetryGap;
import dev.vortex.core.port.ObservabilityProvider;
import dev.vortex.core.resource.ResourceKind;
import dev.vortex.core.resource.ResourceLimit;
import dev.vortex.core.resource.ResourceScope;
import dev.vortex.core.resource.ResourceSignal;
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
 * Watches the service under test through Dynatrace while a run is in progress.
 *
 * <h2>What Dynatrace contributes that Prometheus cannot</h2>
 * Topology. A Dynatrace service knows the services it calls, so a downstream signal arrives already
 * attributed to a named dependency rather than as an anonymous client-side histogram. That
 * attribution is carried in {@link MetricObservation#dimensions()}.
 *
 * <p>The abstraction is deliberately not flattened to hide this. Prometheus simply does not have it,
 * and pretending both providers are identical would mean discarding the more useful of the two
 * answers for the sake of symmetry.
 *
 * <h2>Markers are enrichment, never a precondition</h2>
 * Dynatrace can be told a run is happening, which puts a marker on its own timeline and makes the
 * run navigable in its interface. Reading metrics and ingesting events need <em>different</em>
 * permissions, and a production tenant may well grant the first and refuse the second.
 *
 * <p>So marker creation is attempted, and its failure changes nothing about the telemetry. The
 * provider degrades to {@link CorrelationCapability#QUERY_ONLY}, records one honest gap saying why,
 * and goes on collecting. A measurement is never downgraded to missing because a marker could not be
 * written — that would discard real evidence over a permission that has nothing to do with it.
 */
public final class DynatraceObservabilityProvider implements ObservabilityProvider {

    private static final Logger log =
            LoggerFactory.getLogger(DynatraceObservabilityProvider.class);

    private static final String QUERY_PATH = "/api/v2/metrics/query";
    private static final String EVENTS_PATH = "/api/v2/events/ingest";

    /** Chosen to separate saturation from queueing from a slow dependency. See the sibling adapter. */
    static final Map<String, Signal> SIGNALS = signals();

    private static Map<String, Signal> signals() {
        Map<String, Signal> signals = new LinkedHashMap<>();
        signals.put("system.cpu.utilization", new Signal(
                "CPU utilisation", "builtin:host.cpu.usage:max", MetricUnit.PERCENT,
                ResourceKind.CPU, ResourceScope.SYSTEM_UNDER_TEST,
                ResourceLimit.inherentToPercentage()));
        signals.put("jvm.memory.utilization", new Signal(
                "Heap utilisation", "builtin:tech.jvm.memory.pool.utilization:max",
                MetricUnit.PERCENT,
                ResourceKind.RUNTIME_MEMORY, ResourceScope.SYSTEM_UNDER_TEST,
                ResourceLimit.inherentToPercentage()));
        signals.put("pool.connections.utilization", new Signal(
                "Connection pool utilisation",
                "builtin:tech.jvm.connectionPool.utilization:max", MetricUnit.PERCENT,
                ResourceKind.POOL, ResourceScope.SYSTEM_UNDER_TEST,
                ResourceLimit.inherentToPercentage()));
        // Unclassified for the same reason as in the Prometheus adapter: how long a dependency takes
        // to answer describes the dependency, not a resource of the service under test.
        signals.put("dependency.latency.p95", new Signal(
                "Downstream call latency, p95",
                "builtin:service.response.time:percentile(95):splitBy(\"dt.entity.service\")",
                MetricUnit.MILLISECONDS, null, null, null));
        signals.put("dependency.errors", new Signal(
                "Downstream call failure rate",
                "builtin:service.errors.total.rate:max:splitBy(\"dt.entity.service\")",
                MetricUnit.PERCENT, null, null, null));
        return Map.copyOf(signals);
    }

    /**
     * One measurement, its Dynatrace selector, and what sort of resource it describes.
     *
     * <p>The selector syntax stays here for the same reason PromQL stays in the Prometheus adapter:
     * the core reasons over a {@link ResourceKind}, and knows nothing about {@code builtin:} names.
     */
    record Signal(String name, String selector, MetricUnit unit,
            ResourceKind kind, ResourceScope scope, ResourceLimit limit) {

        boolean isClassified() {
            return kind != null && scope != null;
        }
    }

    private final RestClient client;
    private final String endpoint;
    private final String entityId;

    public DynatraceObservabilityProvider(RestClient.Builder builder, String endpoint,
            String entityId) {
        this.client = builder.requestFactory(timeoutFactory()).build();
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.entityId = entityId == null ? "" : entityId.trim();
    }

    private static ClientHttpRequestFactory timeoutFactory() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }

    @Override
    public String id() {
        return "dynatrace";
    }

    @Override
    public List<String> defaultMetrics() {
        return List.copyOf(SIGNALS.keySet());
    }

    @Override
    public boolean isAvailable(ObservabilityQuery query) {
        if (endpoint.isBlank() || entityId.isBlank()) {
            return false;
        }
        try {
            client.get()
                    .uri(endpoint + "/api/v2/metrics?pageSize=1")
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            log.debug("Dynatrace not reachable at {}: {}", endpoint, e.getMessage());
            return false;
        }
    }

    @Override
    public Collected collect(ObservabilityQuery query) {
        List<MetricObservation> observations = new ArrayList<>();
        List<TelemetryGap> gaps = new ArrayList<>();
        List<ResourceSignal> resources = new ArrayList<>();

        // Attempted first, and its outcome deliberately kept apart from everything below. Scoped
        // to this one call rather than to the bean, so concurrent or successive runs sharing this
        // singleton never see another run's marker outcome.
        MarkOutcome marker = markRun(query);
        marker.gap().ifPresent(gaps::add);

        for (Map.Entry<String, Signal> entry : SIGNALS.entrySet()) {
            String id = entry.getKey();
            Signal signal = entry.getValue();
            try {
                Reading reading = read(signal, query);
                if (reading == null) {
                    gaps.add(new TelemetryGap(id(), id, TelemetryAvailability.NO_DATA,
                            "the selector matched no data for this entity"));
                    continue;
                }
                MetricObservation observation = new MetricObservation(
                        "metric:" + id, signal.name(), MetricSource.DYNATRACE, signal.unit(),
                        Aggregation.MAX, reading.value(), query.window(),
                        reading.dimensions(),
                        new ObservationProvenance(id(), signal.selector(), entityId,
                                endpoint + "/ui/entity/" + entityId),
                        null);
                observations.add(observation);
                if (signal.isClassified()) {
                    resources.add(new ResourceSignal(
                            observation, signal.kind(), signal.scope(), signal.limit()));
                }
            } catch (RuntimeException e) {
                gaps.add(new TelemetryGap(id(), id, classify(e),
                        e.getMessage() == null ? "" : e.getMessage()));
            }
        }

        return new Collected(observations, gaps, marker.capability(), resources);
    }

    /** What a marker attempt produced: the capability it demonstrated, and any gap to report. */
    private record MarkOutcome(CorrelationCapability capability,
            java.util.Optional<TelemetryGap> gap) {

        static MarkOutcome notAttempted() {
            return new MarkOutcome(CorrelationCapability.QUERY_ONLY, java.util.Optional.empty());
        }
    }

    /**
     * Tells Dynatrace a run is happening, if the credentials permit it.
     *
     * <p>The outcome is returned rather than recorded on the instance: this provider is a shared
     * singleton, and a capability that lived on the bean would leak one run's outcome into the
     * next concurrent or successive one asking the same question.
     */
    private MarkOutcome markRun(ObservabilityQuery query) {
        if (!query.correlation().isKnown()) {
            return MarkOutcome.notAttempted();
        }
        try {
            client.post()
                    .uri(endpoint + EVENTS_PATH)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "eventType", "CUSTOM_ANNOTATION",
                            "title", query.correlation().describe(),
                            "entitySelector", "type(SERVICE),entityId(" + entityId + ")",
                            "properties", Map.of(
                                    "vortex.execution", query.correlation()
                                            .executionIdIfPresent()
                                            .map(id -> id.value()).orElse(""),
                                    "vortex.plan", query.correlation()
                                            .fingerprintIfPresent()
                                            .map(Object::toString).orElse(""))))
                    .retrieve()
                    .toBodilessEntity();

            return new MarkOutcome(CorrelationCapability.EVENT_MARKERS, java.util.Optional.empty());

        } catch (RuntimeException e) {
            // The telemetry is unaffected. Only the ability to find this run in Dynatrace's own
            // timeline is lost, and saying so is more useful than silently not having it.
            log.debug("Dynatrace run markers unavailable: {}", e.getMessage());
            return new MarkOutcome(CorrelationCapability.QUERY_ONLY, java.util.Optional.of(
                    new TelemetryGap(id(), "run markers", classify(e),
                            "Dynatrace telemetry was collected, but run markers were not created "
                                    + "because the configured credentials do not permit event "
                                    + "ingestion. The run is still findable by its time window.")));
        }
    }

    private TelemetryAvailability classify(RuntimeException failure) {
        if (failure instanceof org.springframework.web.client.HttpClientErrorException.Unauthorized
                || failure instanceof org.springframework.web.client.HttpClientErrorException.Forbidden) {
            return TelemetryAvailability.UNAUTHORIZED;
        }
        if (failure instanceof org.springframework.web.client.HttpClientErrorException.NotFound) {
            return TelemetryAvailability.UNSUPPORTED;
        }
        if (failure instanceof org.springframework.web.client.ResourceAccessException) {
            return TelemetryAvailability.UNREACHABLE;
        }
        if (failure instanceof org.springframework.web.client.HttpClientErrorException) {
            return TelemetryAvailability.MALFORMED;
        }
        return TelemetryAvailability.UNREACHABLE;
    }

    /** One value, with whatever the split told us about which dependency it belongs to. */
    record Reading(double value, Map<String, String> dimensions) {
    }

    private Reading read(Signal signal, ObservabilityQuery query) {
        JsonNode body = client.get()
                .uri(endpoint + QUERY_PATH
                        + "?metricSelector=" + encode(signal.selector())
                        + "&entitySelector="
                        + encode("type(SERVICE),entityId(" + entityId + ")")
                        + "&from=" + encode(query.window().start().toString())
                        + "&to=" + encode(query.window().end().toString()))
                .retrieve()
                .body(JsonNode.class);

        return highest(body);
    }

    /**
     * The highest value across the returned series, with its dimension labels.
     *
     * <p>Package-private so recorded responses can drive it. The interesting behaviour is what Vortex
     * makes of a body somebody else wrote.
     */
    static Reading highest(JsonNode body) {
        if (body == null) {
            return null;
        }
        double best = Double.NEGATIVE_INFINITY;
        Map<String, String> dimensions = Map.of();

        for (JsonNode result : body.path("result")) {
            for (JsonNode series : result.path("data")) {
                for (JsonNode value : series.path("values")) {
                    if (value.isNull() || !value.isNumber()) {
                        continue;
                    }
                    if (value.asDouble() > best) {
                        best = value.asDouble();
                        dimensions = dependencyOf(series);
                    }
                }
            }
        }
        return best == Double.NEGATIVE_INFINITY ? null : new Reading(best, dimensions);
    }

    /**
     * Which downstream service a split series belongs to.
     *
     * <p>This is the topology Prometheus cannot supply. Carried as a dimension so a report can say
     * "the payments service got slower" rather than "a downstream call got slower".
     */
    private static Map<String, String> dependencyOf(JsonNode series) {
        JsonNode names = series.path("dimensionMap");
        if (names.isObject() && names.hasNonNull("dt.entity.service.name")) {
            return Map.of("dependency", names.path("dt.entity.service.name").asText(""));
        }
        return Map.of();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
