package dev.vortex.core.capacity;

import dev.vortex.core.metrics.MetricObservation;
import dev.vortex.core.shared.ErrorRate;
import dev.vortex.core.shared.LoadLevel;
import dev.vortex.core.threshold.Durations;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One side of a tested capacity boundary: a level, and what the service looked like there.
 *
 * <p>The compliant edge on its own is half the evidence. "The service sustained 400 requests/sec"
 * invites the obvious follow-up — <em>and what happened at 450?</em> — and a report that cannot
 * answer it has recorded the more quotable half and discarded the more useful one. Knowing that
 * latency doubled at the next level up is a different situation from knowing that errors went to
 * 40%, and they call for different work.
 *
 * @param level              the load the workload was holding
 * @param p95                request latency there
 * @param errorRate          share of failed requests there
 * @param violatedThresholds objectives that were not met there; empty for a compliant edge
 * @param signals            what the service said about itself at that level
 */
public record BoundaryEdge(LoadLevel level, Duration p95, ErrorRate errorRate,
        List<String> violatedThresholds, List<MetricObservation> signals) {

    public BoundaryEdge {
        Objects.requireNonNull(level, "level");
        errorRate = errorRate == null ? ErrorRate.ZERO : errorRate;
        violatedThresholds = violatedThresholds == null ? List.of() : List.copyOf(violatedThresholds);
        signals = signals == null ? List.of() : List.copyOf(signals);
    }

    public Optional<Duration> p95IfPresent() {
        return Optional.ofNullable(p95);
    }

    public boolean isCompliant() {
        return violatedThresholds.isEmpty();
    }

    /** The level with its unit, e.g. {@code 450 requests/sec}. Never a bare number. */
    public String display() {
        return level.displayWithUnit();
    }

    /** What was measured here, in one line. */
    public String describe() {
        StringBuilder text = new StringBuilder(display());
        p95IfPresent().ifPresent(latency -> text.append(" · p95 ").append(Durations.display(latency)));
        text.append(" · ").append(errorRate.display()).append(" errors");
        return text.toString();
    }
}
