package com.acltabontabon.vortex.app.adapter.observation;

import com.fasterxml.jackson.databind.JsonNode;
import com.acltabontabon.vortex.core.capacity.ObservationSource;
import com.acltabontabon.vortex.core.capacity.OperationMixCoverage;
import com.acltabontabon.vortex.core.capacity.ProductionObservation;
import com.acltabontabon.vortex.core.metrics.ObservationProvenance;
import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.core.port.ProductionObservationSource;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.threshold.Durations;
import com.acltabontabon.vortex.core.workload.Observation;
import com.acltabontabon.vortex.core.workload.OperationMix;
import com.acltabontabon.vortex.core.workload.WeightedOperation;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Asks Dynatrace what a service receives in production.
 *
 * <h2>Why this does not look like the Prometheus adapter</h2>
 * Dynatrace does not take an expression over arbitrary labels; it takes a metric selector scoped to
 * an <em>entity</em>, and it performs the aggregation itself. So where the Prometheus adapter writes
 * PromQL, this one writes {@code builtin:service.requestCount.total} with {@code :max},
 * {@code :percentile(95)} and {@code :avg} transformations and lets the platform evaluate them.
 *
 * <p>That asymmetry is deliberately not hidden. Flattening both systems into a common query shape
 * would mean reimplementing one of them badly; what the port abstracts is the <em>question</em>, not
 * the dialect. Both adapters return the same {@link ProductionObservation} and record which one
 * answered, and nothing downstream can tell the difference.
 *
 * <h2>Counts, not rates</h2>
 * {@code requestCount.total} is a count per resolution bucket, so every figure is divided by the
 * bucket length to become a rate. The bucket is the resolution Vortex asked for, which is the same
 * rule the Prometheus adapter follows and the same figure that travels back with the observation.
 */
public final class DynatraceObservationSource implements ProductionObservationSource {

    private static final Logger log = LoggerFactory.getLogger(DynatraceObservationSource.class);

    static final String REQUEST_COUNT = "builtin:service.requestCount.total";

    private static final String QUERY_PATH = "/api/v2/metrics/query";

    /** How Dynatrace splits a service's traffic by endpoint. */
    private static final String METHOD_DIMENSION = "dt.entity.service_method";

    private final RestClient client;

    public DynatraceObservationSource(RestClient.Builder builder) {
        this.client = ObservationHttp.client(builder);
    }

    @Override
    public String id() {
        return "dynatrace";
    }

    @Override
    public boolean supports(ObservationSource source) {
        return source != null && source.kind() == ObservationSource.Kind.DYNATRACE;
    }

    @Override
    public Retrieval retrieve(ObservationRequest request) {
        ObservationSource source = request.source();

        String missing = ObservationHttp.missingSecret(source);
        if (missing != null) {
            return new NotRetrieved(
                    "Could not read production traffic from Dynatrace",
                    "the environment variable " + missing + ", referenced by observation.headers, "
                            + "is not set in this shell.",
                    "Export an API token with the metrics.read scope before calibrating.");
        }

        Duration window = request.window().duration();
        Duration resolution = request.resolution();
        double bucketSeconds = resolution.toSeconds();

        String peakSelector = REQUEST_COUNT + ":max";
        String p95Selector = REQUEST_COUNT + ":percentile(95)";
        String averageSelector = REQUEST_COUNT + ":avg";
        String mixSelector = REQUEST_COUNT + ":splitBy(\"" + METHOD_DIMENSION + "\"):sum";

        try {
            Optional<Double> peak = scalar(source, peakSelector, request);
            if (peak.isEmpty() || peak.get() <= 0) {
                return new NotRetrieved(
                        "Could not read production traffic from Dynatrace",
                        "no request data was returned for entity " + source.serviceIdentifier()
                                + " over the last " + Durations.display(window) + ".",
                        "Check observation.entity is the service's entity id (it starts with "
                                + "SERVICE-), and that the token has the metrics.read scope.");
            }

            Optional<Double> p95 = scalar(source, p95Selector, request);
            Optional<Double> average = scalar(source, averageSelector, request);

            Mix mix = attribute(query(source, mixSelector, request), request.operations());

            // Coverage is claimed only when the split's own total is available to compare against.
            // Where Dynatrace cannot establish one, coverage is absent rather than assumed complete:
            // an unknown share is not a full share.
            OperationMixCoverage coverage = mix.total() > 0
                    ? new OperationMixCoverage(Math.round(mix.total()), Math.round(mix.matched()))
                    : null;

            if (mix.entries().isEmpty()) {
                log.info("Dynatrace returned traffic for {} but no service method matched a "
                        + "catalogued operation; the observation will carry rates without a "
                        + "composition", source.serviceIdentifier());
            }

            return new Retrieved(new ProductionObservation(
                    average.map(value -> rate(value, bucketSeconds)).orElse(null),
                    p95.map(value -> rate(value, bucketSeconds)).orElse(null),
                    rate(peak.get(), bucketSeconds),
                    mix.entries().isEmpty() ? null : OperationMix.of(mix.entries()),
                    coverage,
                    resolution,
                    "Dynatrace (" + source.serviceIdentifier() + ")",
                    Observation.over(request.window().start(), request.window().end()),
                    new ObservationProvenance(id(), peakSelector, source.serviceIdentifier(),
                            browseUrl(source)),
                    ""));

        } catch (RuntimeException e) {
            return ObservationHttp.classify(source, e);
        }
    }

