package com.acltabontabon.vortex.core.plan;

import java.time.Instant;
import java.util.Objects;

/**
 * A record that a person consciously accepted a safety warning before a test ran.
 *
 * <p>Kept with the plan and written into the execution's artifacts, so that a run against a shared
 * or production-like environment always carries evidence of who agreed to it and what they were
 * told at the time.
 *
 * @param policyId    which policy raised the concern, e.g. {@code target.non-local}
 * @param summary     what the user was warned about
 * @param confirmedAt when the confirmation was given (UTC)
 */
public record SafetyDecision(String policyId, String summary, Instant confirmedAt) {

    public SafetyDecision {
        Objects.requireNonNull(confirmedAt, "confirmedAt");
        if (policyId == null || policyId.isBlank()) {
            throw new IllegalArgumentException("safety decision must name the policy it acknowledges");
        }
        summary = summary == null ? "" : summary;
    }
}
