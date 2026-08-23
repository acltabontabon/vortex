package com.acltabontabon.vortex.core.analysis;

import java.util.List;
import java.util.Objects;

/**
 * A suggested next action.
 *
 * <p>Recommendations are proposals for a person to accept or reject. Vortex never acts on one by
 * itself — in particular, a recommendation to run another test does not run another test.
 *
 * <p>Like a {@link Finding}, a recommendation must cite the evidence that makes it reasonable.
 * This is the mechanism that rejects generic advice — "optimise the application", "scale
 * horizontally" — rather than trying to recognise generic phrasing: a recommendation with no
 * resolvable evidence is discarded by {@link
 * com.acltabontabon.vortex.core.application.EvidenceReferenceValidator}, the same way an unsupported finding is.
 *
 * @param action    what to do
 * @param rationale why it is worth doing, grounded in what this run did and did not show
 * @param evidenceIds identifiers of the measurements that make this action specifically reasonable
 */
public record Recommendation(String action, String rationale, List<String> evidenceIds) {

    public Recommendation {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("a recommendation must state an action");
        }
        action = action.trim();
        rationale = rationale == null ? "" : rationale.trim();
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }

    /** Convenience constructor for callers that have not attached evidence. */
    public Recommendation(String action, String rationale) {
        this(action, rationale, List.of());
    }

    public boolean isSupported() {
        return !evidenceIds.isEmpty();
    }
}
