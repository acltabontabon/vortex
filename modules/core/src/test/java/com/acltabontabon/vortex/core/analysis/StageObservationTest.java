package com.acltabontabon.vortex.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.acltabontabon.vortex.core.shared.Concurrency;
import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.workload.StageWindowBasis;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Whether achieved load fell short of what a stage actually asked for.
 *
 * <p>A stage that followed a different level ramps linearly toward its own over its own duration —
 * that is k6's own {@code ramping-arrival-rate} behavior, and Vortex told it to do exactly that. The
 * comparison basis has to reflect the ramp it was actually judged against, not the level it was only
 * just arriving at by the stage's end; otherwise every ordinary ramp looks like a shortfall.
 */
class StageObservationTest {

    private static StageObservation stage(double target, double achieved, Double rampStart) {
        RequestsPerSecond rampStartLevel = rampStart == null ? null : RequestsPerSecond.of(rampStart);
        return new StageObservation(RequestsPerSecond.of(target), RequestsPerSecond.of(achieved),
                Duration.ofMillis(60), ErrorRate.ZERO, 12, List.of(), List.of(),
                StageWindowBasis.OBSERVED, List.of(), 5_000, rampStartLevel);
    }

    @Test
    @DisplayName("with no ramp start recorded, the comparison is against the flat target, as before")
    void noRampStartUsesTheFlatTarget() {
        // 75 achieved against a 100 target, with no ramp context, is a genuine 25% shortfall.
        assertThat(stage(100, 75, null).rateShortfall()).hasValueSatisfying(shortfall ->
                assertThat(shortfall).isCloseTo(0.25, within(0.001)));
    }

    @Test
    @DisplayName("a true plateau — the previous stage held the same level — reduces to the same formula")
    void aPlateauReducesToTheFlatComparison() {
        assertThat(stage(100, 75, 100.0).rateShortfall()).hasValueSatisfying(shortfall ->
                assertThat(shortfall).isCloseTo(0.25, within(0.001)));
    }

    @Test
    @DisplayName("a stage that tracked its ramp from a lower level reports no material shortfall")
    void aTrackedRampHasNoMaterialShortfall() {
        // Ramping 50 -> 100: a linear ramp's mean is (50+100)/2 = 75, and 75 was achieved — the stage
        // tracked its ramp essentially perfectly, even though 75 is 25% below the nominal 100 target.
        assertThat(stage(100, 75, 50.0).rateShortfall()).hasValueSatisfying(shortfall ->
                assertThat(shortfall).isCloseTo(0.0, within(0.001)));
    }

    @Test
    @DisplayName("a stage that genuinely fell behind its ramp still reports a shortfall")
    void aStageBehindItsOwnRampStillReportsAShortfall() {
        // Ramping 50 -> 100 expects an average of 75; achieving only 60 is a real shortfall, ramp or
        // not.
        assertThat(stage(100, 60, 50.0).rateShortfall()).hasValueSatisfying(shortfall ->
                assertThat(shortfall).isCloseTo(0.20, within(0.001)));
    }

    @Test
    @DisplayName("a closed workload has no rate to fall short of, ramp start or not")
    void aClosedWorkloadHasNoShortfall() {
        StageObservation closed = new StageObservation(Concurrency.of(100),
                RequestsPerSecond.of(75), Duration.ofMillis(60), ErrorRate.ZERO, 12, List.of(),
                List.of(), StageWindowBasis.OBSERVED, List.of(), 5_000, Concurrency.of(50));

        assertThat(closed.rateShortfall()).isEmpty();
    }

    @Test
    @DisplayName("the ramp-start level is exposed for callers that want to describe it")
    void rampStartLevelIsExposed() {
        assertThat(stage(100, 75, 50.0).rampStartLevelIfPresent())
                .hasValue(RequestsPerSecond.of(50));
        assertThat(stage(100, 75, null).rampStartLevelIfPresent()).isEmpty();
    }
}
