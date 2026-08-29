package com.acltabontabon.vortex.k6;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.acltabontabon.vortex.core.metrics.FailureClass;
import com.acltabontabon.vortex.core.metrics.LatencyHistogram;
import com.acltabontabon.vortex.core.metrics.LatencyPercentiles;
import com.acltabontabon.vortex.core.metrics.LoadGeneration;
import com.acltabontabon.vortex.core.metrics.MetricSeries;
import com.acltabontabon.vortex.core.metrics.OperationMetrics;
import com.acltabontabon.vortex.core.metrics.ReliabilityBreakdown;
import com.acltabontabon.vortex.core.metrics.ResponseClass;
import com.acltabontabon.vortex.core.metrics.SamplePoint;
import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.LoadLevel;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.shared.Percentile;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reduces k6's raw sample stream into fixed-width buckets.
 *
 * <p>k6 emits one JSON object per metric sample. A run at 200 requests/sec produces several hundred
 * thousand of them, and forwarding that to a browser would cost more than the test itself — and
 * risk perturbing the very measurement being taken. So samples are aggregated here, on a background
 * thread, and only completed buckets travel any further.
 *
 * <pre>
 * k6 raw samples → this aggregator → 5-second bucket → ExecutionProgress → SSE
 * </pre>
 *
 * <p>The aggregator is deliberately single-pass and allocation-light: it never holds the whole
 * stream, only the bucket currently being filled plus running per-operation totals.
 *
 * <h2>How a sample becomes an operation</h2>
 * Every k6 sample carries the built-in {@code scenario} tag, and Vortex generates one k6 scenario per
 * operation — so the tag names the operation. The key is resolved through the map the
 * <em>plan</em> recorded, never by re-sanitising the tag and comparing strings: scenario keys are
 * lossy renderings of operation ids, two ids can collide, and matching by similarity would attribute
 * one operation's latency to another in precisely the case the collision suffix exists to prevent.
 *
 * <p>A tag that is not in the map — an imported script, or one of k6's own internal workloads — is
 * bucketed as {@code (unattributed)} rather than guessed at.
 *
 * <p>Latency percentiles within a bucket come from a pooled {@link LatencyHistogram} built from every
 * duration in that bucket, not a capped or reservoir-sampled subset — every observation contributes,
 * at bounded memory. A bucket's own p95 is therefore a bounded approximation (exact below 50
 * nanoseconds and at zero, within 2% above that — see {@link LatencyHistogram}), used for the live
 * display and, merged across a stage's buckets, for stage-level analysis. The authoritative end-of-test
 * percentiles still come from k6's own summary, which computes them across the whole run.
 */
public final class K6RawMetricsAggregator {

    /** Bucket width. Five seconds is fine enough to show a ramp and coarse enough to stay cheap. */
    public static final Duration BUCKET_WIDTH = Duration.ofSeconds(5);

    /**
     * Upper bound of what a k6-reported duration can be converted to as a signed 64-bit nanosecond
     * count: {@code 2^63}, exactly representable as a {@code double}.
     *
     * <p>{@code (double) Long.MAX_VALUE} is not exact — it rounds up to this same {@code 2^63} — so
     * checking against it would let values in {@code (Long.MAX_VALUE, 2^63]} through, and
     * {@link Math#round(double)} would silently saturate those to {@code Long.MAX_VALUE}. The greatest
     * representable {@code double} strictly below {@code 2^63}, in the {@code [2^62, 2^63)} binade, is
     * {@code 2^63 - 1024} (that binade's spacing is {@code 2^(62-52) = 1024}), which remains safely
     * within {@code long} range after rounding — so rejecting anything at or above this exact boundary
     * is what actually prevents silent saturation.
     */
    private static final double LONG_NANOS_UPPER_EXCLUSIVE = 0x1p63;

