package dev.vortex.core.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.shared.Percentile;
import dev.vortex.core.threshold.ErrorRateThreshold;
import dev.vortex.core.threshold.LatencyThreshold;
import dev.vortex.core.threshold.ThresholdSet;
import dev.vortex.core.workload.ConstantArrivalRateShape;
import dev.vortex.core.workload.ConstantConcurrencyShape;
import dev.vortex.core.workload.OperationMix;
import dev.vortex.core.workload.WorkloadModel;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A workload is the workload Vortex intends to reproduce, and the invariants here are the ones that
 * stop it meaning something other than what it says.
 */
class WorkloadTest {

    @Nested
    @DisplayName("a single operation is a complete performance target")
    class SingleOperation {

        @Test
        @DisplayName("one operation needs no mix, no flow and no business framing")
        void oneOperationIsEnough() {
            var workload = Fixtures.singleOperationWorkload();

            assertThat(workload.isSingleOperation()).isTrue();
            assertThat(workload.operations().sharePercent(Fixtures.CREATE_ORDER)).isEqualTo("100");
            assertThat(workload.peakLevel().displayWithUnit()).isEqualTo("50 requests/sec");
            assertThat(workload.totalDuration()).isEqualTo(Duration.ofMinutes(30));
        }

        @Test
        void aSingleOperationWorkloadStillStatesTheQuestionItAnswers() {
            assertThat(Fixtures.singleOperationWorkload().question())
                    .isEqualTo(TestType.AVERAGE_LOAD.question());
        }
    }

    @Nested
    @DisplayName("a realistic multi-operation workload")
    class MultiOperation {

        @Test
        void aMixDescribesTrafficComposition() {
            var workload = Fixtures.workload("production-peak", TestType.STRESS, Fixtures.fourWayMix(),
                    ConstantArrivalRateShape.of(120, Duration.ofMinutes(30)));

            assertThat(workload.operations().size()).isEqualTo(4);
            assertThat(workload.operations().sharePercent(Fixtures.GET_ACCOUNT)).isEqualTo("55");
            assertThat(workload.operations().sharePercent(Fixtures.CREATE_ORDER)).isEqualTo("15");
            // Shape only: the magnitude lives on the workload, so it cannot be repeated per operation.
            assertThat(workload.peakLevel().asDouble()).isEqualTo(120.0);
        }
    }

    @Nested
    @DisplayName("a mix only means traffic under an open workload")
    class ClosedWorkloadInvariant {

        @Test
        @DisplayName("a concurrency workload may not spread itself across several operations")
        void concurrencyRejectsAMix() {
            assertThatThrownBy(() -> Fixtures.workload("bad", TestType.AVERAGE_LOAD,
                    Fixtures.operationMix(),
                    ConstantConcurrencyShape.of(50, Duration.ofMinutes(5))))
                    .isInstanceOf(IllegalArgumentException.class)
                    // The message has to explain the reason, not just state the rule: someone who
                    // only reads "not supported" will assume it is an implementation gap and work
                    // around it by splitting the workload, which reproduces the same wrong number.
                    .hasMessageContaining("Weights divide virtual users rather than traffic")
                    .hasMessageContaining("depends on how fast the operation it calls responds")
                    .hasMessageContaining("switch it to an arrival-rate workload");
        }

        @Test
        void aConcurrencyWorkloadWithOneOperationIsFine() {
            var workload = Fixtures.concurrencyWorkload();

            assertThat(workload.model()).isEqualTo(WorkloadModel.CLOSED);
            assertThat(workload.peakLevel().unit()).isEqualTo("VUs");
        }

