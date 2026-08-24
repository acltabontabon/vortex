package com.acltabontabon.vortex.core.workload;

import com.acltabontabon.vortex.core.shared.Concurrency;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import java.time.Duration;
import java.util.List;

/**
 * Builds the baseline → jump → hold → recovery stage pattern a spike test needs — already legal
 * under {@link RampingArrivalRateShape}/{@link RampingConcurrencyShape} (neither constrains stage
 * monotonicity), so this is the one place that pattern is assembled rather than a fifth
 * {@link LoadShape} variant.
 *
 * <p>The jump and the recovery are each one {@link Stage} whose duration is how long the transition
 * takes — the shortest a k6 ramping executor can still treat as a real ramp rather than a
 * zero-length fault, so it reads as "sudden" next to a multi-minute hold without being
 * instantaneous.
 */
public final class SpikeShapes {

    public static final Duration TRANSITION = Duration.ofSeconds(15);

    private SpikeShapes() {
    }

    public static RampingArrivalRateShape arrivalRate(double baseline, double peak,
            Duration holdBefore, Duration holdAtPeak) {
        RequestsPerSecond base = RequestsPerSecond.of(baseline);
        RequestsPerSecond top = RequestsPerSecond.of(peak);
        List<Stage> stages = List.of(
                new Stage(base, holdBefore),
                new Stage(top, TRANSITION),
                new Stage(top, holdAtPeak),
                new Stage(base, TRANSITION));
        return new RampingArrivalRateShape(base, stages);
    }

    public static RampingConcurrencyShape concurrency(int baseline, int peak,
            Duration holdBefore, Duration holdAtPeak) {
        Concurrency base = Concurrency.of(baseline);
        Concurrency top = Concurrency.of(peak);
        List<Stage> stages = List.of(
                new Stage(base, holdBefore),
                new Stage(top, TRANSITION),
                new Stage(top, holdAtPeak),
                new Stage(base, TRANSITION));
        return new RampingConcurrencyShape(base, stages);
    }
}
