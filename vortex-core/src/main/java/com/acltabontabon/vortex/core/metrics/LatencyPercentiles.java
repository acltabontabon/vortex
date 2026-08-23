package com.acltabontabon.vortex.core.metrics;

import com.acltabontabon.vortex.core.shared.Percentile;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Latency distribution for a measurement window. */
public record LatencyPercentiles(
        Map<Percentile, Duration> percentiles,
        Duration minimum,
        Duration mean,
        Duration maximum) {

    public LatencyPercentiles {
        percentiles = percentiles == null ? Map.of() : Map.copyOf(percentiles);
        minimum = Objects.requireNonNullElse(minimum, Duration.ZERO);
        mean = Objects.requireNonNullElse(mean, Duration.ZERO);
        maximum = Objects.requireNonNullElse(maximum, Duration.ZERO);
    }

    public static LatencyPercentiles empty() {
        return new LatencyPercentiles(Map.of(), Duration.ZERO, Duration.ZERO, Duration.ZERO);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<Duration> at(Percentile percentile) {
        return Optional.ofNullable(percentiles.get(percentile));
    }

    public Optional<Duration> p50() {
        return at(Percentile.P50);
    }

    public Optional<Duration> p95() {
        return at(Percentile.P95);
    }

    public Optional<Duration> p99() {
        return at(Percentile.P99);
    }

    public boolean isEmpty() {
        return percentiles.isEmpty();
    }

    /** Percentiles in ascending order, for display. */
    public Map<Percentile, Duration> sorted() {
        return new TreeMap<>(percentiles);
    }

    public static final class Builder {

        private final Map<Percentile, Duration> percentiles = new TreeMap<>();
        private Duration minimum = Duration.ZERO;
        private Duration mean = Duration.ZERO;
        private Duration maximum = Duration.ZERO;

        public Builder at(Percentile percentile, Duration value) {
            percentiles.put(percentile, value);
            return this;
        }

        public Builder atMillis(double percent, double millis) {
            return at(Percentile.of(percent), millisToDuration(millis));
        }

        public Builder minimumMillis(double millis) {
            this.minimum = millisToDuration(millis);
            return this;
        }

        public Builder meanMillis(double millis) {
            this.mean = millisToDuration(millis);
            return this;
        }

        public Builder maximumMillis(double millis) {
            this.maximum = millisToDuration(millis);
            return this;
        }

        public LatencyPercentiles build() {
            return new LatencyPercentiles(percentiles, minimum, mean, maximum);
        }

        private static Duration millisToDuration(double millis) {
            return Duration.ofNanos(Math.round(millis * 1_000_000d));
        }
    }
}