    @Override
    public Retrieval verify(ObservationSource source, TimeWindow window, Duration resolution) {
        String missing = ObservationHttp.missingSecret(source);
        if (missing != null) {
            return new NotRetrieved(
                    "Could not reach Dynatrace",
                    "the environment variable " + missing + ", referenced by the headers, is not "
                            + "set in the shell Vortex is running in.",
                    "Export it and restart Vortex, then test again.");
        }

        String peakSelector = REQUEST_COUNT + ":max";
        try {
            var request = new ObservationRequest(source, window, resolution, List.of());
            Optional<Double> peak = scalarFrom(query(source, peakSelector, request));
            if (peak.isEmpty() || peak.get() <= 0) {
                return new NotRetrieved(
                        "Dynatrace answered, but not about this service",
                        "no request data was found for entity " + source.serviceIdentifier()
                                + " over the last " + Durations.display(window.duration()) + ".",
                        "Check the entity id is this service's (it starts with SERVICE-), and that "
                                + "the token carries the metrics.read scope.");
            }
            return new Retrieved(new ProductionObservation(
                    null, null, rate(peak.get(), resolution.toSeconds()), null, null, resolution,
                    "Dynatrace (" + source.serviceIdentifier() + ")",
                    Observation.over(window.start(), window.end()),
                    new ObservationProvenance(id(), peakSelector, source.serviceIdentifier(),
                            browseUrl(source)),
                    ""));
        } catch (RuntimeException e) {
            return ObservationHttp.classify(source, e);
        }
    }

    // ------------------------------------------------------------------ querying

    private RequestsPerSecond rate(double countPerBucket, double bucketSeconds) {
        return RequestsPerSecond.of(
                bucketSeconds <= 0 ? countPerBucket : countPerBucket / bucketSeconds);
    }

    private JsonNode query(ObservationSource source, String metricSelector,
            ObservationRequest request) {
        String uri = source.endpoint() + QUERY_PATH
                + "?metricSelector=" + encode(metricSelector)
                + "&entitySelector="
                + encode("type(SERVICE),entityId(" + source.serviceIdentifier() + ")")
                + "&from=" + encode(request.window().start().toString())
                + "&to=" + encode(request.window().end().toString())
                + "&resolution=" + encode(resolutionLiteral(request.resolution()));
        return ObservationHttp.parse(client.get()
                .uri(uri)
                .accept(org.springframework.http.MediaType.ALL)
                .headers(headers -> ObservationHttp.headers(source).apply(headers))
                .retrieve()
                .body(String.class));
    }

