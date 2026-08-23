package dev.vortex.app.adapter.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vortex.core.metrics.Aggregation;
import dev.vortex.core.metrics.MetricObservation;
import dev.vortex.core.metrics.MetricSource;
import dev.vortex.core.metrics.ObservationProvenance;
import dev.vortex.core.metrics.TelemetryAvailability;
import dev.vortex.core.metrics.TelemetryGap;
import dev.vortex.core.metrics.MetricUnit;
import dev.vortex.core.metrics.TimeWindow;
import dev.vortex.core.port.ObservabilityProvider;
import dev.vortex.core.resource.ResourceKind;
import dev.vortex.core.resource.ResourceLimit;
import dev.vortex.core.resource.ResourceScope;
import dev.vortex.core.resource.ResourceSignal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Collects measurements from a service's own metrics endpoint.
 *
 * <p>This is what lets Vortex move beyond "latency rose" towards "latency rose, and the connection
 * pool was saturated at the same time". The load generator sees the service from outside; this sees
 * it from inside, and the correlation between the two is where a bottleneck hypothesis comes from.
 *
 * <h2>Why this is a real implementation of the general port</h2>
 * It would have been easier to special-case the bundled demo service. Implementing
 * {@link ObservabilityProvider} properly instead means the abstraction has been exercised by
 * something real before Prometheus or Dynatrace is added behind it — an interface with no
 * implementations is a guess, and one with a single implementation shaped around a single consumer
 * is usually the wrong guess.
 *
 * <h2>Scope</h2>
 * A small, fixed set of metrics, collected once per run. Vortex is not an observability platform and
 * deliberately has no query language: it consumes evidence from whatever the team already runs.
 *
 * <h2>Honesty about gaps</h2>
 * Only measurements that were actually read are returned. A metric the service does not publish is
 * absent, and absence flows through to the analysis as missing telemetry — which is far more useful
 * than a plausible number nobody measured.
 */
public final class ActuatorObservabilityProvider implements ObservabilityProvider {

    private static final Logger log = LoggerFactory.getLogger(ActuatorObservabilityProvider.class);

    /**
     * The metrics Vortex looks for, in Micrometer's naming.
     *
     * <p>Chosen because between them they cover the saturation points that most often limit a JVM
     * service: CPU, heap, and whatever bounded pool sits in front of a downstream dependency.
     */
    /** Where Actuator publishes its metric index. Also the base of every provenance link. */
    private static final String ACTUATOR_METRICS_PATH = "/actuator/metrics";

    public static final List<String> DEFAULT_METRICS = List.of(
            "system.cpu.usage",
            "process.cpu.usage",
            "jvm.memory.used",
            "jvm.memory.max",
            "jvm.threads.live",
            "hikaricp.connections.active",
            "hikaricp.connections.max",
            "hikaricp.connections.pending",
            "hikaricp.connections.acquire",
            "checkout.pool.utilization",
            "checkout.pool.active",
            "checkout.pool.pending",
            "checkout.pool.acquire");

    private final RestClient client;
    private final ObjectMapper json = new ObjectMapper();

    public ActuatorObservabilityProvider(RestClient.Builder builder) {
        this.client = builder
                .requestFactory(timeoutFactory())
                .build();
    }

