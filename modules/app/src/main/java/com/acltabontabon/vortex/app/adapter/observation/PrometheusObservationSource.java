package com.acltabontabon.vortex.app.adapter.observation;

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
import java.time.Instant;
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
 *   <li><strong>peak</strong> — the highest of the samples {@code query_range} returns for
 *       {@code sum(rate(C[r]))}, stepped every {@code r} across {@code W}.</li>
 *   <li><strong>p95 request rate</strong> — the 95th-percentile of that same sample set, computed
 *       client-side by {@link PrometheusQuantile} rather than by a {@code quantile_over_time}
 *       subquery — subqueries are a Prometheus-native feature not guaranteed on every
 *       Prometheus-compatible backend, and computing it here means Vortex sanitizes the raw samples
 *       itself rather than trusting an opaque server-side aggregate.</li>
 *   <li><strong>average</strong> — {@code sum(increase(C[W])) / W}, deliberately <em>not</em> derived
 *       from the same sample set. {@code rate()} extrapolates at series boundaries, so averaging its
 *       samples drifts from the true mean, whereas total requests divided by elapsed time is the
 *       definition of one.</li>
 * </ul>
 *
 * <p>The resolution is chosen by {@link com.acltabontabon.vortex.core.capacity.ObservationResolution}
 * rather than here, and travels back with the observation — a peak from one-minute samples and a
 * peak from hourly samples are different claims about the same traffic.
 *
 * <h2>Attribution, and what is not invented</h2>
 * The composition query groups by route and method and is matched against the operations Vortex
 * knows about. A series matching nothing is dropped rather than bucketed into a synthetic "other":
 * Vortex can only issue requests against catalogued operations, so an invented entry would produce a
 * workload it cannot run. What it does instead is record how much traffic was matched, because
 * narrowing the evidence is acceptable and quietly overstating its completeness is not.
 *
 * <h2>Latency is diagnostic only</h2>
 * {@link #verify} — and only {@code verify}, never {@link #retrieve} — additionally asks whether
 * histogram buckets exist and, if so, computes a real p95 latency figure via
 * {@code histogram_quantile}. That figure is folded into the {@code note} of the peak-only
 * observation {@code verify} already returns for review, and surfaces in the connection-test message
 * text — it is never added to the retrieved observation, never persisted, and never used for
 * calibration. {@link ProductionObservation} deliberately carries no latency field; this does not
 * reopen that decision, it answers a different question ("can Vortex compute this at all here") in a
 * place nothing downstream can mistake for evidence.
 */
public final class PrometheusObservationSource implements ProductionObservationSource {

    private static final Logger log = LoggerFactory.getLogger(PrometheusObservationSource.class);

    private final java.util.function.Function<ObservationSource, PrometheusClient> clientFactory;

    public PrometheusObservationSource(RestClient.Builder builder) {
        RestClient restClient = ObservationHttp.client(builder);
        this.clientFactory = source -> new RestClientPrometheusClient(restClient, source.endpoint(),
                headers -> ObservationHttp.headers(source).apply(headers));
    }

    /** Test seam — a fake {@link PrometheusClient} exercises {@link #retrieve}/{@link #verify}'s
     *  orchestration (which queries are issued, in what order, and how the answers are turned into
     *  an observation or a classified refusal) without a socket. */
    PrometheusObservationSource(java.util.function.Function<ObservationSource, PrometheusClient> clientFactory) {
        this.clientFactory = clientFactory;
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
                            + "header.",
                    NotRetrieved.Kind.AUTHENTICATION_FAILED);
        }

        Duration window = request.window().duration();
        Duration resolution = request.resolution();
        Instant end = request.window().end();
        PrometheusClient promClient = clientFactory.apply(source);
        String rateExpr = PrometheusQueries.rateExpression(source, resolution);

        try {
            PrometheusRangeResult range = promClient.queryRange(rateExpr, request.window().start(), end, resolution);
            if (!range.success()) {
                return invalidResponse(source, range.errorType(), range.error());
            }
            List<Double> samples = range.firstSeriesValues();
            Double peak = PrometheusQuantile.max(samples);
            if (peak == null || peak <= 0) {
                return new NotRetrieved(
                        "Could not read production traffic from Prometheus",
                        "the query returned no data for " + source.serviceIdentifier()
                                + " over the last " + Durations.display(window) + ".",
                        "Check observation.service matches the '" + source.label("service")
                                + "' label your service publishes, and that "
                                + PrometheusQueries.REQUEST_COUNTER + " exists in this Prometheus.",
                        NotRetrieved.Kind.NO_DATA);
            }
            Double p95 = PrometheusQuantile.quantile(samples, 0.95);

            Optional<Double> average = promClient.query(PrometheusQueries.averageQuery(source, window), end).firstValue();

            PrometheusQueryResult mixResult = promClient.query(PrometheusQueries.mixQuery(source, window), end);
            Mix mix = attribute(mixResult, source, request.operations());
            Optional<Double> total = promClient.query(PrometheusQueries.totalQuery(source, window), end).firstValue();

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
                    RequestsPerSecond.of(p95),
                    RequestsPerSecond.of(peak),
                    mix.entries().isEmpty() ? null : OperationMix.of(mix.entries()),
                    coverage,
                    resolution,
                    "Prometheus (" + source.serviceIdentifier() + ")",
                    Observation.over(request.window().start(), request.window().end()),
                    new ObservationProvenance(id(), rateExpr, source.serviceIdentifier(),
                            browseUrl(source, rateExpr)),
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
                    "Export it and restart Vortex, then test again.",
                    NotRetrieved.Kind.AUTHENTICATION_FAILED);
        }

        PrometheusClient promClient = clientFactory.apply(source);
        String rateExpr = PrometheusQueries.rateExpression(source, resolution);
        try {
            PrometheusRangeResult range = promClient.queryRange(rateExpr, window.start(), window.end(), resolution);
            if (!range.success()) {
                return invalidResponse(source, range.errorType(), range.error());
            }
            List<Double> samples = range.firstSeriesValues();
            Double peak = PrometheusQuantile.max(samples);
            if (peak == null || peak <= 0) {
                // Reached and answered, and answered about nothing. Distinct from an unreachable
                // endpoint, and much more often the actual problem: the label value is wrong.
                return new NotRetrieved(
                        "Prometheus answered, but not about this service",
                        "no request data was found for " + source.label("service") + "=\""
                                + source.serviceIdentifier() + "\" over the last "
                                + Durations.display(window.duration()) + ".",
                        "Check the service value matches the label your service publishes, and that "
                                + PrometheusQueries.REQUEST_COUNTER + " exists in this Prometheus.",
                        NotRetrieved.Kind.NO_DATA);
            }

            String latencyNote = latencyDiagnostic(promClient, source, window.duration(), end(window));

            return new Retrieved(new ProductionObservation(
                    null, null, RequestsPerSecond.of(peak), null, null, resolution,
                    "Prometheus (" + source.serviceIdentifier() + ")",
                    Observation.over(window.start(), window.end()),
                    new ObservationProvenance(id(), rateExpr, source.serviceIdentifier(),
                            browseUrl(source, rateExpr)),
                    latencyNote));
        } catch (RuntimeException e) {
            return ObservationHttp.classify(source, e);
        }
    }

    // ------------------------------------------------------------------ diagnostic-only latency

    /**
     * Two questions, in order: does the histogram exist at all (structural), and if so does it have
     * samples in this window (volume). Collapsing them into one query cannot tell "never
     * instrumented" from "instrumented but silent this month" — exactly the distinction that matters
     * to someone deciding whether to enable histogram publishing.
     *
     * <p>Never called from {@link #retrieve}. See the class Javadoc for why this stays out of the
     * retrieved observation entirely.
     */
    private String latencyDiagnostic(PrometheusClient promClient, ObservationSource source,
            Duration window, Instant time) {
        PrometheusQueryResult existence = promClient.query(
                PrometheusQueries.histogramExistenceQuery(source), time);
        Optional<Double> bucketCount = existence.success() ? existence.firstValue() : Optional.empty();
        if (bucketCount.isEmpty() || bucketCount.get() <= 0) {
            return "Histogram buckets required for p95 latency are not published by this Prometheus ("
                    + PrometheusQueries.REQUEST_HISTOGRAM + ").";
        }

        PrometheusQueryResult latency = promClient.query(
                PrometheusQueries.latencyP95Query(source, window), time);
        Optional<Double> p95Seconds = latency.success() ? latency.firstValue() : Optional.empty();
        if (p95Seconds.isEmpty() || p95Seconds.get() < 0) {
            return "Histogram data exists but had no samples in this window.";
        }
        long millis = Math.round(p95Seconds.get() * 1000);
        return "Histogram data found — p95 latency ~" + millis + "ms ("
                + PrometheusQueries.REQUEST_HISTOGRAM + "). Diagnostic only, not saved.";
    }

    private static Instant end(TimeWindow window) {
        return window.end();
    }

    // ------------------------------------------------------------------ querying

    private NotRetrieved invalidResponse(ObservationSource source, String errorType, String error) {
        String detail = errorType.isBlank() ? error : errorType + ": " + error;
        return new NotRetrieved(
                "Could not read production traffic from Prometheus",
                "Prometheus rejected the query" + (detail.isBlank() ? "." : " (" + detail + ")."),
                "The query Vortex issued is shown above; try it in Prometheus directly to see what "
                        + "it objects to.",
                NotRetrieved.Kind.INVALID_RESPONSE);
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
    static Mix attribute(PrometheusQueryResult result, ObservationSource source,
            List<ObservedOperation> operations) {
        List<WeightedOperation> entries = new ArrayList<>();
        double matched = 0;

        if (!result.success()) {
            return new Mix(entries, 0);
        }

        Map<String, ObservedOperation> byKey = new LinkedHashMap<>();
        for (ObservedOperation operation : operations) {
            byKey.put(key(operation.method(), operation.pathTemplate()), operation);
        }

        Map<ObservedOperation, Double> counts = new LinkedHashMap<>();
        for (PrometheusQueryResult.VectorSample sample : result.vector()) {
            String route = sample.labels().getOrDefault(source.label("route"), "");
            String method = sample.labels().getOrDefault(source.label("method"), "");
            ObservedOperation operation = byKey.get(key(method, route));
            if (operation == null) {
                continue;
            }
            double count = sample.valueIfPresent().filter(v -> v > 0).orElse(0.0);
            if (count > 0) {
                counts.merge(operation, count, Double::sum);
                matched += count;
            }
        }

        counts.forEach((operation, count) ->
                entries.add(WeightedOperation.of(operation.operationId(), (int) Math.max(1, Math.round(count)))));
        return new Mix(entries, matched);
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
}
