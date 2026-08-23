package com.acltabontabon.vortex.core.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** The interval a measurement covers. Stored in UTC. */
public record TimeWindow(Instant start, Instant end) {

    public TimeWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("time window end must not precede its start");
        }
    }

    public static TimeWindow of(Instant start, Instant end) {
        return new TimeWindow(start, end);
    }

    public Duration duration() {
        return Duration.between(start, end);
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(start) && !instant.isAfter(end);
    }
}