    /**
     * Validates and normalizes a k6-reported duration into the histogram's integer nanosecond domain.
     *
     * <p>Validated before any arithmetic: {@code Math.round(Double.NaN) == 0} and
     * {@code Math.round(Double.POSITIVE_INFINITY) == Long.MAX_VALUE} — both look like ordinary values,
     * and would silently corrupt the histogram rather than fail loudly. The range check happens in
     * {@code double} space, before the cast to {@code long}, so an out-of-range value fails clearly
     * rather than saturating.
     *
     * <p>This introduces at most 0.5ns of additional rounding error on top of {@code nanosExact}
     * itself (the definition of round-half-up) — a bound on this one normalization step, not a claim
     * about k6's own measurement precision or the floating-point multiplication that produced
     * {@code nanosExact}. It is kept entirely separate from, and never combined with, the histogram's
     * own {@code v <= reported <= v * 1.02} quantization bound.
     *
     * <p>Package-private, not private, so its exact boundary behaviour can be tested directly with
     * precise {@code double} values rather than only indirectly, through JSON round-tripping.
     */
    static long validateAndConvertToNanos(double millis) {
        if (!Double.isFinite(millis) || millis < 0) {
            throw new IllegalArgumentException(
                    "a k6 request duration must be a finite, non-negative number of milliseconds but "
                            + "was " + millis);
        }
        double nanosExact = millis * 1_000_000d;
        if (nanosExact >= LONG_NANOS_UPPER_EXCLUSIVE) {
            throw new IllegalArgumentException("a k6 request duration of " + millis
                    + "ms cannot be represented as a signed 64-bit nanosecond count");
        }
        return Math.round(nanosExact);
    }

    /**
     * Cap on durations retained per operation for its own end-of-run percentile estimation.
     *
     * <p>Unlike a bucket's own latency, which now pools every observation into a
     * {@link LatencyHistogram} (see the class doc), a per-operation distribution is still a capped,
     * reservoir-free list, computed once at the end of the run rather than merged across buckets — a
     * different, smaller, bounded-sampling-error problem, deliberately left as-is.
     */
    private static final int MAX_DURATIONS_PER_OPERATION = 40_000;

    /**
     * Cap on distinct engine codes retained in a distribution.
     *
     * <p>A well-behaved run produces a handful. A pathological one — a service echoing a request id
     * into its status, or an imported script hitting many hosts — could produce thousands, and an
     * unbounded map in a hot loop is how an aggregator becomes a source of load. Codes beyond the cap
     * stop being counted individually; the class totals, which is what conclusions rest on, stay
     * exact.
     */
    private static final int MAX_DISTINCT_CODES = 64;

    /** k6's tag names for the facts that decide what happened to a request. */
    private static final String STATUS_TAG = "status";
    private static final String ERROR_CODE_TAG = "error_code";
    private static final String EXPECTED_RESPONSE_TAG = "expected_response";

    private final ObjectMapper json = new ObjectMapper();

    /** Mutable accumulator for one bucket. */
    private static final class Bucket {

        private final Instant start;
        private final LatencyHistogram.Builder latency = LatencyHistogram.builder();
        private long httpRequests;
        private long httpFailures;
        private LoadLevel targetLoad;
        private Integer peakVus;

        /**
         * Work the generator could not start during this bucket.
         *
         * <p>Boxed and null until k6 says something, because a bucket k6 never reported on and a
         * bucket where the generator kept up are different facts, and only one of them supports a
         * capacity claim.
         */
        private Long droppedIterations;

        Bucket(Instant start) {
            this.start = start;
        }

        void recordDropped(long dropped) {
            droppedIterations = (droppedIterations == null ? 0L : droppedIterations) + dropped;
        }

        void addDuration(double millis) {
            latency.record(validateAndConvertToNanos(millis));
        }

        SamplePoint toSamplePoint() {
            double seconds = BUCKET_WIDTH.toMillis() / 1000.0;
            LatencyHistogram histogram = latency.build();
            return new SamplePoint(
                    start,
                    BUCKET_WIDTH,
                    RequestsPerSecond.of(httpRequests / seconds),
                    ErrorRate.of(httpFailures, httpRequests),
                    histogram.percentile(0.95).orElse(null),
                    targetLoad,
                    peakVus,
                    droppedIterations,
                    histogram,
                    httpRequests,
                    httpFailures);
        }

        /**
         * Whether this bucket carries anything worth keeping.
         *
         * <p>A bucket with no requests but with dropped work is the most diagnostic bucket a run can
         * produce — it is the generator saying it could not start anything at all. Discarding it
         * because no request was issued would throw away exactly the evidence that distinguishes a
         * saturated generator from a service that went quiet.
         */
        boolean carriesEvidence() {
            return httpRequests > 0 || (droppedIterations != null && droppedIterations > 0);
        }

