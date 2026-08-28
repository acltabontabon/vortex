package com.acltabontabon.vortex.app.adapter.observation;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The only {@link PrometheusClient} implementation — issues {@code /api/v1/query} and
 * {@code /api/v1/query_range} over the shared {@link ObservationHttp}-configured {@link RestClient},
 * and turns Prometheus's JSON envelope into {@link PrometheusQueryResult}/{@link PrometheusRangeResult}.
 *
 * <p>Constructed per request rather than held across one, mirroring how the adapter always issued
 * {@code client.get().uri(...).headers(...)} inline — a client tied to one source's headers has no
 * reason to outlive the request that needed them.
 *
 * <p>Retries a small, bounded number of times on a transient failure (network trouble, a 5xx) and
 * never on a 4xx — a rejected token or a query the server rejected will not succeed on attempt two.
 * No resilience framework: this is fifteen lines, auditable in one method.
 */
final class RestClientPrometheusClient implements PrometheusClient {

    private static final Logger log = LoggerFactory.getLogger(RestClientPrometheusClient.class);

    private static final String QUERY_PATH = "/api/v1/query";
    private static final String QUERY_RANGE_PATH = "/api/v1/query_range";

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration BACKOFF = Duration.ofMillis(250);

    private final RestClient restClient;
    private final String endpoint;
    private final UnaryOperator<HttpHeaders> headers;

    RestClientPrometheusClient(RestClient restClient, String endpoint, UnaryOperator<HttpHeaders> headers) {
        this.restClient = restClient;
        this.endpoint = endpoint;
        this.headers = headers;
    }

    @Override
    public PrometheusQueryResult query(String promql, Instant time) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("query", promql);
        params.put("time", time.toString());
        return parseQuery(withRetry(() -> fetch(uri(QUERY_PATH, params))));
    }

    @Override
    public PrometheusRangeResult queryRange(String promql, Instant start, Instant end, Duration step) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("query", promql);
        params.put("start", start.toString());
        params.put("end", end.toString());
        params.put("step", PrometheusQueries.step(step));
        return parseRange(withRetry(() -> fetch(uri(QUERY_RANGE_PATH, params))));
    }

    /**
     * Builds and encodes the URI here, once, from unescaped values — {@link RestClient}'s own
     * {@code .uri(String)} treats a string argument as a template and encodes it again, which turns
     * an already-percent-encoded {@code %3A} into {@code %253A} and Prometheus then rejects as an
     * unparsable timestamp. Handing {@link RestClient} a finished {@link URI} instead skips that
     * second pass entirely.
     */
    private URI uri(String path, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(endpoint + path);
        params.forEach(builder::queryParam);
        return builder.build().encode().toUri();
    }

    private JsonNode fetch(URI uri) {
        return ObservationHttp.parse(restClient.get()
                .uri(uri)
                .accept(MediaType.ALL)
                .headers(h -> headers.apply(h))
                .retrieve()
                .body(String.class));
    }

    private <T> T withRetry(Supplier<T> attempt) {
        for (int i = 1; i <= MAX_ATTEMPTS; i++) {
            try {
                return attempt.get();
            } catch (ResourceAccessException | HttpServerErrorException e) {
                if (i == MAX_ATTEMPTS) {
                    throw e;
                }
                log.debug("Prometheus request failed, retrying (attempt {}/{}): {}", i, MAX_ATTEMPTS, e.toString());
                try {
                    Thread.sleep(BACKOFF.toMillis() * i);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw new IllegalStateException("unreachable");
    }

    // ------------------------------------------------------------------ parsing

    /** Package-private rather than private for the same reason {@code scalarFrom}/{@code attribute}
     *  already are on the adapters: worth testing directly from a recorded response. */
    static PrometheusQueryResult parseQuery(JsonNode body) {
        if (body == null) {
            return PrometheusQueryResult.error("", "empty response body");
        }
        if (!"success".equals(body.path("status").asText())) {
            return PrometheusQueryResult.error(body.path("errorType").asText(""), body.path("error").asText(""));
        }
        JsonNode result = body.path("data").path("result");
        List<PrometheusQueryResult.VectorSample> vector = new ArrayList<>();
        if (result.isArray()) {
            for (JsonNode series : result) {
                vector.add(new PrometheusQueryResult.VectorSample(
                        labelsOf(series.path("metric")), valueAt(series.path("value"))));
            }
        }
        return PrometheusQueryResult.success(vector);
    }

    static PrometheusRangeResult parseRange(JsonNode body) {
        if (body == null) {
            return PrometheusRangeResult.error("", "empty response body");
        }
        if (!"success".equals(body.path("status").asText())) {
            return PrometheusRangeResult.error(body.path("errorType").asText(""), body.path("error").asText(""));
        }
        JsonNode result = body.path("data").path("result");
        List<PrometheusRangeResult.MatrixSeries> matrix = new ArrayList<>();
        if (result.isArray()) {
            for (JsonNode series : result) {
                List<PrometheusRangeResult.Sample> samples = new ArrayList<>();
                for (JsonNode point : series.path("values")) {
                    if (point.isArray() && point.size() >= 2) {
                        Instant timestamp = Instant.ofEpochMilli(Math.round(point.get(0).asDouble() * 1000));
                        samples.add(new PrometheusRangeResult.Sample(timestamp, sanitize(point.get(1).asText(null))));
                    }
                }
                matrix.add(new PrometheusRangeResult.MatrixSeries(labelsOf(series.path("metric")), samples));
            }
        }
        return PrometheusRangeResult.success(matrix);
    }

    private static Map<String, String> labelsOf(JsonNode metric) {
        Map<String, String> labels = new LinkedHashMap<>();
        if (metric.isObject()) {
            metric.fields().forEachRemaining(entry -> labels.put(entry.getKey(), entry.getValue().asText("")));
        }
        return labels;
    }

    private static Double valueAt(JsonNode value) {
        if (!value.isArray() || value.size() < 2) {
            return null;
        }
        return sanitize(value.get(1).asText(null));
    }

    /** Prometheus renders NaN/+Inf/-Inf as JSON strings. All three become "no usable value" rather
     *  than a number that would silently participate in a max or an average as if it were real. */
    private static Double sanitize(String text) {
        if (text == null) {
            return null;
        }
        try {
            double parsed = Double.parseDouble(text);
            return Double.isFinite(parsed) ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

}
