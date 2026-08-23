package dev.vortex.core.workload;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.metrics.SamplePoint;
import dev.vortex.core.shared.Concurrency;
import dev.vortex.core.shared.ErrorRate;
import dev.vortex.core.shared.RequestsPerSecond;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * One walk over stage durations, and an honest label on what it produced.
 *
 * <p>The rule used to live in two places — the analyzer and the k6 aggregator — and two
 * implementations of the same rule is one more than the rule survives. These tests pin the shared
 * one, and pin the distinction between a boundary that was measured and one that was computed.
 */
class StageWindowsTest {

    private static final Instant START = Instant.parse("2026-08-21T10:00:00Z");

    private static Stage rate(double value, Duration duration) {
        return new Stage(RequestsPerSecond.of(value), duration);
    }

    private static Stage vus(int value, Duration duration) {
        return new Stage(new Concurrency(value), duration);
    }

    @Nested
    @DisplayName("windows computed from the plan")
    class FromPlan {

        private final List<Stage> stages = List.of(
                rate(50, Duration.ofMinutes(5)),
                rate(100, Duration.ofMinutes(5)),
                rate(150, Duration.ofMinutes(5)));

        @Test
        void runBackToBackFromTheAnchor() {
            var windows = StageWindows.fromPlan(stages, START);

            assertThat(windows).hasSize(3);
            assertThat(windows.get(0).window().start()).isEqualTo(START);
            assertThat(windows.get(0).window().end())
                    .isEqualTo(windows.get(1).window().start());
            assertThat(windows.get(2).window().end())
                    .isEqualTo(START.plus(Duration.ofMinutes(15)));
        }

        @Test
        void carryTheirTargetLevel() {
            var windows = StageWindows.fromPlan(stages, START);

            assertThat(windows).extracting(window -> window.target().asDouble())
                    .containsExactly(50.0, 100.0, 150.0);
        }

        @Test
        @DisplayName("are labelled derived, even though the anchor itself was observed")
        void areLabelledDerived() {
            // The anchor comes from the first sample the run produced, which is real. The durations
            // are still what was asked for rather than what happened, and that is what the label is
            // about.
            assertThat(StageWindows.fromPlan(stages, START))
                    .allSatisfy(window -> assertThat(window.basis())
                            .isEqualTo(StageWindowBasis.DERIVED_FROM_PLAN));
        }

        @Test
        void aWorkloadWithNoStagesHasNoWindows() {
            assertThat(StageWindows.fromPlan(List.of(), START)).isEmpty();
        }

        @Test
        void aSingleStageIsOneWindow() {
            assertThat(StageWindows.fromPlan(List.of(rate(50, Duration.ofMinutes(10))), START))
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("the level at an offset")
    class LevelAt {

        private final List<Stage> stages = List.of(
                rate(50, Duration.ofMinutes(5)),
                rate(100, Duration.ofMinutes(5)));

        @Test
        void isTheStageInEffect() {
            assertThat(StageWindows.levelAt(stages, Duration.ZERO).asDouble()).isEqualTo(50);
            assertThat(StageWindows.levelAt(stages, Duration.ofMinutes(4)).asDouble()).isEqualTo(50);
            assertThat(StageWindows.levelAt(stages, Duration.ofMinutes(5)).asDouble()).isEqualTo(100);
            assertThat(StageWindows.levelAt(stages, Duration.ofMinutes(9)).asDouble()).isEqualTo(100);
        }

        @Test
        @DisplayName("is nothing past the end, because drain is not a stage")
        void isAbsentDuringDrain() {
            // Attributing drain traffic to the peak stage would make the peak look worse than it
            // was, at exactly the level a capacity claim is drawn from.
            assertThat(StageWindows.levelAt(stages, Duration.ofMinutes(11))).isNull();
        }

        @Test
        void isNothingWhenThereAreNoStages() {
            assertThat(StageWindows.levelAt(List.of(), Duration.ZERO)).isNull();
        }
    }

    @Nested
    @DisplayName("windows measured from the load generator's own output")
    class FromObservedVirtualUsers {

        private List<SamplePoint> samples(Integer... vus) {
            List<SamplePoint> points = new ArrayList<>();
            Instant at = START;
            for (Integer value : vus) {
                points.add(new SamplePoint(at, Duration.ofSeconds(5), RequestsPerSecond.of(10),
                        ErrorRate.ZERO, Duration.ofMillis(20), null, value));
                at = at.plusSeconds(5);
            }
            return points;
        }

        @Test
        void areTakenFromThePlateauEachStageReached() {
            var stages = List.of(vus(10, Duration.ofSeconds(10)), vus(20, Duration.ofSeconds(10)));

            var windows = StageWindows.fromObservedVirtualUsers(stages,
                    samples(10, 10, 20, 20));

            assertThat(windows).hasSize(2);
            assertThat(windows).allSatisfy(window ->
                    assertThat(window.basis()).isEqualTo(StageWindowBasis.OBSERVED));
            assertThat(windows.get(0).window().start()).isEqualTo(START);
            assertThat(windows.get(1).window().start()).isEqualTo(START.plusSeconds(10));
        }

        @Test
        @DisplayName("say nothing about an arrival-rate workload, whose targets k6 never reports")
        void doNotApplyToArrivalRateWorkloads() {
            var stages = List.of(rate(50, Duration.ofSeconds(10)));

            assertThat(StageWindows.fromObservedVirtualUsers(stages, samples(10, 10))).isEmpty();
        }

        @Test
        void areAbsentWhenNoVirtualUserCountsWereReported() {
            var stages = List.of(vus(10, Duration.ofSeconds(10)));
            List<SamplePoint> withoutVus = List.of(new SamplePoint(START, Duration.ofSeconds(5),
                    RequestsPerSecond.of(10), ErrorRate.ZERO, Duration.ofMillis(20), null));

            assertThat(StageWindows.fromObservedVirtualUsers(stages, withoutVus)).isEmpty();
        }

        @Test
        @DisplayName("fall back entirely when a stage's level was never reached")
        void areAbsentWhenAStageNeverReachedItsLevel() {
            // A run that stalled below its second target has not established where that stage began,
            // and the stages after it cannot be trusted either. Returning a partial answer would be
            // worse than returning none: the caller would treat computed boundaries as measured.
            var stages = List.of(vus(10, Duration.ofSeconds(10)), vus(50, Duration.ofSeconds(10)));

            assertThat(StageWindows.fromObservedVirtualUsers(stages, samples(10, 10, 12, 12)))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("what the basis permits")
    class Permissions {

        @Test
        void onlyMeasuredBoundariesMayStrengthenAFinding() {
            assertThat(StageWindowBasis.OBSERVED.canStrengthenAFinding()).isTrue();
            assertThat(StageWindowBasis.DERIVED_FROM_PLAN.canStrengthenAFinding()).isFalse();
        }

        @Test
        void bothSayWhatTheyAreInWordsAReaderSees() {
            assertThat(StageWindowBasis.OBSERVED.label()).isEqualTo("measured");
            assertThat(StageWindowBasis.DERIVED_FROM_PLAN.label()).contains("derived");
        }
    }
}