        /**
         * Records the highest virtual-user count k6 reported inside this bucket.
         *
         * <p>The peak rather than the last reading, because a bucket that straddles a ramp step
         * should be attributed to the level it reached — taking the last sample would report a
         * plateau one bucket late, which is exactly the drift stage alignment exists to avoid.
         */
        void recordVus(int vus) {
            if (peakVus == null || vus > peakVus) {
                peakVus = vus;
            }
        }
    }

    /** Running totals for one operation across the whole run. */
    private static final class OperationAccumulator {

        private final String name;
        private final List<Double> durations = new ArrayList<>();
        private final OutcomeTally outcomes = new OutcomeTally();
        private long requests;
        private long failures;

        OperationAccumulator(String name) {
            this.name = name;
        }
    }

    /**
     * Running counts of what happened to requests, by class and by the engine's own code.
     *
     * <p>Kept per operation as well as per run, because one operation timing out while another
     * returns 500 is a sentence the aggregate cannot say — and attributing one operation's outcomes
     * to another is the invariant this whole keyed-accumulator arrangement exists to protect.
     */
    private static final class OutcomeTally {

        private final Map<ResponseClass, Long> byResponseClass = new EnumMap<>(ResponseClass.class);
        private final Map<FailureClass, Long> byFailureClass = new EnumMap<>(FailureClass.class);
        private final Map<String, Long> byCode = new LinkedHashMap<>();
        private long total;

        void record(K6OutcomeClassifier.Outcome outcome, long count) {
            total += count;
            byResponseClass.merge(outcome.responseClass(), count, Long::sum);
            if (outcome.isFailure()) {
                byFailureClass.merge(outcome.failureClass(), count, Long::sum);
            }
            if (byCode.size() < MAX_DISTINCT_CODES || byCode.containsKey(outcome.code())) {
                byCode.merge(outcome.code(), count, Long::sum);
            }
        }

        ReliabilityBreakdown toBreakdown() {
            return new ReliabilityBreakdown(byResponseClass, byFailureClass, byCode, total);
        }
    }

    /** The bucket key used for samples whose workload tag names nothing Vortex planned. */
    public static final String UNATTRIBUTED = "unattributed";

    /**
     * The outcome of aggregating a run's raw stream.
     *
     * @param generation  what the generator itself managed. {@link LoadGeneration#notReported()}
     *                    when the stream carried none of it — never a fabricated zero
     * @param reliability what kind of outcomes the run produced, across every operation
     */
    public record Aggregation(MetricSeries series, Map<OperationId, OperationMetrics> operations,
            long linesRead, LoadGeneration generation, ReliabilityBreakdown reliability) {

        public Aggregation {
            generation = generation == null ? LoadGeneration.notReported() : generation;
            reliability = reliability == null ? ReliabilityBreakdown.notReported() : reliability;
        }

        /** An aggregation of a stream that reported nothing about the generator or its outcomes. */
        public Aggregation(MetricSeries series, Map<OperationId, OperationMetrics> operations,
                long linesRead) {
            this(series, operations, linesRead, LoadGeneration.notReported(),
                    ReliabilityBreakdown.notReported());
        }

        /** Nothing could be aggregated: the stream was absent, unreadable or empty. */
        public static Aggregation none() {
            return new Aggregation(MetricSeries.empty(), Map.of(), 0);
        }
    }

