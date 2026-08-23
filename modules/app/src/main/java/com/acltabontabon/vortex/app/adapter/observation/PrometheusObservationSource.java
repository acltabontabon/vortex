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
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * Asks Prometheus what a service receives in production.
 *
 * <h2>What each number means</h2>
 * Every rate here is a statistic <em>of a set of samples</em>, and the sample interval decides what
 * the word means. Over a window {@code W} at resolution {@code r}, against a request counter
 * {@code C}:
 *
 * <ul>
 *   <li><strong>peak</strong> — {@code max_over_time(sum(rate(C[r]))[W:r])}, the busiest {@code r}
 *       of the window.</li>
 *   <li><strong>p95 request rate</strong> — {@code quantile_over_time(0.95, ...)} over that same
 *       sample set, so the two are directly comparable.</li>
 *   <li><strong>average</strong> — {@code sum(increase(C[W])) / W}, deliberately <em>not</em>
 *       {@code avg_over_time} of a rate subquery. {@code rate()} extrapolates at series boundaries,
 *       so averaging its samples drifts from the true mean, whereas total requests divided by
 *       elapsed time is the definition of one.</li>
 * </ul>
 *
 * <p>The resolution is chosen by {@link com.acltabontabon.vortex.core.capacity.ObservationResolution} rather than
 * here, and travels back with the observation — a peak from one-minute samples and a peak from
 * hourly samples are different claims about the same traffic.
 *
 * <h2>Attribution, and what is not invented</h2>
 * The composition query groups by route and method and is matched against the operations Vortex
 * knows about. A series matching nothing is dropped rather than bucketed into a synthetic "other":
 * Vortex can only issue requests against catalogued operations, so an invented entry would produce a
 * workload it cannot run. What it does instead is record how much traffic was matched, because
 * narrowing the evidence is acceptable and quietly overstating its completeness is not.
 */
public final class PrometheusObservationSource implements ProductionObservationSource {

    private static final Logger log = LoggerFactory.getLogger(PrometheusObservationSource.class);

    /** The counter a Micrometer-instrumented Spring service publishes for HTTP requests. */
    static final String REQUEST_COUNTER = "http_server_requests_seconds_count";

    private static final String QUERY_PATH = "/api/v1/query";

    private final RestClient client;

    public PrometheusObservationSource(RestClient.Builder builder) {
        this.client = ObservationHttp.client(builder);
    }

    @Override
    public String id() {
        return "prometheus";
    }

    @Override
    public boolean supports(ObservationSource source) {
        return source != null && source.kind() == ObservationSource.Kind.PROMETHEUS;
    }

