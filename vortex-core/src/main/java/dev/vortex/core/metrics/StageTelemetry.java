package dev.vortex.core.metrics;

import dev.vortex.core.resource.ResourceScope;
import dev.vortex.core.resource.ResourceSignal;
import dev.vortex.core.workload.StageWindowBasis;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the service said about itself while the workload held one level.
 *
 * <p>Until this existed, a run produced one window and one peak per metric — enough to say "the
 * connection pool reached 98%" and not enough to say when. "What changed as load increased" is the
 * question a breakpoint investigation actually asks, and it needs the service's own view cut the same
 * way the load generator's already is.
 *
 * <p>Three points per stage rather than a series, for the same reason the run-wide observation keeps
 * three: storing and querying telemetry over time is what the team's observability platform is for,
 * and Vortex is not one.
 *
 * @param stageIndex which stage, matching the workload's stage order from zero
 * @param window     the interval this covers
 * @param basis      how that interval was established — measured, or computed from the plan
 * @param signals    what was observed during it; empty when nothing was
 * @param resourceSignals the subset of {@code signals} a provider was able to classify as a typed
 *                   resource. An index over the list above rather than a replacement for it: every
 *                   entry's observation also appears in {@code signals}, so everything that renders,
 *                   cites and exports measurements keeps working, while only classified signals can
 *                   reach a conclusion about a limit
 */
public record StageTelemetry(int stageIndex, TimeWindow window, StageWindowBasis basis,
        List<MetricObservation> signals, List<ResourceSignal> resourceSignals) {

    public StageTelemetry {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(basis, "basis");
        signals = signals == null ? List.of() : List.copyOf(signals);
        resourceSignals = resourceSignals == null ? List.of() : List.copyOf(resourceSignals);
        if (stageIndex < 0) {
            throw new IllegalArgumentException("stage index must not be negative");
        }
    }

    /** A stage whose provider classified none of what it reported. */
    public StageTelemetry(int stageIndex, TimeWindow window, StageWindowBasis basis,
            List<MetricObservation> signals) {
        this(stageIndex, window, basis, signals, List.of());
    }

    public boolean isEmpty() {
        return signals.isEmpty();
    }

    public Optional<MetricObservation> signal(String id) {
        return signals.stream().filter(signal -> signal.id().equals(id)).findFirst();
    }

    /** The typed signals describing one system, in the order the provider reported them. */
    public List<ResourceSignal> resourcesScopedTo(ResourceScope scope) {
        return resourceSignals.stream().filter(signal -> signal.scope() == scope).toList();
    }

    /**
     * Whether a finding resting on this alignment may claim more than correlation in time.
     *
     * <p>Delegates rather than exposing the enum, so no caller has to remember which basis is the
     * permissive one.
     */
    public boolean supportsStrongerEvidence() {
        return basis.canStrengthenAFinding();
    }
}
