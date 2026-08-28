package com.acltabontabon.vortex.app.adapter.observation;

import com.acltabontabon.vortex.core.capacity.ObservationSource;
import java.time.Duration;

/**
 * The PromQL Vortex asks Prometheus for a production observation, built in one place so a test can
 * assert the exact expression rather than only its effect.
 *
 * <p>Package-private rather than private methods on the adapter for the same reason
 * {@code scalarFrom}/{@code attribute} already are: the interesting behaviour is what these
 * expressions ask for, and that is worth testing directly.
 */
final class PrometheusQueries {

    /** The counter a Micrometer-instrumented Spring service publishes for HTTP requests. */
    static final String REQUEST_COUNTER = "http_server_requests_seconds_count";

    /**
     * The histogram bucket series the same service publishes for request latency, when histogram
     * publishing is enabled (it is not, by default, in every Spring Boot configuration).
     *
     * <p>Not configurable, unlike the label names below: a metric's presence is a fact about what the
     * service publishes, not a naming convention to translate. {@link #REQUEST_COUNTER} isn't
     * configurable either, for the same reason.
     */
    static final String REQUEST_HISTOGRAM = "http_server_requests_seconds_bucket";

    private PrometheusQueries() {
    }

    static String selector(ObservationSource source) {
        return REQUEST_COUNTER + "{" + source.label("service") + "=\""
                + escape(source.serviceIdentifier()) + "\"}";
    }

    private static String bucketSelector(ObservationSource source) {
        return REQUEST_HISTOGRAM + "{" + source.label("service") + "=\""
                + escape(source.serviceIdentifier()) + "\"}";
    }

    /**
     * Per-sample throughput — the shared shape peak, p95-of-rate and {@code verify()} all sample via
     * {@code query_range}, one {@code resolution}-wide point at a time.
     */
    static String rateExpression(ObservationSource source, Duration resolution) {
        return "sum(rate(" + selector(source) + "[" + step(resolution) + "]))";
    }

    /**
     * Total requests over the whole window, divided by its length — deliberately not
     * {@code avg_over_time} of a rate series, which extrapolates at series boundaries and drifts from
     * the true mean. Total divided by elapsed time is the definition of an average.
     */
    static String averageQuery(ObservationSource source, Duration window) {
        return "sum(increase(" + selector(source) + "[" + step(window) + "])) / " + window.toSeconds();
    }

    static String mixQuery(ObservationSource source, Duration window) {
        return "sum by (" + source.label("route") + ", " + source.label("method") + ") (increase("
                + selector(source) + "[" + step(window) + "]))";
    }

    static String totalQuery(ObservationSource source, Duration window) {
        return "sum(increase(" + selector(source) + "[" + step(window) + "]))";
    }

    /** Whether the histogram series exists at all — a structural question, independent of the
     *  observation window, distinct from whether it has samples <em>in</em> that window. */
    static String histogramExistenceQuery(ObservationSource source) {
        return "count(count by (le) (" + bucketSelector(source) + "))";
    }

    /** One instant aggregate percentile over the whole window — the standard Prometheus idiom for
     *  this, needing no subquery and no client-side math, unlike throughput's p95. */
    static String latencyP95Query(ObservationSource source, Duration window) {
        return "histogram_quantile(0.95, sum by (le) (rate(" + bucketSelector(source) + "["
                + step(window) + "])))";
    }

    /** Prometheus duration literals: whole seconds, which every version accepts. */
    static String step(Duration duration) {
        return duration.toSeconds() + "s";
    }

    private static String escape(String value) {
        return value.replace("\"", "\\\"");
    }
}
