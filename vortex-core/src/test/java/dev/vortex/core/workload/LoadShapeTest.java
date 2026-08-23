package dev.vortex.core.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vortex.core.shared.Concurrency;
import dev.vortex.core.shared.RequestsPerSecond;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The four workload shapes, and the things they refuse to let you say. */
class LoadShapeTest {

    @Nested
    @DisplayName("open workloads control an arrival rate")
    class OpenWorkloads {

        @Test
        void aConstantRateIsASingleStage() {
            var workload = ConstantArrivalRateShape.of(50, Duration.ofMinutes(30));

            assertThat(workload.model()).isEqualTo(WorkloadModel.OPEN);
            assertThat(workload.isRamping()).isFalse();
            assertThat(workload.stages()).singleElement()
                    .satisfies(stage -> assertThat(stage.target().displayWithUnit())
                            .isEqualTo("50 requests/sec"));
            assertThat(workload.peakLevel().asDouble()).isEqualTo(50.0);
        }

        @Test
        @DisplayName("a ramp's request count is predictable, because the schedule is the workload")
        void arrivalsArePredictable() {
            var workload = new RampingArrivalRateShape(RequestsPerSecond.of(0.001), List.of(
                    Stage.ofRate(100, Duration.ofSeconds(10)),
                    Stage.ofRate(100, Duration.ofSeconds(10))));

            // Trapezoidal over the ramp, then flat: ~500 + 1000.
            assertThat(workload.estimatedRequests()).hasValueSatisfying(
                    total -> assertThat(total).isBetween(1400L, 1600L));
        }

        @Test
        void everyStageOfAnArrivalRateWorkloadMustTargetARate() {
            assertThatThrownBy(() -> new RampingArrivalRateShape(RequestsPerSecond.of(10),
                    List.of(Stage.ofVus(20, Duration.ofMinutes(1)))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must target a request rate")
                    .hasMessageContaining("controls one quantity throughout");
        }
    }

    @Nested
    @DisplayName("closed workloads control concurrency")
    class ClosedWorkloads {

        @Test
        void aConstantConcurrencyIsASingleStage() {
            var workload = ConstantConcurrencyShape.of(50, Duration.ofMinutes(10));

            assertThat(workload.model()).isEqualTo(WorkloadModel.CLOSED);
            assertThat(workload.peakLevel().displayWithUnit()).isEqualTo("50 VUs");
        }

        @Test
        @DisplayName("a request count is not predictable, because it depends on the latency being measured")
        void requestCountIsNotPredictable() {
            var workload = ConstantConcurrencyShape.of(50, Duration.ofMinutes(10));

            assertThat(workload.estimatedRequests()).isEmpty();
            assertThat(workload.requestEstimateCaveat())
                    .contains("issues its next request when the previous one returns")
                    .contains("the latency this run is measuring");
        }

        @Test
        void everyStageOfAConcurrencyWorkloadMustTargetVirtualUsers() {
            assertThatThrownBy(() -> new RampingConcurrencyShape(Concurrency.of(10),
                    List.of(Stage.ofRate(20, Duration.ofMinutes(1)))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must target a virtual-user count");
        }

        @Test
        void aFractionOfAVirtualUserIsNotAThing() {
            assertThatThrownBy(() -> Concurrency.of(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least 1 virtual user");
        }
    }

    @Nested
    @DisplayName("the two quantities never convert into one another")
    class UnitsStaySeparate {

        @Test
        @DisplayName("50 requests/sec and 50 VUs are the same number and different facts")
        void sameNumberDifferentQuantity() {
            var rate = RequestsPerSecond.of(50);
            var vus = Concurrency.of(50);

            assertThat(rate.asDouble()).isEqualTo(vus.asDouble());
            assertThat(rate.sameQuantityAs(vus)).isFalse();
            assertThat(rate.displayWithUnit()).isNotEqualTo(vus.displayWithUnit());
        }

        @Test
        void everyLevelCanStateItsOwnUnit() {
            assertThat(RequestsPerSecond.of(40.2).displayWithUnit()).isEqualTo("40.2 requests/sec");
            assertThat(Concurrency.of(50).displayWithUnit()).isEqualTo("50 VUs");
        }
    }

    @Nested
    @DisplayName("bounds")
    class Bounds {

        @Test
        void aWorkloadMustHaveAPositiveDuration() {
            assertThatThrownBy(() -> ConstantArrivalRateShape.of(10, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be positive");
        }

        @Test
        @DisplayName("a run longer than a day is an orchestration problem, not a workload setting")
        void aWorkloadIsCappedAtADay() {
            assertThatThrownBy(() -> ConstantArrivalRateShape.of(10, Duration.ofHours(25)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at most 24 hours");
        }

        @Test
        void aRampNeedsAtLeastOneStage() {
            assertThatThrownBy(() ->
                    new RampingArrivalRateShape(RequestsPerSecond.of(10), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one stage");
        }
    }
}
