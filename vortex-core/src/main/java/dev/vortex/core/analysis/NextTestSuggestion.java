package dev.vortex.core.analysis;

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

    public NextTestSuggestion {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("a next test must state an action");
        }
        action = action.trim();
        rationale = rationale == null ? "" : rationale.trim();
        wouldDistinguish = wouldDistinguish == null ? "" : wouldDistinguish.trim();
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
    }

    public boolean isSupported() {
        return !evidenceIds.isEmpty();
    }
}