    /**
     * Consumes a stream of k6 NDJSON lines, emitting each completed bucket as it closes.
     *
     * @param lines        the raw sample lines, in order
     * @param runDuration  planned duration, used to compute per-operation rates
     * @param byScenarioKey the plan's own map from k6 scenario key to operation; samples tagged with
     *                      anything else are left unattributed rather than matched by similarity
     * @param targetLoadAt supplies the workload's intended level at a given offset
     * @param onBucket     receives each completed bucket; must not block
     */
    public Aggregation aggregate(Iterable<String> lines, Duration runDuration,
            Map<String, OperationId> byScenarioKey, TargetLoadAt targetLoadAt,
            Consumer<SamplePoint> onBucket) {

        Map<String, OperationAccumulator> operations = new LinkedHashMap<>();
        List<SamplePoint> points = new ArrayList<>();
        OutcomeTally runOutcomes = new OutcomeTally();
        Bucket current = null;
        Instant firstSampleAt = null;
        long linesRead = 0;

        // Boxed and null until k6 reports them. An imported script, an engine that never emitted
        // the counter, or a stream that was truncated all leave these absent — and absent must not
        // become "the generator kept up", which is the one default that would quietly restore the
        // failure this whole phase exists to detect.
        Long iterations = null;
        Long droppedIterations = null;

        for (String line : lines) {
            linesRead++;
            if (line == null || line.isBlank()) {
                continue;
            }

            JsonNode node;
            try {
                node = json.readTree(line);
            } catch (Exception e) {
                // A truncated final line is normal when a run is cancelled mid-write. Skipping it
                // is correct; failing the whole aggregation over it would discard good evidence.
                continue;
            }

            if (!"Point".equals(node.path("type").asText())) {
                continue;
            }

            String metric = node.path("metric").asText();
            JsonNode data = node.path("data");
            Instant at = parseTime(data.path("time").asText());
            if (at == null) {
                continue;
            }
            double value = data.path("value").asDouble();
            String scenarioKey = data.path("tags").path(K6ScriptGenerator.OPERATION_TAG).asText("");
            String operationKey = byScenarioKey.containsKey(scenarioKey) ? scenarioKey : UNATTRIBUTED;

            if (firstSampleAt == null) {
                firstSampleAt = at;
                current = new Bucket(firstSampleAt);
                current.targetLoad = targetLoadAt.levelAt(Duration.ZERO);
            }

            while (at.isAfter(current.start.plus(BUCKET_WIDTH))) {
                SamplePoint completed = current.toSamplePoint();
                points.add(completed);
                onBucket.accept(completed);
                Instant nextStart = current.start.plus(BUCKET_WIDTH);
                current = new Bucket(nextStart);
                current.targetLoad =
                        targetLoadAt.levelAt(Duration.between(firstSampleAt, nextStart));
            }

            switch (metric) {
                case "http_reqs" -> {
                    long count = (long) value;
                    current.httpRequests = Math.addExact(current.httpRequests, count);
                    OperationAccumulator operation = accumulator(operations, operationKey);
                    operation.requests = Math.addExact(operation.requests, count);

                    // One sample per request, carrying the tags that say what happened to it.
                    // Classifying here rather than from http_req_failed keeps the distribution's
                    // total equal to the request count, so a share is a share of something real.
                    JsonNode tags = data.path("tags");
                    K6OutcomeClassifier.Outcome outcome = K6OutcomeClassifier.classify(
                            tags.path(STATUS_TAG).asText(""),
                            tags.path(ERROR_CODE_TAG).asText(""),
                            tags.path(EXPECTED_RESPONSE_TAG).asBoolean(true));
                    runOutcomes.record(outcome, count);
                    operation.outcomes.record(outcome, count);
                }
                case "http_req_failed" -> {
                    if (value > 0) {
                        current.httpFailures = Math.incrementExact(current.httpFailures);
                        OperationAccumulator operation = accumulator(operations, operationKey);
                        operation.failures = Math.incrementExact(operation.failures);
                    }
                }
                case "dropped_iterations" -> {
                    // The direct evidence that the offered load was never actually offered. k6
                    // publishes it and Vortex used to discard it, which is how a generator's
                    // ceiling became a quoted service capacity.
                    long dropped = (long) value;
                    droppedIterations = (droppedIterations == null ? 0L : droppedIterations) + dropped;
                    current.recordDropped(dropped);
                }
                case "iterations" -> iterations =
                        (iterations == null ? 0L : iterations) + (long) value;
                case "http_req_duration" -> {
                    current.addDuration(value);
                    OperationAccumulator operation = accumulator(operations, operationKey);
                    if (operation.durations.size() < MAX_DURATIONS_PER_OPERATION) {
                        operation.durations.add(value);
                    }
                }
                case "vus" -> {
                    // k6 publishes this itself; reading it adds no tag and no custom metric, which
                    // is what keeps stage alignment compatible with ADR-026. For a concurrency
                    // workload it is the difference between boundaries Vortex measured and
                    // boundaries it computed from planned durations and hoped were right.
                    current.recordVus((int) value);
                }
                default -> {
                    // Every other k6 metric is either derivable from the above or not something
                    // Vortex reports per bucket. The request-phase trends (blocked, connecting,
                    // tls_handshaking, sending, waiting, receiving) are read from the end-of-run
                    // summary instead, where k6 has already computed percentiles across the whole run
                    // rather than one stage's worth of buckets.
                }
            }
        }

        if (current != null && current.carriesEvidence()) {
            SamplePoint last = current.toSamplePoint();
            points.add(last);
            onBucket.accept(last);
        }

        double seconds = Math.max(1, runDuration.toMillis() / 1000.0);
        LoadGeneration generation = iterations == null && droppedIterations == null
                ? LoadGeneration.notReported()
                : new LoadGeneration(iterations, droppedIterations,
                        iterations == null ? null : iterations / seconds);

        return new Aggregation(
                new MetricSeries(BUCKET_WIDTH, points),
                toOperationMetrics(operations, byScenarioKey, runDuration),
                linesRead,
                generation,
                runOutcomes.toBreakdown());
    }

