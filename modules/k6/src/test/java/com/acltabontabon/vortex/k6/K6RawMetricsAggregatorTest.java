package com.acltabontabon.vortex.k6;

import static org.assertj.core.api.Assertions.assertThat;

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
}
