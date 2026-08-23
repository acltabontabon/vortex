package com.acltabontabon.vortex.k6;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.acltabontabon.vortex.core.metrics.LatencyPercentiles;
import com.acltabontabon.vortex.core.metrics.LoadGeneration;
import com.acltabontabon.vortex.core.metrics.MeasuredResults;
import com.acltabontabon.vortex.core.metrics.MetricObservation;
import com.acltabontabon.vortex.core.metrics.MetricSeries;
import com.acltabontabon.vortex.core.metrics.OperationMetrics;
import com.acltabontabon.vortex.core.metrics.ReliabilityBreakdown;
import com.acltabontabon.vortex.core.metrics.RequestPhases;
import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.core.shared.LoadLevel;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.shared.Percentile;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns k6's end-of-test summary into Vortex's normalised measurements.
 *
 * <p>This is the only place in Vortex that knows what k6's output looks like. Everything downstream
 * — threshold evaluation, breakpoint detection, reports, the AI assistant — sees
 * {@link MeasuredResults} and nothing else, which is what makes the engine genuinely replaceable
 * rather than nominally so.
 *
 * <h2>A trap worth documenting</h2>
 * k6's {@code http_req_failed} is a Rate metric, and its {@code passes} field counts the times the
 * condition was <em>true</em> — that is, the number of requests that <em>failed</em>. Reading
 * {@code fails} as the failure count, which is the obvious thing to do, inverts the error rate
 * completely: a perfectly healthy run reports 100% errors. The parser uses {@code rate} and
 * {@code passes}, and the fixtures cover it.
 *
 * <h2>Aggregates only</h2>
 * Everything read here is a whole-run total. Per-operation figures come from the raw sample stream
 * instead ({@link K6RawMetricsAggregator}), because that is where the {@code workload} tag lives.
 * The summary is the authority on end-of-run percentiles, which k6 computes across every sample
 * rather than the capped reservoir the aggregator keeps.
 */
public final class K6SummaryParser {

    private final ObjectMapper json = new ObjectMapper();

    /** Thrown when a summary cannot be understood at all. */
    public static class UnreadableSummaryException extends RuntimeException {

        public UnreadableSummaryException(String message, Throwable cause) {
            super(message, cause);
        }

        public UnreadableSummaryException(String message) {
            super(message);
        }
    }

    /**
     * Parses a summary document, with what the raw stream established alongside it.
     *
     * <p>The two sources answer different halves. The summary is authoritative for whole-run
     * percentiles and counters, because k6 computed them across every sample rather than the capped
     * reservoir a bucket can hold. The stream is the only source of per-operation figures and of the
     * outcome distribution, because that is where the tags live. Where both report a generator
     * counter the summary wins; where only one does, that one is used; where neither does, the
     * result says so rather than reporting zero.
     *
     * @param summaryJson  the contents of {@code k6-summary.json}
     * @param startedAt    when the run began, used to place the measurement window
     * @param targetLoad   the level the workload aimed for, carried through for reporting
     * @param aggregation  what the raw sample stream established
     * @param observations additional measurements from an observability provider
     */
    public MeasuredResults parse(String summaryJson, Instant startedAt, LoadLevel targetLoad,
            K6RawMetricsAggregator.Aggregation aggregation, List<MetricObservation> observations) {
        return parse(summaryJson, startedAt, targetLoad, aggregation.series(),
                aggregation.operations(), observations, aggregation.generation(),
                aggregation.reliability());
    }

    /**
     * Parses a summary document with no evidence from the raw stream about the generator.
     *
     * <p>Retained for callers that have only a summary — an imported script run with
     * {@code --summary-export}, and the parser's own fixtures. It reports the generator and the
     * outcome distribution as <em>not reported</em>, which is what they are.
     */
    public MeasuredResults parse(String summaryJson, Instant startedAt,
            LoadLevel targetLoad, MetricSeries series,
            Map<OperationId, OperationMetrics> operations, List<MetricObservation> observations) {
        return parse(summaryJson, startedAt, targetLoad, series, operations, observations,
                LoadGeneration.notReported(), ReliabilityBreakdown.notReported());
    }

    private MeasuredResults parse(String summaryJson, Instant startedAt,
            LoadLevel targetLoad, MetricSeries series,
            Map<OperationId, OperationMetrics> operations, List<MetricObservation> observations,
            LoadGeneration fromStream, ReliabilityBreakdown reliability) {

        JsonNode root;
        try {
            root = json.readTree(summaryJson);
        } catch (Exception e) {
            throw new UnreadableSummaryException(
                    "The execution engine's summary could not be parsed as JSON. The raw output has "
                            + "been preserved in this execution's artifacts.", e);
        }

        JsonNode metrics = root.path("metrics");
        if (metrics.isMissingNode() || !metrics.isObject()) {
            throw new UnreadableSummaryException(
                    "The execution engine's summary contained no metrics. This usually means the run "
                            + "ended before any traffic was generated — check the captured error "
                            + "output for this execution.");
        }

        Duration runDuration = Duration.ofMillis(
                Math.round(root.path("state").path("testRunDurationMs").asDouble(0)));
        if (runDuration.isZero()) {
            runDuration = series.span();
        }
        TimeWindow window = new TimeWindow(startedAt, startedAt.plus(runDuration));

        long httpRequests = counterCount(metrics, "http_reqs");
        double httpRate = counterRate(metrics, "http_reqs");

        // See the class comment: `passes` counts failures for this Rate metric.
        long httpFailures = Math.round(metrics.path("http_req_failed").path("values")
                .path("passes").asDouble(0));

        return new MeasuredResults(
                window,
                targetLoad,
                httpRate > 0 ? RequestsPerSecond.of(httpRate) : null,
                httpRequests,
                Math.min(httpFailures, httpRequests),
                latencyFrom(metrics.path("http_req_duration")),
                operations,
                series,
                observations,
                List.of(),
                List.of(),
                generationFrom(metrics, fromStream),
                phasesFrom(metrics),
                reliability);
    }

