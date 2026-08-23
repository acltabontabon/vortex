package com.acltabontabon.vortex.core.metrics;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The time series of aggregation buckets for one execution.
 *
 * <p>This is the evidence that makes breakpoint detection possible: an aggregate p95 for a whole
 * stress test says almost nothing, whereas the shape of p95 against offered load says where the
 * service started to struggle.
 */
public record MetricSeries(Duration bucketWidth, List<SamplePoint> points) {

    public MetricSeries {
        bucketWidth = bucketWidth == null ? Duration.ofSeconds(5) : bucketWidth;
        points = points == null ? List.of() : List.copyOf(points);
    }

    public static MetricSeries empty() {
        return new MetricSeries(Duration.ofSeconds(5), List.of());
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    public int size() {
        return points.size();
    }

    public Optional<SamplePoint> last() {
        return points.isEmpty() ? Optional.empty() : Optional.of(points.getLast());
    }

    public Duration span() {
        if (points.isEmpty()) {
            return Duration.ZERO;
        }
        return Duration.between(points.getFirst().at(), points.getLast().at())
                .plus(points.getLast().duration());
    }
}
