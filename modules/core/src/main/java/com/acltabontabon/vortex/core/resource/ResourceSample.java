package com.acltabontabon.vortex.core.resource;

import com.acltabontabon.vortex.core.metrics.MetricUnit;
import java.time.Instant;
import java.util.Objects;

/**
 * One raw reading of one resource signal, at one instant.
 *
 * <p>The raw tier beneath {@link ResourceSignal}: where that type is the derived start/peak/end
 * summary a report reads, this is what a run-scoped telemetry artifact retains so a later reader can
 * ask "when", not just "how much". Kept deliberately thin — no dimensions map, no limit — because
 * everything else a reader needs is either constant for the whole run (the limit, carried once by
 * {@link ResourceSignal}) or not consumed by anything this phase builds.
 *
 * @param at          when this was sampled
 * @param providerId  which provider produced it. Two different providers can report the same
 *                     {@code signalId} for two different measurements (Prometheus and Dynatrace both
 *                     use bare keys like {@code system.cpu.utilization}), so this is what keeps a raw
 *                     series from interleaving two providers' readings under one identity
 * @param signalId    {@code MetricObservation.id()} / {@code ResourceSignal.signalId()}
 * @param kind        what this measures
 * @param scope       what system this describes
 * @param value       the reading
 * @param unit        the unit {@code value} is expressed in
 * @param stageIndex  which workload stage this fell in; null outside any stage (ramp-up or drain),
 *                    matching {@code StageWindows.levelAt}'s own convention for that case
 */
public record ResourceSample(
        Instant at,
        String providerId,
        String signalId,
        ResourceKind kind,
        ResourceScope scope,
        double value,
        MetricUnit unit,
        Integer stageIndex) {

    public ResourceSample {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(providerId, "providerId");
        Objects.requireNonNull(signalId, "signalId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(unit, "unit");
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("A resource sample's value must be finite but was " + value);
        }
    }
}