        @Test
        @DisplayName("an arrival-rate workload carries a mix happily, because there the weights are traffic")
        void arrivalRateAcceptsAMix() {
            assertThat(Fixtures.averageLoadWorkload().operations().size()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("objectives")
    class Objectives {

        @Test
        @DisplayName("an objective for an operation the workload does not exercise is rejected")
        void objectivesMustNameAnOperationInTheMix() {
            assertThatThrownBy(() -> new Workload(
                    dev.vortex.core.shared.WorkloadId.of("s"), "s", "", "", TestType.AVERAGE_LOAD,
                    OperationMix.single(Fixtures.GET_ORDER),
                    ConstantArrivalRateShape.of(10, Duration.ofMinutes(1)),
                    ThresholdSet.of(LatencyThreshold.of(Fixtures.CREATE_ORDER, Percentile.P95,
                            Duration.ofMillis(500))),
                    WorkloadSource.manual(), java.util.Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("createOrder")
                    .hasMessageContaining("not one of the operations it exercises");
        }

        @Test
        @DisplayName("a workload refines the project's objectives rather than restating them")
        void workloadThresholdsLayerOverProjectDefaults() {
            var project = ThresholdSet.of(
                    LatencyThreshold.of(Percentile.P95, Duration.ofMillis(500)),
                    ErrorRateThreshold.ofPercent(1));

            var workload = new Workload(
                    dev.vortex.core.shared.WorkloadId.of("s"), "s", "", "", TestType.STRESS,
                    OperationMix.single(Fixtures.GET_ORDER),
                    ConstantArrivalRateShape.of(10, Duration.ofMinutes(1)),
                    ThresholdSet.of(LatencyThreshold.of(Percentile.P95, Duration.ofMillis(800))),
                    WorkloadSource.manual(), java.util.Map.of());

            var effective = workload.effectiveThresholds(project);

            // The p95 is replaced, not duplicated: two contradictory p95 objectives could never
            // both hold, and a run judged against both would be judged against neither.
            assertThat(effective.size()).isEqualTo(2);
            assertThat(effective.latencyThresholds()).singleElement()
                    .satisfies(threshold ->
                            assertThat(threshold.maximum()).isEqualTo(Duration.ofMillis(800)));
            assertThat(effective.errorRateThreshold()).isPresent();
        }
    }

    @Nested
    @DisplayName("provenance")
    class Provenance {

        @Test
        @DisplayName("a manually entered number never claims to be production evidence")
        void manualNumbersAreLabelledAsSuch() {
            assertThat(Fixtures.averageLoadWorkload().source().isProductionInformed()).isFalse();
            assertThat(Fixtures.averageLoadWorkload().source().describe())
                    .isEqualTo("Manually entered");
        }

        @Test
        void anObservedNumberCarriesWhereItCameFrom() {
            var window = Observation.over(Fixtures.NOW.minusSeconds(3600), Fixtures.NOW);
            var observed = WorkloadSource.observed("Grafana · rolling 30 days", window);

            assertThat(observed.isProductionInformed()).isTrue();
            assertThat(observed.describe())
                    .isEqualTo("From observed production traffic · Grafana · rolling 30 days");
            assertThat(observed.observation()).isEqualTo(window);
        }

        @Test
        @DisplayName("a derived figure is distinguishable from a measured one")
        void derivedIsNotTheSameAsObserved() {
            var when = Observation.at(Fixtures.NOW);

            assertThat(WorkloadSource.derived("dashboard", when, "1.5 × peak").kind())
                    .isNotEqualTo(WorkloadSource.observed("dashboard", when).kind());
        }

        @Test
        @DisplayName("the arithmetic behind a derived figure survives an edit to the description")
        void derivationLivesOnTheSourceNotTheDescription() {
            var derived = WorkloadSource.derived("Grafana", Observation.at(Fixtures.NOW),
                    "observed peak 120 × 1.5 = 180");

            assertThat(derived.derivationIfPresent()).hasValue("observed peak 120 × 1.5 = 180");
            assertThat(WorkloadSource.manual().derivationIfPresent()).isEmpty();
        }
    }

    @Nested
    @DisplayName("names")
    class Names {

        @Test
        void namesAreConstrainedBecauseTheyAppearOnTheCommandLine() {
            assertThatThrownBy(() -> Fixtures.workload("has spaces", TestType.SMOKE,
                    OperationMix.single(Fixtures.GET_ORDER),
                    ConstantArrivalRateShape.of(1, Duration.ofSeconds(30))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("letters, digits, '-' and '_'");
        }

        @Test
        void aBlankNameIsRejected() {
            assertThatThrownBy(() -> Fixtures.workload("  ", TestType.SMOKE,
                    OperationMix.single(Fixtures.GET_ORDER),
                    ConstantArrivalRateShape.of(1, Duration.ofSeconds(30))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be blank");
        }
    }
}
