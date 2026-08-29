package com.acltabontabon.vortex.k6;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.metrics.FailureClass;
import com.acltabontabon.vortex.core.metrics.LoadGeneration;
import com.acltabontabon.vortex.core.metrics.ResponseClass;
import com.acltabontabon.vortex.core.metrics.SamplePoint;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.shared.OperationId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Attribution has to go through the plan, not through the tag string.
 *
 * <p>k6 scenario keys are sanitised renderings of operation ids, and sanitising is lossy: two ids can
 * produce the same candidate key. Recovering an operation by re-sanitising the tag and comparing
 * would attribute one operation's latency to another in precisely the case the disambiguating suffix
 * exists to prevent — a wrong number that looks exactly like a right one.
 */
class K6RawMetricsAggregatorTest {

    private final K6RawMetricsAggregator aggregator = new K6RawMetricsAggregator();
    private final EffectiveTestPlan plan = Fixtures.plan();

    /** A minimal synthetic k6 {@code Point} line, for tests that need exact control over a value. */
    private static String point(String metric, String isoTime, Object value) {
        return "{\"metric\":\"" + metric + "\",\"type\":\"Point\",\"data\":{\"time\":\"" + isoTime
                + "\",\"value\":" + value + ",\"tags\":{\"scenario\":\"getaccount\"}}}";
    }

    /** A timestamp {@code offsetMicros} microseconds into the first bucket's window. */
    private static String timeAt(int offsetMicros) {
        return String.format("2026-08-21T04:45:43.%06d+08:00", offsetMicros % 900_000);
    }