    @Override
    public Retrieval retrieve(ObservationRequest request) {
        ObservationSource source = request.source();

        String missing = ObservationHttp.missingSecret(source);
        if (missing != null) {
            return new NotRetrieved(
                    "Could not read production traffic from Prometheus",
                    "the environment variable " + missing + ", referenced by observation.headers, "
                            + "is not set in this shell.",
                    "Export it before calibrating, the same way you do for a run's authorisation "
                            + "header.");
        }

        Duration window = request.window().duration();
        Duration resolution = request.resolution();
        String selector = selector(source);

        String peakQuery = peakQuery(source, window, resolution);
        String p95Query = "quantile_over_time(0.95, sum(rate(" + selector + "["
                + step(resolution) + "]))[" + step(window) + ":" + step(resolution) + "])";
        String averageQuery = "sum(increase(" + selector + "[" + step(window) + "])) / "
                + window.toSeconds();
        String mixQuery = "sum by (" + source.label("route") + ", " + source.label("method")
                + ") (increase(" + selector + "[" + step(window) + "]))";
        String totalQuery = "sum(increase(" + selector + "[" + step(window) + "]))";

        try {
            Optional<Double> peak = scalar(source, peakQuery);
            if (peak.isEmpty() || peak.get() <= 0) {
                return new NotRetrieved(
                        "Could not read production traffic from Prometheus",
                        "the query returned no data for " + source.serviceIdentifier()
                                + " over the last " + Durations.display(window) + ".",
                        "Check observation.service matches the '" + source.label("service")
                                + "' label your service publishes, and that "
                                + REQUEST_COUNTER + " exists in this Prometheus.");
            }

            Optional<Double> p95 = scalar(source, p95Query);
            Optional<Double> average = scalar(source, averageQuery);

            JsonNode mixResult = query(source, mixQuery);
            Mix mix = attribute(mixResult, source, request.operations());
            Optional<Double> total = scalar(source, totalQuery);

            OperationMixCoverage coverage = total
                    .map(observed -> new OperationMixCoverage(
                            Math.round(observed), Math.min(Math.round(mix.matched()), Math.round(observed))))
                    .orElse(null);

            if (mix.entries().isEmpty()) {
                log.info("Prometheus returned traffic for {} but none of it matched a catalogued "
                        + "operation; the observation will carry rates without a composition",
                        source.serviceIdentifier());
            }

            return new Retrieved(new ProductionObservation(
                    average.map(RequestsPerSecond::of).orElse(null),
                    p95.map(RequestsPerSecond::of).orElse(null),
                    RequestsPerSecond.of(peak.get()),
                    mix.entries().isEmpty() ? null : OperationMix.of(mix.entries()),
                    coverage,
                    resolution,
                    "Prometheus (" + source.serviceIdentifier() + ")",
                    Observation.over(request.window().start(), request.window().end()),
                    new ObservationProvenance(id(), peakQuery, source.serviceIdentifier(),
                            browseUrl(source, peakQuery)),
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
                    "Could not reach Prometheus",
                    "the environment variable " + missing + ", referenced by the headers, is not "
                            + "set in the shell Vortex is running in.",
                    "Export it and restart Vortex, then test again.");
        }

        String peakQuery = peakQuery(source, window.duration(), resolution);
        try {
            Optional<Double> peak = scalar(source, peakQuery);
            if (peak.isEmpty() || peak.get() <= 0) {
                // Reached and answered, and answered about nothing. Distinct from an unreachable
                // endpoint, and much more often the actual problem: the label value is wrong.
                return new NotRetrieved(
                        "Prometheus answered, but not about this service",
                        "no request data was found for " + source.label("service") + "=\""
                                + source.serviceIdentifier() + "\" over the last "
                                + Durations.display(window.duration()) + ".",
                        "Check the service value matches the label your service publishes, and that "
                                + REQUEST_COUNTER + " exists in this Prometheus.");
            }
            return new Retrieved(new ProductionObservation(
                    null, null, RequestsPerSecond.of(peak.get()), null, null, resolution,
                    "Prometheus (" + source.serviceIdentifier() + ")",
                    Observation.over(window.start(), window.end()),
                    new ObservationProvenance(id(), peakQuery, source.serviceIdentifier(),
                            browseUrl(source, peakQuery)),
                    ""));
        } catch (RuntimeException e) {
            return ObservationHttp.classify(source, e);
        }
    }

    // ------------------------------------------------------------------ querying

    /** The peak expression, in one place, so a test and a fetch ask the same question. */
    private String peakQuery(ObservationSource source, Duration window, Duration resolution) {
        return "max_over_time(sum(rate(" + selector(source) + "[" + step(resolution) + "]))["
                + step(window) + ":" + step(resolution) + "])";
    }

    private String selector(ObservationSource source) {
        return REQUEST_COUNTER + "{" + source.label("service") + "=\""
                + source.serviceIdentifier().replace("\"", "\\\"") + "\"}";
    }

    private JsonNode query(ObservationSource source, String expression) {
        return ObservationHttp.parse(client.get()
                .uri(source.endpoint() + QUERY_PATH + "?query="
                        + URLEncoder.encode(expression, StandardCharsets.UTF_8))
                .accept(org.springframework.http.MediaType.ALL)
                .headers(headers -> ObservationHttp.headers(source).apply(headers))
                .retrieve()
                .body(String.class));
    }

    /**
     * The single value of an instant query.
     *
     * <p>Empty when Prometheus answered but had nothing to say. That is an ordinary outcome — a
     * service with no traffic in the window, or a label that matches nothing — and it is not the
     * same as a failure, so it does not become one.
     */
    private Optional<Double> scalar(ObservationSource source, String expression) {
        return scalarFrom(query(source, expression));
    }

    /**
     * The mapping half of {@link #scalar}, separated so it can be driven from recorded responses.
     *
     * <p>Package-private rather than private for exactly that reason: the interesting behaviour here
     * is what Vortex makes of a body it did not write, and that is worth testing against real
     * captured output rather than a stub server's idea of it.
     */
    static Optional<Double> scalarFrom(JsonNode body) {
        if (body == null || !"success".equals(body.path("status").asText())) {
            return Optional.empty();
        }
        JsonNode result = body.path("data").path("result");
        if (!result.isArray() || result.isEmpty()) {
            return Optional.empty();
        }
        JsonNode value = result.get(0).path("value");
        if (!value.isArray() || value.size() < 2) {
            return Optional.empty();
        }
        try {
            double parsed = Double.parseDouble(value.get(1).asText());
            return Double.isFinite(parsed) ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException e) {
            // Prometheus renders NaN as a string. A metric that exists but has no samples in the
            // window is exactly this case, and it means "nothing observed", not "malformed".
            return Optional.empty();
        }
    }

    /** Matched entries plus how much traffic they account for. */
    record Mix(List<WeightedOperation> entries, double matched) {
    }

    /**
     * Matches grouped series onto catalogued operations.
     *
     * <p>Weights are the request counts themselves. {@link OperationMix} normalises them, so passing
     * counts through directly avoids a second rounding step and keeps the ratios exactly as
     * Prometheus reported them.
     */
    static Mix attribute(JsonNode body, ObservationSource source, List<ObservedOperation> operations) {
        List<WeightedOperation> entries = new ArrayList<>();
        double matched = 0;

        if (body == null || !"success".equals(body.path("status").asText())) {
            return new Mix(entries, 0);
        }

        Map<String, ObservedOperation> byKey = new LinkedHashMap<>();
        for (ObservedOperation operation : operations) {
            byKey.put(key(operation.method(), operation.pathTemplate()), operation);
        }

        Map<ObservedOperation, Double> counts = new LinkedHashMap<>();
        for (JsonNode series : body.path("data").path("result")) {
            JsonNode metric = series.path("metric");
            String route = metric.path(source.label("route")).asText("");
            String method = metric.path(source.label("method")).asText("");
            ObservedOperation operation = byKey.get(key(method, route));
            if (operation == null) {
                continue;
            }
            double count = seriesValue(series);
            if (count > 0) {
                counts.merge(operation, count, Double::sum);
                matched += count;
            }
        }

        counts.forEach((operation, count) ->
                entries.add(WeightedOperation.of(operation.operationId(), (int) Math.max(1, Math.round(count)))));
        return new Mix(entries, matched);
    }

    private static double seriesValue(JsonNode series) {
        JsonNode value = series.path("value");
        if (!value.isArray() || value.size() < 2) {
            return 0;
        }
        try {
            double parsed = Double.parseDouble(value.get(1).asText());
            return Double.isFinite(parsed) ? parsed : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String key(String method, String path) {
        return method.trim().toUpperCase(java.util.Locale.ROOT) + " " + path.trim();
    }

    /**
     * A link to the same question in Prometheus's own interface.
     *
     * <p>Reports say where their numbers came from; this is the shortest path from a figure in a
     * report to the system that measured it.
     */
    private String browseUrl(ObservationSource source, String expression) {
        return source.endpoint() + "/graph?g0.expr="
                + URLEncoder.encode(expression, StandardCharsets.UTF_8) + "&g0.tab=1";
    }

    /** Prometheus duration literals: whole seconds, which every version accepts. */
    private String step(Duration duration) {
        return duration.toSeconds() + "s";
    }
}
