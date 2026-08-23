package com.acltabontabon.vortex.core.comparison;

import java.util.Objects;

/**
 * A comparison Vortex declined to draw, and why.
 *
 * <p>Recorded rather than silently omitted. This is the shape headroom refusal already has: a reader
 * who expected a resource delta and finds nothing should be told the baseline had incomplete
 * telemetry, not left to wonder whether the resources were identical.
 */
public record RefusedDelta(DeltaKind kind, String reason) {

    public RefusedDelta {
        Objects.requireNonNull(kind, "kind");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "a refused delta must say why; a missing comparison with no reason reads as an "
                            + "absence of difference");
        }
    }
}
