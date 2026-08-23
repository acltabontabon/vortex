package com.acltabontabon.vortex.core.workload;

import com.acltabontabon.vortex.core.shared.LoadLevel;
import java.time.Duration;
import java.util.Objects;

/**
 * One step of a workload: move to {@code target} over {@code duration}.
 *
 * <p>Stages are the unit of evidence for breakpoint detection. A run with four stages gives four
 * observations of "did the service still meet its objectives at this level?", which is why Vortex
 * reports low evidence strength when a run had very few stages.
 *
 * <p>The target is a {@link LoadLevel} rather than a rate, so the same machinery describes an
 * arrival-rate ramp and a virtual-user ramp. Every stage within one workload measures the same
 * quantity; the workload types enforce that.
 */
public record Stage(LoadLevel target, Duration duration) {

    public Stage {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(duration, "duration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("stage duration must be positive but was " + duration);
        }
        if (duration.toHours() > 24) {
            throw new IllegalArgumentException("stage duration must be at most 24 hours but was " + duration);
        }
    }

    public static Stage ofRate(double requestsPerSecond, Duration duration) {
        return new Stage(com.acltabontabon.vortex.core.shared.RequestsPerSecond.of(requestsPerSecond), duration);
    }

    public static Stage ofVus(int vus, Duration duration) {
        return new Stage(com.acltabontabon.vortex.core.shared.Concurrency.of(vus), duration);
    }
}
