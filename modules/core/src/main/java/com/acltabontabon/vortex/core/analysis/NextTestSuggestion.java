package com.acltabontabon.vortex.core.analysis;

import java.util.List;

/**
 * The single highest-information experiment worth running next.
 *
 * <p>Distinct from {@link Recommendation}: a recommendation is advice about the system under test,
 * while this is a proposed action on the test <em>process</em> itself — repeat a stage with more
 * telemetry, isolate one operation, hold below a breakpoint for longer. Vortex already knows what
 * happened in this run; the useful question left is what to measure next, and {@code
 * wouldDistinguish} keeps that question answerable rather than open-ended by naming the specific
 * uncertainty the experiment would resolve.
 *
 * @param action           the experiment to run
 * @param rationale        why it is worth running, grounded in what this run did and did not show
 * @param wouldDistinguish the uncertainty this experiment would resolve — what it would tell apart
 *                         that this run alone could not
 * @param evidenceIds      identifiers of the measurements motivating this experiment
 */
public record NextTestSuggestion(
        String action, String rationale, String wouldDistinguish, List<String> evidenceIds) {

    /** Mirrors {@link Finding#MAX_STATEMENT_LENGTH} — a misbehaving model returning far more than a
     *  short action, rationale or distinguishing statement is truncated, not rejected. */
    public static final int MAX_ACTION_LENGTH = 300;
    public static final int MAX_RATIONALE_LENGTH = 500;
    public static final int MAX_WOULD_DISTINGUISH_LENGTH = 300;

    public NextTestSuggestion {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("a next test must state an action");
        }
        action = action.trim();
        if (action.length() > MAX_ACTION_LENGTH) {
            action = action.substring(0, MAX_ACTION_LENGTH);
        }
        rationale = rationale == null ? "" : rationale.trim();
        if (rationale.length() > MAX_RATIONALE_LENGTH) {
            rationale = rationale.substring(0, MAX_RATIONALE_LENGTH);
        }
        wouldDistinguish = wouldDistinguish == null ? "" : wouldDistinguish.trim();
        if (wouldDistinguish.length() > MAX_WOULD_DISTINGUISH_LENGTH) {
            wouldDistinguish = wouldDistinguish.substring(0, MAX_WOULD_DISTINGUISH_LENGTH);
        }
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }

    public boolean isSupported() {
        return !evidenceIds.isEmpty();
    }
}
