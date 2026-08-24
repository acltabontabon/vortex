package com.acltabontabon.vortex.core.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.shared.Concurrency;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.workload.ConstantArrivalRateShape;
import com.acltabontabon.vortex.core.workload.ConstantConcurrencyShape;
import com.acltabontabon.vortex.core.workload.LoadShape;
import com.acltabontabon.vortex.core.workload.RampingArrivalRateShape;
import com.acltabontabon.vortex.core.workload.SpikeShapes;
import com.acltabontabon.vortex.core.workload.Stage;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A property test: every shape {@link ShapeKind} is meant to describe round-trips back through
 *  {@link ShapeKindClassifier#classify} to the kind it was built as. */
class ShapeKindClassifierTest {

    @Test
    void classifiesAConstantShapeAsSteady() {
        LoadShape shape = new ConstantArrivalRateShape(RequestsPerSecond.of(10), Duration.ofMinutes(1));
        assertThat(ShapeKindClassifier.classify(shape)).isEqualTo(ShapeKind.STEADY);

        LoadShape closed = new ConstantConcurrencyShape(Concurrency.of(10), Duration.ofMinutes(1));
        assertThat(ShapeKindClassifier.classify(closed)).isEqualTo(ShapeKind.STEADY);
    }

    @Test
    void classifiesASpikePatternAsSpike() {
        LoadShape shape = SpikeShapes.arrivalRate(10, 100, Duration.ofSeconds(30), Duration.ofMinutes(1));
        assertThat(ShapeKindClassifier.classify(shape)).isEqualTo(ShapeKind.SPIKE);

        LoadShape closed = SpikeShapes.concurrency(5, 50, Duration.ofSeconds(30), Duration.ofMinutes(1));
        assertThat(ShapeKindClassifier.classify(closed)).isEqualTo(ShapeKind.SPIKE);
    }

    @Test
    void classifiesAShortMonotonicRampAsProgressiveRamp() {
        List<Stage> stages = List.of(
                Stage.ofRate(25, Duration.ofMinutes(5)),
                Stage.ofRate(50, Duration.ofMinutes(5)),
                Stage.ofRate(75, Duration.ofMinutes(5)));
        LoadShape shape = new RampingArrivalRateShape(RequestsPerSecond.of(25), stages);
        assertThat(ShapeKindClassifier.classify(shape)).isEqualTo(ShapeKind.PROGRESSIVE_RAMP);
    }

    @Test
    void classifiesALongerMonotonicRampAsStaged() {
        List<Stage> stages = List.of(
                Stage.ofRate(20, Duration.ofMinutes(5)),
                Stage.ofRate(40, Duration.ofMinutes(5)),
                Stage.ofRate(60, Duration.ofMinutes(5)),
                Stage.ofRate(80, Duration.ofMinutes(5)),
                Stage.ofRate(100, Duration.ofMinutes(5)));
        LoadShape shape = new RampingArrivalRateShape(RequestsPerSecond.of(20), stages);
        assertThat(ShapeKindClassifier.classify(shape)).isEqualTo(ShapeKind.STAGED);
    }

    @Test
    void classifiesANonMonotonicNonSpikeRampAsStaged() {
        List<Stage> stages = List.of(
                Stage.ofRate(50, Duration.ofMinutes(5)),
                Stage.ofRate(20, Duration.ofMinutes(5)),
                Stage.ofRate(80, Duration.ofMinutes(5)));
        LoadShape shape = new RampingArrivalRateShape(RequestsPerSecond.of(50), stages);
        assertThat(ShapeKindClassifier.classify(shape)).isEqualTo(ShapeKind.STAGED);
    }
}
