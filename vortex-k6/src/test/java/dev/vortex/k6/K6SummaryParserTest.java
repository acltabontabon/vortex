package dev.vortex.k6;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vortex.core.metrics.LoadGeneration;
import dev.vortex.core.metrics.MetricSeries;
import dev.vortex.core.metrics.ReliabilityBreakdown;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.shared.Percentile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Contract tests against real k6 output.
 *
 * <p>{@code summary-success.json} is a genuine k6 v2 summary captured from a run against the
 * bundled demo service, not a hand-written approximation. The remaining fixtures cover the ways a
 * run can go wrong, because "the engine crashed" and "the file is half-written" are ordinary
 * outcomes that must not lose a user's evidence or produce a wrong number.
 */
class K6SummaryParserTest {

    private final K6SummaryParser parser = new K6SummaryParser();
    private static final Instant STARTED_AT = Instant.parse("2026-08-21T10:00:00Z");

    private static String fixture(String name) {
        try (var in = K6SummaryParserTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private dev.vortex.core.metrics.MeasuredResults parse(String fixtureName) {
        return parser.parse(fixture(fixtureName), STARTED_AT, RequestsPerSecond.of(20),
                MetricSeries.empty(), Map.of(), List.of());
    }

    @Test
    @DisplayName("a successful run is parsed from genuine k6 v2 output")
    void successfulRun() {
        var results = parse("summary-success.json");

        assertThat(results.requests()).isEqualTo(202);
        assertThat(results.failures()).isZero();
        assertThat(results.latency().at(Percentile.P95))
                .hasValueSatisfying(p95 -> assertThat(p95.toMillis()).isEqualTo(52));
        assertThat(results.errorRate().asFraction()).isZero();
    }

    @Test
    @DisplayName("offered and achieved throughput are carried separately, never derived from each other")
    void offeredAndAchievedAreDistinct() {
        var results = parse("summary-success.json");

        // The gap between them is the signal. Deriving one from the other would erase it.
        assertThat(results.targetLoadIfPresent())
                .hasValueSatisfying(level -> assertThat(level.asDouble()).isEqualTo(20.0));
        assertThat(results.achievedRateIfPresent())
                .hasValueSatisfying(rate -> assertThat(rate.asDouble()).isCloseTo(20.0,
                        org.assertj.core.data.Offset.offset(0.1)));
        assertThat(results.deliveredFraction())
                .hasValueSatisfying(fraction -> assertThat(fraction).isCloseTo(1.0,
                        org.assertj.core.data.Offset.offset(0.02)));
    }

    @Test
    @DisplayName("a concurrency run reports no delivered fraction, because nothing was offered")
    void concurrencyRunsHaveNoShortfall() {
        var results = parser.parse(fixture("summary-success.json"), STARTED_AT,
                dev.vortex.core.shared.Concurrency.of(50), MetricSeries.empty(), Map.of(), List.of());

        // Throughput under a closed workload is an outcome of latency, not a target that was missed.
        // Reporting a shortfall would invent a target nobody set.
        assertThat(results.deliveredFraction()).isEmpty();
        assertThat(results.targetLoadIfPresent())
                .hasValueSatisfying(level -> assertThat(level.unit()).isEqualTo("VUs"));
    }

    @Test
    @DisplayName("k6's http_req_failed counts failures in `passes`, and reading `fails` would invert the error rate")
    void errorRateIsReadFromTheCorrectField() {
        var results = parse("summary-threshold-violation.json");

        // The fixture has fails=77772 and passes=628. Reading `fails` would report a 99% error
        // rate for a run that actually failed 0.8% of its requests.
        assertThat(results.failures()).isEqualTo(628);
        assertThat(results.errorRate().asFraction()).isCloseTo(0.008,
                org.assertj.core.data.Offset.offset(0.0005));

        assertThat(parser.reportedFailureRate(fixture("summary-threshold-violation.json")))
                .hasValueSatisfying(rate -> assertThat(rate)
                        .isCloseTo(results.errorRate().asFraction(),
                                org.assertj.core.data.Offset.offset(0.0001)));
    }

    @Test
    void thresholdViolationsReportedByTheEngineAreVisible() {
        assertThat(parser.engineThresholdsPassed(fixture("summary-threshold-violation.json"))).isFalse();
        assertThat(parser.engineThresholdsPassed(fixture("summary-success.json"))).isTrue();
    }

    @Test
    void aRunWithNoMetricsIsRejectedWithAnActionableMessage() {
        assertThatThrownBy(() -> parse("summary-empty-metrics.json"))
                .isInstanceOf(K6SummaryParser.UnreadableSummaryException.class)
                .hasMessageContaining("contained no metrics")
                .hasMessageContaining("check the captured error output");
    }

    @Test
    @DisplayName("a half-written summary fails cleanly and points at the preserved artifacts")
    void truncatedOutputIsRejected() {
        assertThatThrownBy(() -> parse("summary-truncated.json"))
                .isInstanceOf(K6SummaryParser.UnreadableSummaryException.class)
                .hasMessageContaining("preserved");
    }

    @Test
    void outputThatIsNotJsonAtAllIsRejected() {
        assertThatThrownBy(() -> parse("summary-invalid.txt"))
                .isInstanceOf(K6SummaryParser.UnreadableSummaryException.class);
    }

    @Test
    @DisplayName("an imported script needs no Vortex metrics to produce a usable result")
    void importedScriptsStillYieldThroughput() {
        // Nothing here depends on anything Vortex added to the script — the generator emits no
        // custom metrics at all, so an imported script and a generated one are read the same way.
        var results = parse("summary-threshold-violation.json");

        assertThat(results.requests()).isPositive();
        assertThat(results.achievedRateIfPresent()).isPresent();
        assertThat(results.latency().at(Percentile.P95)).isPresent();
    }

    @Test
    void failuresCanNeverExceedTotalRequests() {
        var results = parse("summary-threshold-violation.json");

        assertThat(results.failures()).isLessThanOrEqualTo(results.requests());
    }

    @Nested
    @DisplayName("the rest of what k6 already reported")
    class TheRestOfWhatK6Reports {

        @Test
        @DisplayName("request phases are read, so a slow connection is not quoted as service latency")
        void phasesAreRead() {
            var phases = parse("summary-success.json").phases();

            assertThat(phases.isEmpty()).isFalse();
            assertThat(phases.serverThinkTimeIfPresent()).isPresent();
            assertThat(phases.waiting().at(Percentile.P95)).isPresent();
            assertThat(phases.connecting().at(Percentile.P95)).isPresent();
        }

        @Test
        @DisplayName("time to first byte is no greater than the whole request it is part of")
        void waitingIsPartOfTheWhole() {
            var results = parse("summary-success.json");

            // The property, not the numbers: whatever the machine, the server's think time is a
            // component of the request duration and cannot exceed it.
            assertThat(results.phases().waiting().at(Percentile.P95))
                    .hasValueSatisfying(waiting -> assertThat(waiting)
                            .isLessThanOrEqualTo(results.latency().at(Percentile.P95).orElseThrow()));
        }

        @Test
        @DisplayName("iterations are read from the summary's own whole-run counter")
        void iterationsAreRead() {
            var generation = parse("summary-success.json").generation();

            assertThat(generation.wasReported()).isTrue();
            assertThat(generation.iterationsStartedIfPresent()).isPresent();
            assertThat(generation.iterationRateIfPresent()).isPresent();
        }

        @Test
        @DisplayName("a summary that never mentions dropped work leaves it absent, not zero")
        void absentDroppedIterationsStayAbsent() {
            var generation = parse("summary-success.json").generation();

            // k6 omits the counter both from a run that dropped nothing and from an engine that
            // never tracked it. Only the raw stream can tell those apart, so the summary alone
            // must not answer — and must not answer "zero".
            assertThat(generation.iterationsDroppedIfPresent()).isEmpty();
            assertThat(generation.droppedWork()).isFalse();
            assertThat(generation.droppedFraction()).isEmpty();
        }

        @Test
        @DisplayName("what the stream established survives a summary that is silent about it")
        void theStreamFillsWhatTheSummaryOmits() {
            var fromStream = new K6RawMetricsAggregator.Aggregation(
                    MetricSeries.empty(), Map.of(), 3,
                    new LoadGeneration(900L, 412L, 15.0), ReliabilityBreakdown.notReported());

            var results = parser.parse(fixture("summary-success.json"), STARTED_AT,
                    RequestsPerSecond.of(20), fromStream, List.of());

            assertThat(results.generation().iterationsDroppedIfPresent()).hasValue(412L);
            assertThat(results.generation().droppedWork()).isTrue();
        }

        @Test
        @DisplayName("a summary with no phase breakdown reports none, rather than zero-length phases")
        void absentPhasesStayAbsent() {
            // An engine that reported traffic but no phase trends — the shape an imported script
            // run with --summary-export produces. "No breakdown" must not render as six phases
            // that each took no time.
            String withoutPhases = """
                    {"state":{"testRunDurationMs":60000},
                     "metrics":{"http_reqs":{"values":{"count":100,"rate":1.67}},
                                "http_req_duration":{"values":{"p(95)":120.0}}}}""";

            var results = parser.parse(withoutPhases, STARTED_AT, RequestsPerSecond.of(20),
                    MetricSeries.empty(), Map.of(), List.of());

            assertThat(results.requests()).isEqualTo(100);
            assertThat(results.phases().isEmpty()).isTrue();
            assertThat(results.phases().serverThinkTimeIfPresent()).isEmpty();
            assertThat(results.generation().wasReported()).isFalse();
        }

        @Test
        @DisplayName("a summary alone classifies no outcomes, which is not the same as all succeeding")
        void aSummaryAloneClassifiesNothing() {
            var reliability = parse("summary-success.json").reliability();

            // The tags that say what happened live in the raw stream. A parser given only a summary
            // knows how many failed and nothing about how — and must say so.
            assertThat(reliability.wasReported()).isFalse();
            assertThat(reliability.unreachedShare()).isEmpty();
        }
    }
}
