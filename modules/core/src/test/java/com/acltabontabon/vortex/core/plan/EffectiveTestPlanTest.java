package com.acltabontabon.vortex.core.plan;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.workload.ConstantArrivalRateShape;
import com.acltabontabon.vortex.core.workload.ConstantConcurrencyShape;
import com.acltabontabon.vortex.core.workload.RampingArrivalRateShape;
import com.acltabontabon.vortex.core.workload.Stage;
import com.acltabontabon.vortex.core.workload.TestType;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link EffectiveTestPlan#idealizedAverageArrivalRate()} — the ramp-aware comparison basis a
 * whole-run shortfall must use instead of the peak, so a ramp's own shape is never charged as a
 * shortfall it never was. See {@code StageObservation.rateShortfall()} for the same correction
 * applied per stage.
 */
class EffectiveTestPlanTest {

    @Test
    @DisplayName("a flat workload's average is its peak")
    void flatWorkloadReducesToPeak() {
        var plan = Fixtures.plan(TestType.AVERAGE_LOAD,
                ConstantArrivalRateShape.of(50, Duration.ofMinutes(10)));

        assertThat(plan.idealizedAverageArrivalRate())
                .hasValueSatisfying(level -> assertThat(level.asDouble()).isEqualTo(50.0));
    }

    @Test
    @DisplayName("a ramp's average honours the time it spent below its own peak")
    void rampingWorkloadAveragesTheWholeSchedule() {
        // 50 req/s flat for 20s, ramp to 100 over 20s, ramp to 150 over 20s: this is the exact
        // shape that produced a false "45% shortfall" finding against an 83.119 req/s achieved
        // rate the service delivered essentially exactly.
        var shape = new RampingArrivalRateShape(RequestsPerSecond.of(50), List.of(
                Stage.ofRate(50, Duration.ofSeconds(20)),
                Stage.ofRate(100, Duration.ofSeconds(20)),
                Stage.ofRate(150, Duration.ofSeconds(20))));
        var plan = Fixtures.plan(TestType.SPIKE, shape);

        assertThat(plan.idealizedAverageArrivalRate())
                .hasValueSatisfying(level -> assertThat(level.asDouble()).isCloseTo(83.33, org.assertj.core.data.Offset.offset(0.01)));
        assertThat(plan.idealizedAverageArrivalRate().orElseThrow().asDouble())
                .isLessThan(plan.peakLevel().asDouble());
    }

    @Test
    @DisplayName("a concurrency workload has no arrival rate to average")
    void closedWorkloadHasNoAverage() {
        var plan = Fixtures.plan(TestType.AVERAGE_LOAD,
                ConstantConcurrencyShape.of(50, Duration.ofMinutes(10)));

        assertThat(plan.idealizedAverageArrivalRate()).isEmpty();
    }
}