    private OperationAccumulator accumulator(Map<String, OperationAccumulator> operations, String key) {
        return operations.computeIfAbsent(key, OperationAccumulator::new);
    }

    private Map<OperationId, OperationMetrics> toOperationMetrics(
            Map<String, OperationAccumulator> operations, Map<String, OperationId> byScenarioKey,
            Duration runDuration) {

        Map<OperationId, OperationMetrics> metrics = new LinkedHashMap<>();
        double seconds = Math.max(1, runDuration.toMillis() / 1000.0);

        for (OperationAccumulator operation : operations.values()) {
            if (operation.requests == 0) {
                continue;
            }
            OperationId id = byScenarioKey.get(operation.name);
            if (id == null) {
                // Samples Vortex did not plan. Kept as a visible bucket rather than dropped, so a
                // run whose traffic went somewhere unexpected says so instead of silently losing it.
                id = OperationId.of(UNATTRIBUTED);
            }
            LatencyPercentiles.Builder latency = LatencyPercentiles.builder();
            if (!operation.durations.isEmpty()) {
                latency.at(Percentile.P50, percentile(operation.durations, 0.50))
                        .at(Percentile.P95, percentile(operation.durations, 0.95))
                        .at(Percentile.P99, percentile(operation.durations, 0.99));
            }
            metrics.put(id, new OperationMetrics(
                    id,
                    id.value(),
                    null,
                    RequestsPerSecond.of(operation.requests / seconds),
                    operation.requests,
                    operation.failures,
                    latency.build(),
                    operation.outcomes.toBreakdown()));
        }
        return metrics;
    }


    private static Duration percentile(List<Double> durations, double quantile) {
        if (durations.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(durations);
        sorted.sort(Comparator.naturalOrder());
        int index = (int) Math.ceil(quantile * sorted.size()) - 1;
        double millis = sorted.get(Math.clamp(index, 0, sorted.size() - 1));
        return Duration.ofNanos(Math.round(millis * 1_000_000d));
    }

    private Instant parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (Exception e) {
            try {
                return Instant.parse(raw);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** Supplies the workload's intended level at a given offset into the run. */
    @FunctionalInterface
    public interface TargetLoadAt {

        LoadLevel levelAt(Duration offset);

        /**
         * Builds a lookup from a plan's stages.
         *
         * <p>Delegates to {@link com.acltabontabon.vortex.core.workload.StageWindows}, which owns the cumulative
         * walk. It used to live here as well as in the analyzer, and two implementations of the same
         * rule is one more than the rule survives: they would eventually disagree about which stage a
         * sample belonged to, and the disagreement would surface as a bottleneck attributed to the
         * wrong level of load.
         */
        static TargetLoadAt fromStages(List<com.acltabontabon.vortex.core.workload.Stage> stages) {
            return offset -> com.acltabontabon.vortex.core.workload.StageWindows.levelAt(stages, offset);
        }
    }
}
