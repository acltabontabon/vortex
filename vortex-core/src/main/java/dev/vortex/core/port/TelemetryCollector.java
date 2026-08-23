package dev.vortex.core.port;

import dev.vortex.core.metrics.MetricObservation;
import dev.vortex.core.metrics.StageTelemetry;
import dev.vortex.core.metrics.TelemetryGap;
import dev.vortex.core.metrics.TimeWindow;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.resource.ResourceSignal;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.target.ResolvedTarget;
import java.util.List;

/**
 * Samples the service under test while a run is in progress.
 *
 * <h2>Why this has a lifecycle rather than a single read</h2>
 * Most of the measurements that explain a bottleneck are instantaneous gauges: connection-pool
 * utilisation, active workers, queue depth. Reading them once, after the load generator has
 * stopped, reports a service sitting idle — a connection pool that was saturated for four minutes
 * shows 0% utilisation, which is not merely uninformative but actively misleading in a report
 * about saturation.
 *
 * <p>So a collector is started before traffic begins and finished once it stops, sampling
 * throughout and keeping the peak. That peak is what caused the queueing; an average over a run
 * that includes ramp-up and drain would smooth away the moment of interest.
 *
 * <h2>Why it also keeps the peak per stage</h2>
 * One peak for the whole run answers "what got hot"; it cannot answer "when". A breakpoint
 * investigation asks the second question — what changed as load increased — and answering it needs
 * the service's own view cut the same way the load generator's already is.
 *
 * <h2>Failure is ordinary</h2>
 * Most services Vortex points at expose nothing it can read. That is a normal outcome, not an
 * error: the run still produces valid client-side measurements, and the absent server-side view is
 * reported honestly as missing telemetry — with a stated cause, since "unavailable" and "your token
 * was refused" call for very different afternoons.
 */
public interface TelemetryCollector {

    /**
     * Begins sampling for a run.
     *
     * <p>Must not throw. A collector that cannot reach the service returns a session that yields
     * nothing but may still yield the reason it yielded nothing.
     *
     * @param executionId    which execution this session belongs to, for a collector that persists
     *                       what it samples as that execution's own artifact
     * @param resolvedTarget this run's resolved runtime target — the actual reachable address, the
     *                       ownership that held at prepare time, and (for a Vortex-managed target)
     *                       the opaque telemetry handle and confirmed resource envelope a
     *                       container-id-aware provider needs, since a container id is not something
     *                       an endpoint-keyed {@link ObservabilityQuery} can carry
     */
    Session start(EffectiveTestPlan plan, ExecutionId executionId, ResolvedTarget resolvedTarget);

    /** An in-progress sampling session. */
    interface Session {

        /**
         * Stops sampling and returns what was observed.
         *
         * @param window the interval the run covered, attached to every observation as provenance
         */
        Telemetry finish(TimeWindow window);

        /** A session that observed nothing, and had nothing to say about why. */
        static Session empty() {
            return window -> Telemetry.none();
        }

        /** A session that observed nothing, and knows why. */
        static Session refused(List<TelemetryGap> gaps) {
            return window -> new Telemetry(List.of(), List.of(), gaps);
        }
    }

    /**
     * Everything a session gathered: the run-wide view, the per-stage view, and the gaps.
     *
     * <p>The run-wide observations are not derivable from the stage ones. A run's peak is the
     * highest value at any moment, which may fall in the drain after the last stage ended, and
     * recomputing it from stage maxima would quietly drop that.
     *
     * @param run     one observation per metric, covering the whole run, carrying its peak and trace
     * @param byStage the same metrics cut by workload stage; empty when stages could not be aligned
     * @param gaps    what was asked for and not obtained, and why
     */
    record Telemetry(List<MetricObservation> run, List<StageTelemetry> byStage,
            List<TelemetryGap> gaps, List<ResourceSignal> resourceSignals) {

        public Telemetry {
            run = run == null ? List.of() : List.copyOf(run);
            byStage = byStage == null ? List.of() : List.copyOf(byStage);
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
            resourceSignals = resourceSignals == null ? List.of() : List.copyOf(resourceSignals);
        }

        /** Telemetry from providers that classified none of what they reported. */
        public Telemetry(List<MetricObservation> run, List<StageTelemetry> byStage,
                List<TelemetryGap> gaps) {
            this(run, byStage, gaps, List.of());
        }

        public static Telemetry none() {
            return new Telemetry(List.of(), List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return run.isEmpty() && byStage.isEmpty();
        }
    }

    /** A collector that gathers nothing, for tests and for runs with no observability configured. */
    static TelemetryCollector none() {
        return (plan, executionId, resolvedTarget) -> Session.empty();
    }
}
