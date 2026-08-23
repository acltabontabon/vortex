package dev.vortex.core.threshold;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.metrics.LatencyPercentiles;
import dev.vortex.core.metrics.MeasuredResults;
import dev.vortex.core.metrics.MetricSeries;
import dev.vortex.core.metrics.TimeWindow;
import dev.vortex.core.shared.Percentile;
import dev.vortex.core.shared.RequestsPerSecond;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ThresholdEvaluatorTest {

    private final ThresholdEvaluator evaluator = new ThresholdEvaluator();

    @Test
    void passesWhenEveryObjectiveIsMet() {
        var evaluation = evaluator.evaluate(Fixtures.thresholds(), Fixtures.results(280, 0.0008));

        assertThat(evaluation.overall()).isEqualTo(Verdict.PASS);
        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.failures()).isEmpty();
    }

    @Test
    void failsWhenLatencyExceedsItsObjective() {
        var evaluation = evaluator.evaluate(Fixtures.thresholds(), Fixtures.results(522, 0.0008));

        assertThat(evaluation.overall()).isEqualTo(Verdict.FAIL);
        assertThat(evaluation.failures())
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.thresholdId()).isEqualTo("latency.p95");
                    assertThat(result.observed()).isEqualTo("522 ms");
                });
    }

    @Test
    void failsWhenTheErrorRateExceedsItsObjective() {
        var evaluation = evaluator.evaluate(Fixtures.thresholds(), Fixtures.results(280, 0.05));

        assertThat(evaluation.failures())
                .anySatisfy(result -> assertThat(result.thresholdId()).isEqualTo("errorRate"));
    }

    @Test
    @DisplayName("observedPosition is the observed value as a fraction of the threshold, for a bar to place a marker with")
    void observedPositionIsComputedForLatencyAndErrorRate() {
        var evaluation = evaluator.evaluate(Fixtures.thresholds(), Fixtures.results(250, 0.0004));

        assertThat(evaluation.results())
                .filteredOn(result -> result.thresholdId().equals("latency.p95"))
                .singleElement()
                .satisfies(result -> assertThat(result.observedPosition()).isEqualTo(0.5, org.assertj.core.data.Offset.offset(0.01)));

        var failing = evaluator.evaluate(Fixtures.thresholds(), Fixtures.results(1000, 0.0008));
        assertThat(failing.results())
                .filteredOn(result -> result.thresholdId().equals("latency.p95"))
                .singleElement()
                .satisfies(result -> assertThat(result.observedPosition()).isGreaterThan(1.0));
    }

    @Test
    @DisplayName("observedPosition is null when the measurement was unavailable, never guessed")
    void observedPositionIsNullWhenUnevaluated() {
        MeasuredResults withoutP99 = new MeasuredResults(
                new TimeWindow(Fixtures.NOW, Fixtures.NOW.plus(Duration.ofMinutes(1))),
                RequestsPerSecond.of(20), RequestsPerSecond.of(20), 200, 0,
                LatencyPercentiles.builder().atMillis(95, 120).build(),
                Map.of(), MetricSeries.empty(), List.of());

        var evaluation = evaluator.evaluate(Fixtures.thresholds(), withoutP99);

        assertThat(evaluation.unevaluated()).singleElement()
                .satisfies(result -> assertThat(result.observedPosition()).isNull());
    }

    @Test
    void aBoundaryValueIsWithinTheObjective() {
        var evaluation = evaluator.evaluate(
                ThresholdSet.of(LatencyThreshold.ofMillis(95, 500)),
                Fixtures.results(500, 0));

        assertThat(evaluation.overall()).isEqualTo(Verdict.PASS);
    }

    /**
     * The most consequential behaviour in this class: an objective that could not be measured must
     * never be reported as satisfied. Silently passing an unchecked objective is what makes a green
     * performance report worse than no report at all.
     */
    @Test
    @DisplayName("an objective whose measurement is missing is reported as unevaluated, never as passed")
    void missingMeasurementIsNotAPass() {
        MeasuredResults withoutP99 = new MeasuredResults(
                new TimeWindow(Fixtures.NOW, Fixtures.NOW.plus(Duration.ofMinutes(1))),
                RequestsPerSecond.of(20), RequestsPerSecond.of(20), 200, 0,
                LatencyPercentiles.builder().atMillis(95, 120).build(),
                Map.of(), MetricSeries.empty(), List.of());

        var evaluation = evaluator.evaluate(Fixtures.thresholds(), withoutP99);

        assertThat(evaluation.overall()).isEqualTo(Verdict.NOT_EVALUATED);
        assertThat(evaluation.passed()).isFalse();
        assertThat(evaluation.unevaluated())
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.thresholdId()).isEqualTo("latency.p99");
                    assertThat(result.note()).contains("could not be checked");
                });
    }

    @Test
    void aFailureOutweighsAnUnevaluatedObjective() {
        MeasuredResults slowAndIncomplete = new MeasuredResults(
                new TimeWindow(Fixtures.NOW, Fixtures.NOW.plus(Duration.ofMinutes(1))),
                RequestsPerSecond.of(20), RequestsPerSecond.of(20), 200, 0,
                LatencyPercentiles.builder().atMillis(95, 900).build(),
                Map.of(), MetricSeries.empty(), List.of());

        assertThat(evaluator.evaluate(Fixtures.thresholds(), slowAndIncomplete).overall())
                .isEqualTo(Verdict.FAIL);
    }

    @Test
    void anErrorRateCannotBeEvaluatedWithoutRequests() {
        MeasuredResults nothingHappened = new MeasuredResults(
                new TimeWindow(Fixtures.NOW, Fixtures.NOW.plusSeconds(1)),
                RequestsPerSecond.of(20), null, 0, 0,
                LatencyPercentiles.empty(), Map.of(), MetricSeries.empty(), List.of());

        var evaluation = evaluator.evaluate(
                ThresholdSet.of(ErrorRateThreshold.ofPercent(1)), nothingHappened);

        assertThat(evaluation.overall()).isEqualTo(Verdict.NOT_EVALUATED);
        assertThat(evaluation.unevaluated().getFirst().note()).contains("No requests were recorded");
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("objectives scoped to one operation")
    class PerOperation {

        @Test
        @DisplayName("an operation is judged on its own measurements, not the aggregate")
        void operationsAreJudgedIndividually() {
            // The aggregate p95 is 280 ms and comfortably inside a 500 ms objective. Account lookup
            // is fine at 120 ms; order lookup is failing at 900 ms. An aggregate would hide it, and
            // it usually hides the low-volume operation nobody notices until it matters.
            var results = Fixtures.resultsWithOperations(280, 0.001,
                    Fixtures.perOperation(120, 900));

            var evaluation = evaluator.evaluate(ThresholdSet.of(
                    LatencyThreshold.of(Percentile.P95, Duration.ofMillis(500)),
                    LatencyThreshold.of(Fixtures.GET_ACCOUNT, Percentile.P95, Duration.ofMillis(200)),
                    LatencyThreshold.of(Fixtures.GET_ORDER, Percentile.P95, Duration.ofMillis(200))),
                    results);

            assertThat(evaluation.overall()).isEqualTo(Verdict.FAIL);
            assertThat(evaluation.failures()).singleElement()
                    .satisfies(result ->
                            assertThat(result.thresholdId()).isEqualTo("latency.p95.getOrder"));
        }

        @Test
        @DisplayName("an operation that issued no requests is unevaluated, never passed")
        void anOperationWithNoTrafficIsNotAPass() {
            var results = Fixtures.resultsWithOperations(120, 0.0,
                    Fixtures.perOperation(120, 120));

            var evaluation = evaluator.evaluate(ThresholdSet.of(
                    LatencyThreshold.of(Fixtures.CREATE_ORDER, Percentile.P95,
                            Duration.ofMillis(500))),
                    results);

            // It has not met its objective; it has simply never been asked. Reporting a pass here
            // would be the same failure mode as passing an unmeasured aggregate.
            assertThat(evaluation.overall()).isEqualTo(Verdict.NOT_EVALUATED);
            assertThat(evaluation.unevaluated()).singleElement()
                    .satisfies(result -> assertThat(result.note())
                            .contains("No measurements were recorded for createOrder"));
        }

        @Test
        @DisplayName("a run with no per-operation breakdown says so rather than guessing")
        void missingBreakdownIsReportedHonestly() {
            var evaluation = evaluator.evaluate(ThresholdSet.of(
                    LatencyThreshold.of(Fixtures.GET_ORDER, Percentile.P95, Duration.ofMillis(200))),
                    Fixtures.results(120, 0.0));

            assertThat(evaluation.overall()).isEqualTo(Verdict.NOT_EVALUATED);
            assertThat(evaluation.unevaluated()).singleElement()
                    .satisfies(result -> assertThat(result.note())
                            .contains("no per-operation breakdown"));
        }

        @Test
        void scopedObjectivesGetDistinctIdentifiersAndDescriptions() {
            var overall = LatencyThreshold.of(Percentile.P95, Duration.ofMillis(500));
            var scoped = LatencyThreshold.of(Fixtures.GET_ORDER, Percentile.P95,
                    Duration.ofMillis(200));

            assertThat(overall.id()).isEqualTo("latency.p95");
            assertThat(scoped.id()).isEqualTo("latency.p95.getOrder");
            assertThat(scoped.describe()).isEqualTo("p95 latency below 200 ms for getOrder");
        }
    }

    @Test
    void anEmptyThresholdSetProducesNoVerdict() {
        var evaluation = evaluator.evaluate(ThresholdSet.empty(), Fixtures.results(100, 0));

        assertThat(evaluation.overall()).isEqualTo(Verdict.NOT_EVALUATED);
    }

    @Test
    void thresholdIdentifiersAreStableAcrossReordering() {
        assertThat(LatencyThreshold.of(Percentile.P95, Duration.ofMillis(500)).id())
                .isEqualTo("latency.p95");
        assertThat(ErrorRateThreshold.ofPercent(1).id()).isEqualTo("errorRate");
    }
}
