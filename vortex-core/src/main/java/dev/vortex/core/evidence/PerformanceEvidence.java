package dev.vortex.core.evidence;

import dev.vortex.core.analysis.SloBreakpoint;
import dev.vortex.core.analysis.SystemSaturation;
import dev.vortex.core.capacity.Headroom;
import dev.vortex.core.metrics.LatencyPercentiles;
import dev.vortex.core.metrics.MeasuredResults;
import dev.vortex.core.shared.ErrorRate;
import java.util.Objects;
import java.util.Optional;

/**
 * The headline measurements, together with what the run established about the service's limits.
 *
 * <p>A view over {@link MeasuredResults} rather than a copy of it. Copying the percentiles into a
 * parallel structure would create a second place for them to be wrong, so the normalised
 * measurements are carried whole and the derived conclusions sit beside them.
 *
 * @param headroom       how far tested capacity sits above observed production traffic; null when
 *                       it could not be computed
 * @param headroomRefusal why it could not be, when it could not. Never both this and a value, and
 *                       never neither: a report that goes quiet where a number was expected leaves
 *                       the reader assuming the tool forgot rather than declined
 * @param production     the observed traffic the headroom was measured against, so a report citing
 *                       the multiple can also say how much the baseline behind it is worth
 */
public record PerformanceEvidence(
        MeasuredResults results,
        SloBreakpoint sloBreakpoint,
        SystemSaturation systemSaturation,
        Headroom headroom,
        String headroomRefusal,
        dev.vortex.core.capacity.ProductionObservation production) {

    public PerformanceEvidence {
        Objects.requireNonNull(results, "results");
        headroomRefusal = headroomRefusal == null ? "" : headroomRefusal.trim();
    }

    /** Performance evidence with no project context, for callers that have none to give. */
    public PerformanceEvidence(MeasuredResults results, SloBreakpoint sloBreakpoint,
            SystemSaturation systemSaturation, Headroom headroom) {
        this(results, sloBreakpoint, systemSaturation, headroom, "", null);
    }

    /** Why headroom is absent, when it is. */
    public Optional<String> headroomRefusalIfPresent() {
        return headroomRefusal.isBlank() ? Optional.empty() : Optional.of(headroomRefusal);
    }

    public Optional<dev.vortex.core.capacity.ProductionObservation> productionIfPresent() {
        return Optional.ofNullable(production);
    }

    /**
     * The facts that say how far to trust the production baseline behind the headroom figure.
     *
     * <p>Empty when there is no baseline. Facts rather than a grade: a reader deciding whether to
     * act on "1.8× headroom" needs to know the window, the resolution and how much of production the
     * mix described, and a HIGH badge would discard all three.
     */
    public java.util.List<String> baselineQuality() {
        return productionIfPresent()
                .map(dev.vortex.core.capacity.ProductionObservation::qualityFacts)
                .orElseGet(java.util.List::of);
    }

    public LatencyPercentiles latency() {
        return results.latency();
    }

    public long requests() {
        return results.requests();
    }

    public long failures() {
        return results.failures();
    }

    public ErrorRate errorRate() {
        return results.errorRate();
    }

    public Optional<SloBreakpoint> sloBreakpointIfPresent() {
        return Optional.ofNullable(sloBreakpoint);
    }

    public Optional<SystemSaturation> systemSaturationIfPresent() {
        return Optional.ofNullable(systemSaturation);
    }

    public Optional<Headroom> headroomIfPresent() {
        return Optional.ofNullable(headroom);
    }

    /** Whether the run established anything about where the service stops coping. */
    public boolean establishedALimit() {
        return sloBreakpoint != null || (systemSaturation != null && systemSaturation.wasObserved());
    }
}