    /**
     * The highest value across the returned series.
     *
     * <p>Dynatrace answers with a series of buckets even when the transformation is {@code :max}, so
     * the final reduction happens here. Nulls in a series are gaps rather than zeroes and are
     * skipped: a bucket in which the service received nothing has not observed a rate of zero that
     * should be allowed to drag a statistic down.
     */
    private Optional<Double> scalar(ObservationSource source, String metricSelector,
            ObservationRequest request) {
        return scalarFrom(query(source, metricSelector, request));
    }

    /**
     * The mapping half of {@link #scalar}, separated so it can be driven from recorded responses.
     *
     * <p>Package-private for the same reason as its Prometheus counterpart: what matters is what
     * Vortex makes of a body somebody else wrote.
     */
    static Optional<Double> scalarFrom(JsonNode body) {
        if (body == null) {
            return Optional.empty();
        }
        double best = Double.NEGATIVE_INFINITY;
        for (JsonNode result : body.path("result")) {
            for (JsonNode series : result.path("data")) {
                for (JsonNode value : series.path("values")) {
                    if (value.isNull() || !value.isNumber()) {
                        continue;
                    }
                    best = Math.max(best, value.asDouble());
                }
            }
        }
        return best == Double.NEGATIVE_INFINITY ? Optional.empty() : Optional.of(best);
    }

    /** Matched entries, the traffic they account for, and everything the split saw. */
    record Mix(List<WeightedOperation> entries, double matched, double total) {
    }

    /**
     * Matches Dynatrace service methods onto catalogued operations.
     *
     * <p>Dynatrace names a service method by its display name, which for an HTTP service is
     * conventionally {@code METHOD /path/{template}}. Matching is on that pair; anything that does
     * not match counts towards the total but never becomes an operation — the same rule the
     * Prometheus adapter follows, for the same reason.
     */
    static Mix attribute(JsonNode body, List<ObservedOperation> operations) {
        List<WeightedOperation> entries = new ArrayList<>();
        double matched = 0;
        double total = 0;

        if (body == null) {
            return new Mix(entries, 0, 0);
        }

        Map<String, ObservedOperation> byKey = new LinkedHashMap<>();
        for (ObservedOperation operation : operations) {
            byKey.put(key(operation.method() + " " + operation.pathTemplate()), operation);
        }

        Map<ObservedOperation, Double> counts = new LinkedHashMap<>();
        for (JsonNode result : body.path("result")) {
            for (JsonNode series : result.path("data")) {
                String name = displayName(series);
                double sum = 0;
                for (JsonNode value : series.path("values")) {
                    if (value.isNumber()) {
                        sum += value.asDouble();
                    }
                }
                if (sum <= 0) {
                    continue;
                }
                total += sum;
                ObservedOperation operation = byKey.get(key(name));
                if (operation != null) {
                    counts.merge(operation, sum, Double::sum);
                    matched += sum;
                }
            }
        }

        counts.forEach((operation, count) ->
                entries.add(WeightedOperation.of(operation.operationId(),
                        (int) Math.max(1, Math.round(count)))));
        return new Mix(entries, matched, total);
    }

    /** The readable name of a split dimension, preferred over the raw entity id. */
    private static String displayName(JsonNode series) {
        JsonNode names = series.path("dimensionMap");
        if (names.isObject() && names.hasNonNull(METHOD_DIMENSION + ".name")) {
            return names.path(METHOD_DIMENSION + ".name").asText("");
        }
        if (names.isObject() && names.hasNonNull(METHOD_DIMENSION)) {
            return names.path(METHOD_DIMENSION).asText("");
        }
        JsonNode dimensions = series.path("dimensions");
        return dimensions.isArray() && !dimensions.isEmpty() ? dimensions.get(0).asText("") : "";
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String browseUrl(ObservationSource source) {
        return source.endpoint() + "/ui/entity/" + source.serviceIdentifier();
    }

    /** Dynatrace resolution literals: whole hours where they divide evenly, otherwise minutes. */
    private String resolutionLiteral(Duration resolution) {
        long seconds = resolution.toSeconds();
        if (seconds % 3600 == 0) {
            return (seconds / 3600) + "h";
        }
        return Math.max(1, seconds / 60) + "m";
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
