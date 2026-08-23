package dev.vortex.core.evidence;

import dev.vortex.core.metrics.TelemetryCompleteness;
import dev.vortex.core.resource.ResourceKind;
import dev.vortex.core.resource.ResourceScope;
import java.time.Instant;
import java.util.List;

/**
 * CPU, memory and the rest of a run's resource behaviour over time — grouped by what was measured,
 * not by how many signals happened to report it.
 *
 * <p>Answers "what constrained this experiment", not "what metrics were collected": one
 * {@link ResourceKindPlot} per {@link ResourceKind} actually observed, with every scope that reported
 * it (system under test, load generator, a dependency) as its own line on the same chart when their
 * units agree — that overlay is what lets a reader see "was the load generator saturated rather than
 * the target service" at a glance instead of across two separate figures.
 *
 * <p>{@code completeness} exists because artifact presence is not the same question as artifact
 * completeness: a writer that failed twenty minutes into an hour-long run still leaves a file that
 * opens cleanly. A reader must be able to tell a fully-recorded run from one whose telemetry stopped
 * early, which is exactly what a bare {@code present: boolean} could not do.
 */
public record ResourceTimelineEvidence(
        boolean present,
        TelemetryCompleteness completeness,
        List<ResourceKindPlot> plots) {

    public ResourceTimelineEvidence {
        plots = plots == null ? List.of() : List.copyOf(plots);
    }

    public static ResourceTimelineEvidence unavailable() {
        return new ResourceTimelineEvidence(false, TelemetryCompleteness.unavailable(), List.of());
    }

    /** One resource kind's chart — CPU, memory, pools, and so on — with every scope that reported it
     *  as a separate series on the same axis. */
    public record ResourceKindPlot(ResourceKind kind, String label, List<ResourceSeriesEvidence> series) {
        public ResourceKindPlot {
            series = series == null ? List.of() : List.copyOf(series);
        }
    }

    /**
     * One line on a resource chart — one signal, from one provider, describing one scope.
     *
     * @param display            the peak reading, formatted for a caption
     * @param limitDisplay       the published limit, formatted; empty when none was published
     * @param utilisationDisplay the peak as a fraction of the limit, formatted; empty when no limit
     *                           applies
     */
    public record ResourceSeriesEvidence(
            String signalId,
            String providerId,
            ResourceScope scope,
            String scopeLabel,
            String seriesLabel,
            String unitSymbol,
            List<ResourceTimelinePoint> points,
            String display,
            String limitDisplay,
            String utilisationDisplay,
            boolean atItsLimit) {
        public ResourceSeriesEvidence {
            points = points == null ? List.of() : List.copyOf(points);
        }
    }

    public record ResourceTimelinePoint(Instant at, double value) {
    }
}
