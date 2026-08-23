package com.acltabontabon.vortex.core.evidence;

import com.acltabontabon.vortex.core.threshold.ThresholdEvaluation;
import com.acltabontabon.vortex.core.threshold.ThresholdResult;
import com.acltabontabon.vortex.core.threshold.Verdict;
import java.util.List;
import java.util.Objects;

/**
 * The objectives this run was judged against, one row each.
 *
 * <p>Also carries the reason there is no verdict, when there is none. A run configured with no
 * objectives cannot pass and cannot fail, and the honest rendering of that is a sentence explaining
 * it — not an empty table, and certainly not a green tick.
 *
 * @param absenceExplanation why no verdict was reached; empty when objectives were evaluated
 */
public record AcceptanceEvidence(
        ThresholdEvaluation evaluation,
        String absenceExplanation) {

    public AcceptanceEvidence {
        Objects.requireNonNull(evaluation, "evaluation");
        absenceExplanation = absenceExplanation == null ? "" : absenceExplanation;
    }

    public static AcceptanceEvidence of(ThresholdEvaluation evaluation) {
        if (evaluation == null || evaluation.results().isEmpty()) {
            return new AcceptanceEvidence(ThresholdEvaluation.empty(),
                    "This run had no objectives configured, so it can neither pass nor fail. "
                            + "The measurements below stand on their own.");
        }
        return new AcceptanceEvidence(evaluation, "");
    }

    public Verdict overall() {
        return evaluation.overall();
    }

    public List<ThresholdResult> results() {
        return evaluation.results();
    }

    public List<ThresholdResult> failures() {
        return evaluation.failures();
    }

    /**
     * Objectives whose measurement was never collected.
     *
     * <p>Kept separate from failures because they are a different problem with a different remedy,
     * and because an objective that was never checked has not been met.
     */
    public List<ThresholdResult> unevaluated() {
        return evaluation.unevaluated();
    }

    public boolean hasObjectives() {
        return !evaluation.results().isEmpty();
    }
}