    /**
     * What the generator managed, preferring the summary's whole-run counters over the stream's.
     *
     * <p>Every branch here has to preserve the difference between zero and absent. k6 omits
     * {@code dropped_iterations} entirely from a run that dropped none, and it omits it from a run
     * whose engine never tracked it — so "the key is missing" cannot be read as "nothing was
     * dropped" in isolation. It becomes a zero only when the stream saw the counter and it was zero;
     * otherwise it stays absent and no validity rule may fire from it.
     */
    private LoadGeneration generationFrom(JsonNode metrics, LoadGeneration fromStream) {
        Long iterations = countIfPresent(metrics, "iterations");
        Long dropped = countIfPresent(metrics, "dropped_iterations");
        Double rate = metrics.path("iterations").path("values").has("rate")
                ? metrics.path("iterations").path("values").path("rate").asDouble()
                : null;

        if (iterations == null) {
            iterations = fromStream.iterationsStarted();
        }
        if (dropped == null) {
            dropped = fromStream.iterationsDropped();
        }
        if (rate == null) {
            rate = fromStream.iterationRate();
        }
        if (iterations == null && dropped == null && rate == null) {
            return LoadGeneration.notReported();
        }
        return new LoadGeneration(iterations, dropped, rate);
    }

    /**
     * The phase breakdown, from the trends k6 publishes per phase.
     *
     * <p>Read here rather than in the aggregator because these are percentiles, and k6 has already
     * computed them across every sample. Recomputing them from a capped per-bucket reservoir would
     * be both more expensive and less accurate.
     */
    private RequestPhases phasesFrom(JsonNode metrics) {
        return new RequestPhases(
                latencyFrom(metrics.path("http_req_blocked")),
                latencyFrom(metrics.path("http_req_connecting")),
                latencyFrom(metrics.path("http_req_tls_handshaking")),
                latencyFrom(metrics.path("http_req_sending")),
                latencyFrom(metrics.path("http_req_waiting")),
                latencyFrom(metrics.path("http_req_receiving")));
    }

    /** A counter's total, or absent when the summary did not carry the metric at all. */
    private Long countIfPresent(JsonNode metrics, String name) {
        JsonNode values = metrics.path(name).path("values");
        if (values.isMissingNode() || !values.has("count")) {
            return null;
        }
        return Math.round(values.path("count").asDouble(0));
    }

    /** The failure fraction k6 itself computed, for cross-checking Vortex's own calculation. */
    public Optional<Double> reportedFailureRate(String summaryJson) {
        try {
            JsonNode rate = json.readTree(summaryJson)
                    .path("metrics").path("http_req_failed").path("values").path("rate");
            return rate.isMissingNode() ? Optional.empty() : Optional.of(rate.asDouble());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Whether k6 considered its own declared thresholds satisfied. */
    public boolean engineThresholdsPassed(String summaryJson) {
        try {
            JsonNode metrics = json.readTree(summaryJson).path("metrics");
            for (JsonNode metric : metrics) {
                JsonNode thresholds = metric.path("thresholds");
                for (JsonNode threshold : thresholds) {
                    if (!threshold.path("ok").asBoolean(true)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private LatencyPercentiles latencyFrom(JsonNode trend) {
        JsonNode values = trend.path("values");
        if (values.isMissingNode()) {
            return LatencyPercentiles.empty();
        }

        LatencyPercentiles.Builder builder = LatencyPercentiles.builder();
        for (double percent : new double[] {50, 90, 95, 99}) {
            String key = "p(" + (long) percent + ")";
            if (values.has(key)) {
                builder.at(Percentile.of(percent), millis(values.path(key).asDouble()));
            }
        }
        if (values.has("min")) {
            builder.minimumMillis(values.path("min").asDouble());
        }
        if (values.has("avg")) {
            builder.meanMillis(values.path("avg").asDouble());
        }
        if (values.has("max")) {
            builder.maximumMillis(values.path("max").asDouble());
        }
        return builder.build();
    }

    private Duration millis(double value) {
        return Duration.ofNanos(Math.round(value * 1_000_000d));
    }

    private long counterCount(JsonNode metrics, String name) {
        return Math.round(metrics.path(name).path("values").path("count").asDouble(0));
    }

    private double counterRate(JsonNode metrics, String name) {
        return metrics.path(name).path("values").path("rate").asDouble(0);
    }
}
