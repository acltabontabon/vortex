package dev.vortex.core.analysis;

import dev.vortex.core.metrics.MeasuredResults;
import dev.vortex.core.metrics.MetricObservation;
import dev.vortex.core.metrics.OperationMetrics;
import dev.vortex.core.shared.OperationId;
import dev.vortex.core.shared.Percentile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The identifiers by which a piece of evidence can be cited.
 *
 * <p>Every measurement Vortex can show an AI assistant has a stable name, and a finding must cite
 * one of those names to survive validation. This class is the single definition of what those names
 * are.
 *
 * <p>It exists because that definition was briefly written twice — once where the evidence package
 * is assembled and once where citations are checked. The two drifted immediately: the assistant was
 * shown measurements it was then told it could not cite, and perfectly good findings about real
 * measurements were discarded as unsupported. A validator and the thing it validates cannot each
 * keep their own list.
 */
public final class EvidenceIds {

    /** Prefix for a measured or derived metric. */
    public static final String METRIC = "metric:";

    /** Prefix for the outcome of one objective. */
    public static final String THRESHOLD = "threshold:";

    /** Prefix for a computed difference between two executions. */
    public static final String DELTA = "delta:";

    public static final String THROUGHPUT_ACHIEVED = METRIC + "request.rate.achieved";
    public static final String THROUGHPUT_TARGET = METRIC + "workload.level.target";
    public static final String REQUEST_ERROR_RATE = METRIC + "request.errorRate";
    public static final String REQUEST_COUNT = METRIC + "request.count";
    public static final String REQUEST_FAILURES = METRIC + "request.failures";

    /** Work the generator could not start. Cited by the validity finding that withholds capacity. */
    public static final String ITERATIONS_DROPPED = METRIC + "generator.iterations.dropped";

    /** Work the generator did start, which is the denominator the one above is a share of. */
    public static final String ITERATIONS_STARTED = METRIC + "generator.iterations.started";

    /**
     * Identifiers these ones used to have, so citations already stored keep resolving.
     *
     * <p>Findings persist their {@code evidenceIds}, and the validator discards any finding whose
     * citations do not resolve. Renaming without this map would not produce an error — it would
     * silently delete every stored interpretation of every run made before the rename, which is a
     * data loss disguised as a tidy-up.
     *
     * <p>The rename itself is not cosmetic: these identifiers name a transport in a package that
     * must not, and Phase 7 populates the same five measurements from a queue.
     */
    private static final Map<String, String> RENAMED = Map.of(
            METRIC + "http.rate.achieved", THROUGHPUT_ACHIEVED,
            METRIC + "http.errorRate", REQUEST_ERROR_RATE,
            METRIC + "http.requests", REQUEST_COUNT,
            METRIC + "http.failures", REQUEST_FAILURES);

    /** The prefix latency identifiers used before they stopped naming a transport. */
    private static final String RENAMED_LATENCY_PREFIX = METRIC + "http.latency.";

    /** Percentiles reported to the assistant, when the run measured them. */
    private static final List<Percentile> REPORTED_PERCENTILES =
            List.of(Percentile.P50, Percentile.P90, Percentile.P95, Percentile.P99);

    private EvidenceIds() {
    }

    public static String latency(Percentile percentile) {
        return METRIC + "latency." + percentile.label();
    }

    /**
     * Resolves an identifier that may have been written under an older name.
     *
     * <p>Called before a citation is checked, so a finding stored months ago still points at the
     * measurement it was actually about. An identifier that was never renamed passes through
     * unchanged.
     */
    public static String resolve(String reference) {
        if (reference == null) {
            return null;
        }
        String renamed = RENAMED.get(reference);
        if (renamed != null) {
            return renamed;
        }
        if (reference.startsWith(RENAMED_LATENCY_PREFIX)) {
            return METRIC + "latency." + reference.substring(RENAMED_LATENCY_PREFIX.length());
        }
        return reference;
    }

    /** Per-operation throughput, e.g. {@code metric:operation.createOrder.rate.achieved}. */
    public static String operationRate(OperationId operation) {
        return METRIC + "operation." + operation.value() + ".rate.achieved";
    }