    private static org.springframework.http.client.ClientHttpRequestFactory timeoutFactory() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        return factory;
    }

    @Override
    public String id() {
        return "actuator";
    }

    @Override
    public boolean isAvailable(ObservabilityQuery query) {
        try {
            client.get()
                    .uri(query.endpoint() + ACTUATOR_METRICS_PATH)
                    .accept(MediaType.ALL)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            log.debug("No metrics endpoint at {}: {}", query.endpoint(), e.getMessage());
            return false;
        }
    }

    @Override
    public List<String> defaultMetrics() {
        return DEFAULT_METRICS;
    }

    @Override
    public Collected collect(ObservabilityQuery query) {
        List<String> wanted = query.metricNames().isEmpty() ? DEFAULT_METRICS : query.metricNames();
        List<MetricObservation> observations = new ArrayList<>();
        List<TelemetryGap> gaps = new ArrayList<>();

        for (String metric : wanted) {
            Read result = read(query.endpoint(), metric);
            if (result.body() == null) {
                gaps.add(new TelemetryGap(id(), metric, result.availability(), result.detail()));
                continue;
            }
            var observation =
                    toObservation(query.endpoint(), metric, result.body(), query.window());
            if (observation.isPresent()) {
                observations.add(observation.get());
            } else {
                // The endpoint knows the metric but published no measurement for it. That is a
                // different problem from a metric the service does not have at all, and the reader
                // chasing it needs to be able to tell which they are looking at.
                gaps.add(TelemetryGap.of(id(), metric, TelemetryAvailability.NO_DATA));
            }
        }

        deriveHeapUtilisation(observations, query.window()).ifPresent(observations::add);
        derivePoolUtilisation(observations, query.window()).ifPresent(observations::add);

        return new Collected(observations, gaps, classify(observations));
    }

    /**
     * Which of the collected measurements are typed resources, and what limit each is measured
     * against.
     *
     * <p>Actuator is the one adapter that reads raw {@code used}/{@code max} pairs rather than a
     * ratio somebody else computed, so it is the one adapter that can publish a limit that is a fact
     * about the deployment rather than a fact about arithmetic: "3.9 GB of a 4 GB maximum heap"
     * instead of "97% of a hundred".
     *
     * <p>Both forms are classified. The derived percentages are what the pressure rule has always
     * actually fired on for a Spring service, and classifying only the absolute pair would silently
     * remove every constraint candidate this provider produces.
     */
    private List<ResourceSignal> classify(List<MetricObservation> observations) {
        List<ResourceSignal> resources = new ArrayList<>();
        for (MetricObservation observation : observations) {
            classificationOf(observation.id())
                    .map(classification -> new ResourceSignal(observation, classification.kind(),
                            ResourceScope.SYSTEM_UNDER_TEST,
                            classification.limitFor(observation, observations)))
                    .ifPresent(resources::add);
        }
        return resources;
    }

    /**
     * How to classify one Micrometer name.
     *
     * <p>The table lives here because Micrometer's vocabulary is this adapter's business. A signal
     * absent from it stays an ordinary observation — collected, cited, exported and rendered, but
     * never a limiting-resource claim.
     */
    private Optional<Classification> classificationOf(String id) {
        return Optional.ofNullable(switch (id) {
            case "metric:system.cpu.usage", "metric:process.cpu.usage" ->
                    new Classification(ResourceKind.CPU, null, null);
            case "metric:jvm.memory.used" ->
                    new Classification(ResourceKind.RUNTIME_MEMORY, "metric:jvm.memory.max",
                            "the JVM's maximum heap");
            case "metric:jvm.memory.utilization" ->
                    new Classification(ResourceKind.RUNTIME_MEMORY, null, null);
            case "metric:hikaricp.connections.active", "metric:checkout.pool.active" ->
                    new Classification(ResourceKind.POOL, "metric:hikaricp.connections.max",
                            "the connection pool's configured maximum");
            case "metric:hikaricp.connections.utilization", "metric:checkout.pool.utilization" ->
                    new Classification(ResourceKind.POOL, null, null);
            case "metric:hikaricp.connections.pending", "metric:checkout.pool.pending" ->
                    new Classification(ResourceKind.QUEUE, null, null);
            case "metric:jvm.threads.live" -> new Classification(ResourceKind.THREADS, null, null);
            default -> null;
        });
    }

    /**
     * A kind, and where its limit comes from.
     *
     * @param limitFrom the id of the measurement that states the maximum, or {@code null} when the
     *                  signal is already a proportion and carries its limit in its unit
     */
    private record Classification(ResourceKind kind, String limitFrom, String limitDescription) {

        ResourceLimit limitFor(MetricObservation observation,
                List<MetricObservation> observations) {

            if (limitFrom == null) {
                // Already a proportion of something the provider divided by. Which proportion
                // matters: Micrometer publishes system.cpu.usage as a ratio and Vortex derives heap
                // utilisation as a percentage, and a limit in the wrong unit would never match — so
                // the resource could never be found at its limit, and the gap would be invisible.
                return ResourceLimit.inherentTo(observation.unit());
            }
            return observations.stream()
                    .filter(candidate -> candidate.id().equals(limitFrom))
                    .filter(candidate -> candidate.value() > 0)
                    .filter(candidate -> candidate.unit() == observation.unit())
                    .findFirst()
                    // Absent rather than assumed. A heap whose maximum the endpoint did not publish
                    // is still measured and still reported; it simply cannot be said to have reached
                    // a limit, because nothing said what its limit was.
                    .map(max -> ResourceLimit.published(max.value(), max.unit(), limitDescription))
                    .orElse(null);
        }
    }

    /** One metric's response, or the reason there wasn't one. */
    private record Read(JsonNode body, TelemetryAvailability availability, String detail) {

        static Read of(JsonNode body) {
            return new Read(body, TelemetryAvailability.AVAILABLE, "");
        }

        static Read missing(TelemetryAvailability availability, String detail) {
            return new Read(null, availability, detail);
        }
    }

    /**
     * Reads one metric.
     *
     * <p>The response is taken as text and parsed here rather than letting the HTTP client convert
     * it. Actuator answers with {@code application/vnd.spring-boot.actuator.v3+json}, and a client
     * configured only for {@code application/json} rejects it — which would make every metric look
     * absent for a reason that has nothing to do with the service under test.
     *
     * <p>Failures are classified rather than flattened. "The service does not publish that",
     * "your token was refused" and "the host is unreachable" are three different afternoons, and a
     * report that calls all three "unavailable" wastes all three.
     */
    private Read read(String endpoint, String metric) {
        try {
            String body = client.get()
                    .uri(endpoint + ACTUATOR_METRICS_PATH + "/" + metric)
                    .accept(MediaType.ALL)
                    .retrieve()
                    .body(String.class);

            if (body == null || body.isBlank()) {
                return Read.missing(TelemetryAvailability.NO_DATA, "");
            }
            return Read.of(json.readTree(body));

        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            return Read.missing(TelemetryAvailability.UNSUPPORTED,
                    "this service does not publish " + metric);
        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized
                | org.springframework.web.client.HttpClientErrorException.Forbidden e) {
            return Read.missing(TelemetryAvailability.UNAUTHORIZED,
                    "the actuator endpoint refused the request");
        } catch (org.springframework.web.client.ResourceAccessException e) {
            return Read.missing(TelemetryAvailability.UNREACHABLE, e.getMessage());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Read.missing(TelemetryAvailability.MALFORMED,
                    "the response was not readable JSON");
        } catch (RuntimeException e) {
            log.debug("Metric {} unavailable at {}: {}", metric, endpoint, e.getMessage());
            return Read.missing(TelemetryAvailability.UNREACHABLE, e.getMessage());
        }
    }

    private Optional<MetricObservation> toObservation(String endpoint, String metric, JsonNode node,
            TimeWindow window) {
        JsonNode measurements = node.path("measurements");
        if (!measurements.isArray() || measurements.isEmpty()) {
            return Optional.empty();
        }

        // Micrometer reports several statistics per metric. MAX is preferred for anything that can
        // saturate, because the peak is what caused the queueing; the mean would smooth away the
        // very moment of interest.
        JsonNode chosen = null;
        for (JsonNode measurement : measurements) {
            String statistic = measurement.path("statistic").asText("");
            if ("MAX".equals(statistic)) {
                chosen = measurement;
                break;
            }
            if (chosen == null && ("VALUE".equals(statistic) || "TOTAL_TIME".equals(statistic))) {
                chosen = measurement;
            }
        }
        if (chosen == null) {
            chosen = measurements.get(0);
        }

        double value = chosen.path("value").asDouble();
        if (!Double.isFinite(value)) {
            return Optional.empty();
        }

        // Micrometer reports usage and utilisation gauges as a fraction of one. Vortex normalises
        // them to percentages here, because "the pool reached 100%" is a sentence an engineer can
        // act on and "the pool reached 1" is not — and because an analysis that says "CPU remained
        // below 58%" needs the underlying measurement to actually be in percent.
        MetricUnit unit = unitFor(metric, node.path("baseUnit").asText(""));
        if (unit == MetricUnit.RATIO) {
            unit = MetricUnit.PERCENT;
            value = value * 100;
        }

        return Optional.of(new MetricObservation(
                "metric:" + metric,
                metric,
                MetricSource.ACTUATOR,
                unit,
                aggregationFor(chosen.path("statistic").asText("")),
                value,
                window,
                Map.of(),
                provenanceFor(endpoint, metric),
                null));
    }

    /**
     * Where this measurement came from, in terms a reader can follow.
     *
     * <p>For Actuator the "query" is simply the metric name, which looks redundant beside the name
     * field until the same report also carries a PromQL expression or a Dynatrace selector. The
     * report renderer must not have to know which provider it is describing.
     */
    private ObservationProvenance provenanceFor(String endpoint, String metric) {
        return new ObservationProvenance(id(), metric, "",
                endpoint + ACTUATOR_METRICS_PATH + "/" + metric);
    }

    /**
     * Heap utilisation as a percentage, derived from used and maximum.
     *
     * <p>Derived rather than measured, and labelled {@link MetricSource#DERIVED} so a finding that
     * cites it can be traced to the two measurements it came from. "Heap was at 40%" is a far more
     * useful sentence than "heap used 3.2 GB", but only if it is clear that Vortex computed it.
     */
    private Optional<MetricObservation> deriveHeapUtilisation(List<MetricObservation> observations,
            TimeWindow window) {
        Optional<Double> used = valueOf(observations, "metric:jvm.memory.used");
        Optional<Double> max = valueOf(observations, "metric:jvm.memory.max");
        if (used.isEmpty() || max.isEmpty() || max.get() <= 0) {
            return Optional.empty();
        }
        return Optional.of(MetricObservation.of("metric:jvm.memory.utilization",
                        "jvm.memory.utilization", MetricSource.DERIVED, MetricUnit.PERCENT,
                        Aggregation.MAX, used.get() / max.get() * 100, window)
                .withProvenance(ObservationProvenance.of(id(),
                        "jvm.memory.used / jvm.memory.max")));
    }

    /** Connection-pool utilisation as a percentage, when both active and maximum were reported. */
    private Optional<MetricObservation> derivePoolUtilisation(List<MetricObservation> observations,
            TimeWindow window) {
        Optional<Double> active = valueOf(observations, "metric:hikaricp.connections.active");
        Optional<Double> max = valueOf(observations, "metric:hikaricp.connections.max");
        if (active.isEmpty() || max.isEmpty() || max.get() <= 0) {
            return Optional.empty();
        }
        return Optional.of(MetricObservation.of("metric:hikaricp.connections.utilization",
                        "hikaricp.connections.utilization", MetricSource.DERIVED, MetricUnit.PERCENT,
                        Aggregation.MAX, active.get() / max.get() * 100, window)
                .withProvenance(ObservationProvenance.of(id(),
                        "hikaricp.connections.active / hikaricp.connections.max")));
    }

    private Optional<Double> valueOf(List<MetricObservation> observations, String id) {
        return observations.stream()
                .filter(observation -> observation.id().equals(id))
                .map(MetricObservation::value)
                .findFirst();
    }

    private MetricUnit unitFor(String metric, String baseUnit) {
        String lower = metric.toLowerCase(Locale.ROOT);
        if (lower.contains("utilization") || lower.contains("usage")) {
            return MetricUnit.RATIO;
        }
        return switch (baseUnit) {
            case "seconds" -> MetricUnit.SECONDS;
            case "milliseconds" -> MetricUnit.MILLISECONDS;
            case "bytes" -> MetricUnit.BYTES;
            case "percent" -> MetricUnit.PERCENT;
            default -> MetricUnit.COUNT;
        };
    }

    private Aggregation aggregationFor(String statistic) {
        return switch (statistic) {
            case "MAX" -> Aggregation.MAX;
            case "TOTAL_TIME", "TOTAL" -> Aggregation.SUM;
            case "COUNT" -> Aggregation.SUM;
            case "MEAN" -> Aggregation.MEAN;
            default -> Aggregation.LAST;
        };
    }
}