    private static List<String> fixture(String name) {
        try (var in = K6RawMetricsAggregatorTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private K6RawMetricsAggregator.Aggregation aggregate(String fixtureName,
            Map<String, OperationId> byScenarioKey) {

        return aggregator.aggregate(fixture(fixtureName), Duration.ofMinutes(1), byScenarioKey,
                K6RawMetricsAggregator.TargetLoadAt.fromStages(plan.stages()), _ -> { });
    }

    @Test
    @DisplayName("samples are attributed to operations through the mapping the plan recorded")
    void samplesAreAttributedThroughThePlan() {
        var aggregation = aggregate("raw-metrics-sample.ndjson", plan.operationsByScenarioKey());

        assertThat(aggregation.operations()).containsKeys(Fixtures.GET_ACCOUNT, Fixtures.GET_ORDER);
        assertThat(aggregation.operations().get(Fixtures.GET_ACCOUNT).requests()).isPositive();
        assertThat(aggregation.operations().get(Fixtures.GET_ORDER).requests()).isPositive();
        assertThat(aggregation.operations().get(Fixtures.GET_ACCOUNT).latency().p95()).isPresent();
    }

    @Test
    @DisplayName("a workload tag the plan never declared is left unattributed, not guessed at")
    void unknownWorkloadTagsAreNotGuessed() {
        var aggregation = aggregate("raw-metrics-sample.ndjson", plan.operationsByScenarioKey());

        assertThat(aggregation.operations())
                .containsKey(OperationId.of(K6RawMetricsAggregator.UNATTRIBUTED));
        assertThat(aggregation.operations()
                .get(OperationId.of(K6RawMetricsAggregator.UNATTRIBUTED)).requests())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("with no mapping — an imported script — everything is unattributed rather than wrong")
    void anImportedScriptAttributesNothing() {
        var aggregation = aggregate("raw-metrics-sample.ndjson", Map.of());

        assertThat(aggregation.operations()).hasSize(1);
        assertThat(aggregation.operations())
                .containsOnlyKeys(OperationId.of(K6RawMetricsAggregator.UNATTRIBUTED));
    }

    @Test
    void bucketsCarryThroughputErrorRateAndTheLevelThatWasOffered() {
        List<SamplePoint> published = new ArrayList<>();
        var aggregation = aggregator.aggregate(fixture("raw-metrics-sample.ndjson"),
                Duration.ofMinutes(1), plan.operationsByScenarioKey(),
                K6RawMetricsAggregator.TargetLoadAt.fromStages(plan.stages()), published::add);

        assertThat(aggregation.series().isEmpty()).isFalse();
        assertThat(published).isNotEmpty();
        assertThat(published).allSatisfy(point -> {
            assertThat(point.requestRateIfPresent()).isPresent();
            assertThat(point.targetLoadIfPresent())
                    .hasValueSatisfying(level ->
                            assertThat(level.unit()).isEqualTo("requests/sec"));
        });
    }

    @Test
    @DisplayName("a half-written final line is skipped, not allowed to discard the good evidence")
    void truncatedStreamsStillYieldWhatWasRead() {
        var aggregation = aggregate("raw-metrics-truncated.ndjson", plan.operationsByScenarioKey());

        // A run cancelled mid-write leaves a partial line. Failing the whole aggregation over it
        // would throw away measurements that were successfully recorded.
        assertThat(aggregation.linesRead()).isPositive();
        assertThat(aggregation.operations().get(Fixtures.GET_ACCOUNT).requests()).isEqualTo(2);
        assertThat(aggregation.operations().get(Fixtures.GET_ACCOUNT).failures()).isEqualTo(1);
    }

    @Test
    @DisplayName("a stream that is malformed throughout yields an empty aggregation, not a guess")
    void systemicallyMalformedStreamsYieldNothing() {
        // Not just a truncated last line — every line fails to parse, as a systemic bug in whatever
        // produced the stream might do. The aggregator must not turn that into a plausible-looking
        // empty-but-successful run: an empty series here is what keeps ThresholdEvaluator, one layer
        // up, from ever reporting PASS on a run it never actually measured.
        List<String> garbage = List.of("not json at all", "{", "<html>error page</html>");

        var aggregation = aggregator.aggregate(garbage, Duration.ofMinutes(1),
                plan.operationsByScenarioKey(),
                K6RawMetricsAggregator.TargetLoadAt.fromStages(plan.stages()), _ -> { });

        assertThat(aggregation.series().isEmpty()).isTrue();
        assertThat(aggregation.operations()).isEmpty();
        assertThat(aggregation.generation()).isEqualTo(LoadGeneration.notReported());
        assertThat(aggregation.linesRead()).isEqualTo(garbage.size());
    }

    @Nested
    @DisplayName("what the generator itself managed")
    class GeneratorEvidence {

        @Test
        @DisplayName("dropped iterations are read, and are the total the stream reported")
        void droppedIterationsAreRead() {
            var aggregation = aggregate("raw-metrics-dropped-iterations.ndjson",
                    plan.operationsByScenarioKey());

            assertThat(aggregation.generation().iterationsDroppedIfPresent()).hasValue(412L);
            assertThat(aggregation.generation().droppedWork()).isTrue();
            assertThat(aggregation.generation().wasReported()).isTrue();
        }

        @Test
        @DisplayName("a stream that never mentions dropped work leaves it absent, not zero")
        void silenceIsNotZero() {
            var aggregation = aggregate("raw-metrics-sample.ndjson", plan.operationsByScenarioKey());

            // The distinction this whole phase turns on. An engine that said nothing about its own
            // throughput has not reported that it kept up, and a capacity claim may not rest on the
            // difference being ignored.
            assertThat(aggregation.generation().iterationsDroppedIfPresent()).isEmpty();
            assertThat(aggregation.generation().droppedWork()).isFalse();
        }

        @Test
        @DisplayName("a bucket in which only dropped work happened still reaches the series")
        void aBucketOfNothingButDropsSurvives() {
            List<SamplePoint> published = new ArrayList<>();
            aggregator.aggregate(fixture("raw-metrics-dropped-iterations.ndjson"),
                    Duration.ofMinutes(1), plan.operationsByScenarioKey(),
                    K6RawMetricsAggregator.TargetLoadAt.fromStages(plan.stages()), published::add);

            // The most diagnostic bucket a run can produce: the generator reporting it could not
            // start anything at all. It used to be discarded for having issued no requests.
            assertThat(published).anySatisfy(point -> {
                assertThat(point.iterationsDroppedIfPresent()).hasValue(400L);
                assertThat(point.requestRateIfPresent())
                        .hasValueSatisfying(rate -> assertThat(rate.asDouble()).isZero());
            });
        }

        @Test
        @DisplayName("drops are attributed to the bucket they happened in, not to the run as a whole")
        void dropsLandInTheirOwnBucket() {
            List<SamplePoint> published = new ArrayList<>();
            aggregator.aggregate(fixture("raw-metrics-dropped-iterations.ndjson"),
                    Duration.ofMinutes(1), plan.operationsByScenarioKey(),
                    K6RawMetricsAggregator.TargetLoadAt.fromStages(plan.stages()), published::add);

            long reportedByBuckets = published.stream()
                    .map(SamplePoint::iterationsDroppedIfPresent)
                    .flatMap(java.util.Optional::stream)
                    .mapToLong(Long::longValue)
                    .sum();

            // Per-bucket attribution is what lets a validity finding name the level at which the
            // generator fell behind, rather than only the fact that it did somewhere.
            assertThat(reportedByBuckets).isEqualTo(412L);
            assertThat(published).filteredOn(p -> p.iterationsDroppedIfPresent().isPresent())
                    .hasSize(2);
        }
    }

    @Nested
    @DisplayName("what kind of outcomes the run produced")
    class OutcomeDistribution {

        @Test
        @DisplayName("failures are separated by how they failed, not counted as one tally")
        void failuresAreDistinguished() {
            var reliability = aggregate("raw-metrics-dropped-iterations.ndjson",
                    plan.operationsByScenarioKey()).reliability();

            // "A 503 at 400 requests/sec and a connection reset at 400 requests/sec are different
            // findings" — the whole reason a distribution replaces a count.
            assertThat(reliability.count(FailureClass.APPLICATION)).isEqualTo(1);
            assertThat(reliability.count(FailureClass.CONNECTION)).isEqualTo(1);
            assertThat(reliability.count(FailureClass.TIMEOUT)).isEqualTo(1);
        }

        @Test
        @DisplayName("the classified outcomes account for every request, and no more")
        void theDistributionIsComplete() {
            var aggregation = aggregate("raw-metrics-dropped-iterations.ndjson",
                    plan.operationsByScenarioKey());
            long requests = aggregation.operations().values().stream()
                    .mapToLong(op -> op.requests()).sum();

            assertThat(aggregation.reliability().total()).isEqualTo(requests);
            assertThat(aggregation.reliability().byResponseClass().values().stream()
                    .mapToLong(Long::longValue).sum()).isEqualTo(requests);
        }

        @Test
        @DisplayName("a status the workload declared as expected is not a failure")
        void declaredExpectationsAreNotFailures() {
            var reliability = aggregate("raw-metrics-dropped-iterations.ndjson",
                    plan.operationsByScenarioKey()).reliability();

            // The fixture's 404 carries expected_response=true. Counting it as a failure would
            // contradict the error rate k6 computed from the same tag.
            assertThat(reliability.count(ResponseClass.CLIENT_ERROR)).isEqualTo(1);
            assertThat(reliability.count(FailureClass.APPLICATION)).isEqualTo(1);
        }

        @Test
        @DisplayName("one operation's outcomes are never attributed to another")
        void outcomesAreNotCrossAttributed() {
            var operations = aggregate("raw-metrics-dropped-iterations.ndjson",
                    plan.operationsByScenarioKey()).operations();

            // getorder saw only transport-level failures; getaccount saw only answered responses.
            var order = operations.get(Fixtures.GET_ORDER).reliability();
            var account = operations.get(Fixtures.GET_ACCOUNT).reliability();

            assertThat(order.count(FailureClass.APPLICATION)).isZero();
            assertThat(account.count(FailureClass.CONNECTION)).isZero();
            assertThat(account.count(FailureClass.TIMEOUT)).isZero();
        }

        @Test
        @DisplayName("a stream carrying no status information reports nothing, not success")
        void unclassifiedIsNotSuccess() {
            var reliability = aggregator.aggregate(
                    List.of("{\"metric\":\"vus\",\"type\":\"Point\",\"data\":"
                            + "{\"time\":\"2026-08-21T04:45:43.100000+08:00\",\"value\":4,"
                            + "\"tags\":{}}}"),
                    Duration.ofMinutes(1), plan.operationsByScenarioKey(),
                    K6RawMetricsAggregator.TargetLoadAt.fromStages(plan.stages()), _ -> { })
                    .reliability();

            assertThat(reliability.wasReported()).isFalse();
            assertThat(reliability.isEmpty()).isTrue();
            assertThat(reliability.unreachedShare()).isEmpty();
        }
    }

    @Nested
    @DisplayName("pooled latency histograms replace the capped raw-duration list")
    class PooledLatencyHistograms {

        @Test
        @DisplayName("valid durations flow into the bucket's own pooled histogram and preserved counts")
        void validDurationsPopulateTheHistogramAndCounts() {
            List<String> lines = List.of(
                    point("http_reqs", timeAt(0), 1),
                    point("http_req_duration", timeAt(0), 41.5),
                    point("http_reqs", timeAt(100_000), 1),
                    point("http_req_duration", timeAt(100_000), 42.0),
                    // close the first bucket
                    point("http_reqs", "2026-08-21T04:45:49.000000+08:00", 1),
                    point("http_req_duration", "2026-08-21T04:45:49.000000+08:00", 10.0));

            List<SamplePoint> published = new ArrayList<>();
            aggregator.aggregate(lines, Duration.ofMinutes(1), Map.of(),
                    K6RawMetricsAggregator.TargetLoadAt.fromStages(plan.stages()), published::add);

            SamplePoint first = published.get(0);
            assertThat(first.latencyHistogramIfPresent()).isPresent();
            assertThat(first.latencyHistogramIfPresent().get().totalCount()).isEqualTo(2);
            assertThat(first.requestCountIfPresent()).hasValue(2L);
            assertThat(first.failureCountIfPresent()).hasValue(0L);
        }

        @Test
        @DisplayName("every observation in a bucket contributes to its histogram, not a capped subset — unlike the old reservoir-sampled list")
        void everyObservationContributesRegardlessOfVolume() {
            int count = 4_500; // beyond the old 4,000-sample-per-bucket reservoir cap
            List<String> lines = new ArrayList<>(count + 2);
            for (int i = 0; i < count; i++) {
                lines.add(point("http_req_duration", timeAt(i), 10.0));
            }
            lines.add(point("http_reqs", "2026-08-21T04:45:49.000000+08:00", 1));
            lines.add(point("http_req_duration", "2026-08-21T04:45:49.000000+08:00", 10.0));

            List<SamplePoint> published = new ArrayList<>();
            aggregator.aggregate(lines, Duration.ofMinutes(1), Map.of(),
                    K6RawMetricsAggregator.TargetLoadAt.fromStages(plan.stages()), published::add);

            assertThat(published.get(0).latencyHistogramIfPresent().get().totalCount())
                    .isEqualTo(count);
        }

        @Test
        @DisplayName("a bucket's own p95 comes from its pooled histogram, exactly as merging that same histogram would compute it")
        void bucketP95ComesFromTheHistogram() {
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < 95; i++) {
                lines.add(point("http_req_duration", timeAt(i * 10), 10.0));
            }
            for (int i = 95; i < 100; i++) {
                lines.add(point("http_req_duration", timeAt(i * 10), 1_000.0));
            }
            lines.add(point("http_reqs", "2026-08-21T04:45:49.000000+08:00", 1));
            lines.add(point("http_req_duration", "2026-08-21T04:45:49.000000+08:00", 1.0));

            List<SamplePoint> published = new ArrayList<>();
            aggregator.aggregate(lines, Duration.ofMinutes(1), Map.of(),
                    K6RawMetricsAggregator.TargetLoadAt.fromStages(plan.stages()), published::add);

            SamplePoint first = published.get(0);
            var expectedP95 = first.latencyHistogramIfPresent().get().percentile(0.95);
            assertThat(first.p95IfPresent()).isEqualTo(expectedP95);
        }

        @Test
        @DisplayName("every bucket's duration, including a short final one, remains the nominal BUCKET_WIDTH — a known, unchanged limitation")
        void bucketDurationRemainsNominalEvenForAShortFinalBucket() {
            List<String> lines = List.of(
                    point("http_reqs", timeAt(0), 1),
                    point("http_req_duration", timeAt(0), 10.0),
                    // a second bucket whose raw stream ends less than a second after it opens
                    point("http_reqs", "2026-08-21T04:45:49.000000+08:00", 1),
                    point("http_req_duration", "2026-08-21T04:45:49.000000+08:00", 10.0));

            List<SamplePoint> published = new ArrayList<>();
            aggregator.aggregate(lines, Duration.ofMinutes(1), Map.of(),
                    K6RawMetricsAggregator.TargetLoadAt.fromStages(plan.stages()), published::add);

            assertThat(published).hasSizeGreaterThanOrEqualTo(2);
            assertThat(published).allSatisfy(sample ->
                    assertThat(sample.duration()).isEqualTo(K6RawMetricsAggregator.BUCKET_WIDTH));
        }
    }

    @Nested
    @DisplayName("source normalization: k6's double milliseconds into checked long nanoseconds")
    class SourceNormalization {

        @Test
        void zeroIsValid() {
            assertThat(K6RawMetricsAggregator.validateAndConvertToNanos(0.0)).isEqualTo(0L);
        }

        @Test
        @DisplayName("a value below the source's own rounding resolution collapses to exact zero — an accepted source-precision limit, not a defect")
        void subNanosecondFractionRoundsToZero() {
            assertThat(K6RawMetricsAggregator.validateAndConvertToNanos(0.0000001)).isEqualTo(0L);
        }

        @Test
        @DisplayName("a value resolving to exactly one nanosecond")
        void resolvesToExactlyOneNanosecond() {
            assertThat(K6RawMetricsAggregator.validateAndConvertToNanos(0.000_001)).isEqualTo(1L);
        }

        @Test
        @DisplayName("a value at a round-half-up rounding boundary")
        void roundingBoundary() {
            // 1.5 microseconds: round-half-up must land on 2, not 1
            assertThat(K6RawMetricsAggregator.validateAndConvertToNanos(0.001_5)).isEqualTo(1_500L);
        }

        @Test
        void negativeValuesAreRejected() {
            assertThatThrownBy(() -> K6RawMetricsAggregator.validateAndConvertToNanos(-1.0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void nanIsRejected() {
            assertThatThrownBy(() -> K6RawMetricsAggregator.validateAndConvertToNanos(Double.NaN))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void positiveInfinityIsRejected() {
            assertThatThrownBy(
                    () -> K6RawMetricsAggregator.validateAndConvertToNanos(Double.POSITIVE_INFINITY))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void negativeInfinityIsRejected() {
            assertThatThrownBy(
                    () -> K6RawMetricsAggregator.validateAndConvertToNanos(Double.NEGATIVE_INFINITY))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a value comfortably below the 2^63-nanosecond-equivalent boundary (~292 years) succeeds")
        void comfortablyBelowTheBoundarySucceeds() {
            // 2^63 ns is ~9.223372036854776e12 ms; one trillion ms below that is a wide, unambiguous
            // safety margin (far larger than the ~1024ns granularity doubles have at this magnitude).
            assertThat(K6RawMetricsAggregator.validateAndConvertToNanos(8_223_372_036_854.0))
                    .isPositive();
        }

        @Test
        @DisplayName("a value comfortably above the 2^63-nanosecond-equivalent boundary fails, never silently saturating to Long.MAX_VALUE")
        void comfortablyAboveTheBoundaryFails() {
            assertThatThrownBy(
                    () -> K6RawMetricsAggregator.validateAndConvertToNanos(10_223_372_036_854.0))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("a grossly out-of-range but finite value fails, never silently saturating")
        void grosslyOutOfRangeValueFails() {
            assertThatThrownBy(() -> K6RawMetricsAggregator.validateAndConvertToNanos(1e15))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