    /** Per-operation latency, e.g. {@code metric:operation.createOrder.latency.p95}. */
    public static String operationLatency(OperationId operation, Percentile percentile) {
        return METRIC + "operation." + operation.value() + ".latency." + percentile.label();
    }

    /** Per-operation error rate, e.g. {@code metric:operation.createOrder.errorRate}. */
    public static String operationErrorRate(OperationId operation) {
        return METRIC + "operation." + operation.value() + ".errorRate";
    }

    public static String threshold(String thresholdId) {
        return THRESHOLD + thresholdId;
    }

    /** A computed difference between two executions, e.g. {@code delta:latency.p95}. */
    public static String delta(String metricId) {
        return DELTA + metricId;
    }

    /**
     * The measurements a run produced, keyed by the identifier used to cite them.
     *
     * <p>Only what was actually measured appears. A percentile the engine did not report is absent
     * rather than present with a placeholder, so the assistant is never shown a number that does
     * not exist.
     */
    public static Map<String, String> measurements(MeasuredResults results) {
        Map<String, String> measurements = new LinkedHashMap<>();

        results.targetLoadIfPresent().ifPresent(level ->
                measurements.put(THROUGHPUT_TARGET, level.displayWithUnit()));
        results.achievedRateIfPresent().ifPresent(rate ->
                measurements.put(THROUGHPUT_ACHIEVED, rate.displayWithUnit()));

        for (Percentile percentile : REPORTED_PERCENTILES) {
            results.latency().at(percentile).ifPresent(value ->
                    measurements.put(latency(percentile),
                            dev.vortex.core.threshold.Durations.display(value)));
        }

        measurements.put(REQUEST_ERROR_RATE, results.errorRate().display());
        measurements.put(REQUEST_COUNT, String.valueOf(results.requests()));
        measurements.put(REQUEST_FAILURES, String.valueOf(results.failures()));

        // Only when the engine reported them. A validity finding may cite what the generator did;
        // it may not cite a counter nobody collected, and an absent counter must not appear here as
        // a zero the assistant could then reason from.
        results.generation().iterationsDroppedIfPresent().ifPresent(dropped ->
                measurements.put(ITERATIONS_DROPPED, String.valueOf(dropped)));
        results.generation().iterationsStartedIfPresent().ifPresent(started ->
                measurements.put(ITERATIONS_STARTED, String.valueOf(started)));

        // Per-operation figures are cited far more often than aggregates, because they are what a
        // finding can actually be about: "status polling degraded while submission held steady" is
        // only sayable if both operations have names the assistant is allowed to use.
        for (OperationMetrics operation : results.perOperation().values()) {
            OperationId id = operation.operationId();
            operation.achievedRateIfPresent().ifPresent(rate ->
                    measurements.put(operationRate(id), rate.displayWithUnit()));
            measurements.put(operationErrorRate(id), operation.errorRate().display());
            for (Percentile percentile : REPORTED_PERCENTILES) {
                operation.latency().at(percentile).ifPresent(value ->
                        measurements.put(operationLatency(id, percentile),
                                dev.vortex.core.threshold.Durations.display(value)));
            }
        }

        for (MetricObservation observation : results.observations()) {
            measurements.put(observation.id(),
                    observation.display() + " (" + observation.source().label() + ", "
                            + observation.aggregation().label() + ")");
        }

        return measurements;
    }

    /**
     * Every identifier a finding is permitted to cite for this run.
     *
     * <p>Handed to the assistant as the allowed set, and used unchanged to validate what it returns.
     */
    public static List<String> availableFor(MeasuredResults results,
            dev.vortex.core.threshold.ThresholdEvaluation thresholds) {

        List<String> ids = new ArrayList<>(measurements(results).keySet());
        thresholds.results().forEach(result -> ids.add(threshold(result.thresholdId())));
        return ids;
    }

    /** Renders an identifier for a human, in a message about missing telemetry. */
    public static String describe(String reference) {
        if (reference.startsWith(METRIC)) {
            return reference.substring(METRIC.length());
        }
        if (reference.startsWith(THRESHOLD)) {
            return "threshold " + reference.substring(THRESHOLD.length());
        }
        if (reference.startsWith(DELTA)) {
            return "the " + reference.substring(DELTA.length()) + " difference";
        }
        return reference;
    }
}
