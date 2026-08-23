package com.acltabontabon.vortex.core.target;

import com.acltabontabon.vortex.core.environment.TargetUrl;
import java.util.Objects;
import java.util.Optional;

/**
 * A runtime fact produced by preparing a target. Never part of {@link ExecutionTarget} or {@code
 * EffectiveTestPlan} — it lives on {@code TestExecution}, the run aggregate, because it is
 * genuinely new information discovered while a specific run executed, not part of the plan's
 * description of intent. {@code telemetryHandle} is opaque (a container id today); it is
 * historical/diagnostic once a run is over, never a live-actionable reference — nothing should
 * imply a past run's handle can still be used to inspect or control anything.
 */
public record ResolvedTarget(TargetUrl endpoint, TargetOwnership ownership, String telemetryHandle,
        EffectiveResourceEnvelope resources) {

    public ResolvedTarget {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(ownership, "ownership");
        telemetryHandle = telemetryHandle == null ? "" : telemetryHandle;
    }

    public Optional<String> telemetryHandleIfPresent() {
        return telemetryHandle.isBlank() ? Optional.empty() : Optional.of(telemetryHandle);
    }

    public Optional<EffectiveResourceEnvelope> resourcesIfPresent() {
        return Optional.ofNullable(resources);
    }

    public static ResolvedTarget external(TargetUrl endpoint) {
        return new ResolvedTarget(endpoint, TargetOwnership.EXTERNAL, "", null);
    }
}
