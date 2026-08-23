package dev.vortex.core.workload;

import java.time.Duration;

/** Duration bounds shared by the workload shapes. */
final class LoadShapeDurations {

    /**
     * A single run is capped at a day. Longer soaks are real, but a process that must survive
     * uninterrupted for more than 24 hours is an orchestration problem rather than a workload
     * setting, and pretending otherwise produces runs that die halfway with nothing to show.
     */
    static final Duration MAXIMUM = Duration.ofHours(24);

    private LoadShapeDurations() {
    }

    static Duration require(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("workload duration must be positive but was " + duration);
        }
        if (duration.compareTo(MAXIMUM) > 0) {
            throw new IllegalArgumentException(
                    "workload duration must be at most 24 hours but was " + duration);
        }
        return duration;
    }
}
