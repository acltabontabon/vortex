package com.acltabontabon.vortex.core.workload;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.workload.ConstantArrivalRateShape;
import com.acltabontabon.vortex.core.workload.ConstantConcurrencyShape;
import com.acltabontabon.vortex.core.workload.OperationMix;
import com.acltabontabon.vortex.core.workload.RampingArrivalRateShape;
import com.acltabontabon.vortex.core.workload.RampingConcurrencyShape;
import com.acltabontabon.vortex.core.workload.Stage;
import com.acltabontabon.vortex.core.workload.LoadShape;
import com.acltabontabon.vortex.core.workload.WorkloadModel;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The test type is intent, and these tests exist mainly to keep it that way.
 *
 * <p>The tempting simplification — "a stress test is a ramp, a soak is a constant" — would be wrong
 * and would quietly stop engineers describing the traffic their service actually receives.
 */
class TestTypeTest {

    @ParameterizedTest
    @EnumSource(TestType.class)
    @DisplayName("every test type states the question it answers, in a service owner's terms")
    void everyTypeStatesItsQuestion(TestType type) {
        assertThat(type.label()).isNotBlank();
        assertThat(type.question()).endsWith("?");
        assertThat(type.guidance()).isNotBlank();

        // Leading with the question is the whole point: nobody can answer
        // "constant-arrival-rate or ramping-arrival-rate?" without first learning a load generator.
        assertThat(type.question()).doesNotContain("executor").doesNotContain("arrival-rate");
    }

    @ParameterizedTest
    @EnumSource(TestType.class)
    @DisplayName("any test type composes with any workload model and shape")
    void anyTypeComposesWithAnyWorkload(TestType type) {
        for (LoadShape shape : everyWorkloadShape()) {
            var workload = Fixtures.workload("s", type,
                    OperationMix.single(Fixtures.GET_ORDER), shape);

            assertThat(workload.type()).isEqualTo(type);
            assertThat(workload.model()).isEqualTo(shape.model());
        }
    }

    /**
     * The four shapes, from two independent choices.
     *
     * <p>A soak may legitimately be closed-model when the caller is a fixed worker fleet; a spike may
     * be a step change in either quantity; a breakpoint search may ramp either. Vortex guides that
     * choice and refuses none of it.
     */
    private static List<LoadShape> everyWorkloadShape() {
        return List.of(
                ConstantArrivalRateShape.of(20, Duration.ofMinutes(1)),
                new RampingArrivalRateShape(com.acltabontabon.vortex.core.shared.RequestsPerSecond.of(10),
                        List.of(Stage.ofRate(20, Duration.ofMinutes(1)),
                                Stage.ofRate(40, Duration.ofMinutes(1)))),
                ConstantConcurrencyShape.of(20, Duration.ofMinutes(1)),
                new RampingConcurrencyShape(com.acltabontabon.vortex.core.shared.Concurrency.of(10),
                        List.of(Stage.ofVus(20, Duration.ofMinutes(1)),
                                Stage.ofVus(40, Duration.ofMinutes(1)))));
    }

    @Test
    @DisplayName("the established six, and nothing invented alongside them")
    void theTaxonomyIsTheEstablishedOne() {
        assertThat(TestType.values()).containsExactly(
                TestType.SMOKE, TestType.AVERAGE_LOAD, TestType.STRESS,
                TestType.SPIKE, TestType.SOAK, TestType.BREAKPOINT);
    }

    @Test
    @DisplayName("saturating types are marked as such, for safety confirmation rather than scheduling")
    void saturatingTypesAreIdentified() {
        assertThat(TestType.STRESS.isSaturating()).isTrue();
        assertThat(TestType.SPIKE.isSaturating()).isTrue();
        assertThat(TestType.BREAKPOINT.isSaturating()).isTrue();

        assertThat(TestType.SMOKE.isSaturating()).isFalse();
        assertThat(TestType.AVERAGE_LOAD.isSaturating()).isFalse();
        assertThat(TestType.SOAK.isSaturating()).isFalse();
    }

    @Test
    @DisplayName("both workload models explain when they fit, because the choice is the engineer's")
    void bothWorkloadModelsGuideTheChoice() {
        for (WorkloadModel model : WorkloadModel.values()) {
            assertThat(model.question()).endsWith("?");
            assertThat(model.guidance()).isNotBlank();
            assertThat(model.controlledUnit()).isIn("requests/sec", "VUs");
        }

        assertThat(WorkloadModel.OPEN.guidance()).contains("independently of how the service responds");
        assertThat(WorkloadModel.CLOSED.guidance()).contains("bounded population");
    }
}
