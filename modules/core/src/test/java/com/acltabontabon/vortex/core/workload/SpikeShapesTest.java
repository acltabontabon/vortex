package com.acltabontabon.vortex.core.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SpikeShapesTest {

    @Test
    void arrivalRateBuildsFourStagesJumpingFromBaselineAndBackDown() {
        RampingArrivalRateShape shape = SpikeShapes.arrivalRate(10, 100,
                Duration.ofSeconds(30), Duration.ofMinutes(1));

        assertThat(shape.stages()).hasSize(4);
        assertThat(shape.stages().get(0).target().asDouble()).isEqualTo(10);
        assertThat(shape.stages().get(1).target().asDouble()).isEqualTo(100);
        assertThat(shape.stages().get(2).target().asDouble()).isEqualTo(100);
        assertThat(shape.stages().get(3).target().asDouble()).isEqualTo(10);
        assertThat(shape.totalDuration()).isEqualTo(
                Duration.ofSeconds(30).plus(SpikeShapes.TRANSITION).plus(Duration.ofMinutes(1))
                        .plus(SpikeShapes.TRANSITION));
    }

    @Test
    void concurrencyBuildsTheSameFourStagePattern() {
        RampingConcurrencyShape shape = SpikeShapes.concurrency(5, 50,
                Duration.ofSeconds(15), Duration.ofSeconds(45));

        assertThat(shape.stages()).hasSize(4);
        assertThat(shape.stages().get(0).target().asDouble()).isEqualTo(5);
        assertThat(shape.stages().get(1).target().asDouble()).isEqualTo(50);
        assertThat(shape.stages().get(2).target().asDouble()).isEqualTo(50);
        assertThat(shape.stages().get(3).target().asDouble()).isEqualTo(5);
    }

    @Test
    void toleratesBaselineEqualToPeak() {
        assertThatCode(() -> SpikeShapes.arrivalRate(20, 20, Duration.ofSeconds(30), Duration.ofMinutes(1)))
                .doesNotThrowAnyException();
        assertThatCode(() -> SpikeShapes.concurrency(20, 20, Duration.ofSeconds(30), Duration.ofMinutes(1)))
                .doesNotThrowAnyException();
    }
}
