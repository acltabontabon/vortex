package dev.vortex.core.comparison;

import dev.vortex.core.plan.ExperimentCompatibility;
import dev.vortex.core.shared.ExecutionId;
import java.util.List;
import java.util.Objects;

/**
 * A side-by-side view of two executions.
 *
 * <p>Comparison is permissive on purpose: Vortex will show any two runs together and list what
 * differs between them, because an engineer looking at two dissimilar runs is doing something
 * legitimate. What comparison does <em>not</em> do is declare a regression — that is
 * {@link RegressionEvaluator}'s job, and it is strict.
 *
 * <p>Keeping the two apart means the interface can always be useful without ever being misleading.
 * The distinction it has to make visible is between an <em>observed difference</em> and a
 * <em>supported conclusion</em>: a number moved, and Vortex is willing to say why that number
 * moving means the service got slower.
 *
 * @param baselineId    the earlier execution
 * @param candidateId   the later execution
 * @param compatibility whether the two ran the same experiment, and what differs when they did not
 * @param deltas        metric-by-metric changes
 */
public record ExecutionComparison(
        ExecutionId baselineId,
        ExecutionId candidateId,
        ExperimentCompatibility compatibility,
        List<MetricDelta> deltas,
        List<RefusedDelta> refused,
        BaselineEligibility eligibility) {

    /**
     * A comparison drawn without consulting the baseline's validity.
     *
     * <p>Retained at the previous arity. It refuses nothing, which is correct for a caller that has
     * not asked the question — not a claim that the baseline was sound.
     */
    public ExecutionComparison(ExecutionId baselineId, ExecutionId candidateId,
            ExperimentCompatibility compatibility, List<MetricDelta> deltas) {
        this(baselineId, candidateId, compatibility, deltas, List.of(),
                new BaselineEligibility(true, java.util.Set.of(), ""));
    }

    public ExecutionComparison {
        Objects.requireNonNull(baselineId, "baselineId");
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(compatibility, "compatibility");
        deltas = deltas == null ? List.of() : List.copyOf(deltas);
        refused = refused == null ? List.of() : List.copyOf(refused);
        // Unrestricted by default: a comparison built without a validity view is not one whose
        // baseline was found wanting, and must not read as though it were.
        eligibility = eligibility == null
                ? new BaselineEligibility(true, java.util.Set.of(), "") : eligibility;
    }

    /** Whether anything was declined because of what the baseline could not support. */
    public boolean hasRefusals() {
        return !refused.isEmpty();
    }

    public boolean supportsRegressionVerdict() {
        return compatibility.compatible();
    }

    /** What differs between the two experiments, in plain language. Empty when they are the same. */
    public List<String> differences() {
        return compatibility.differences();
    }

    /**
     * The message shown in place of a verdict when the runs are not comparable.
     *
     * <p>States what Vortex will still do, not only what it refuses to do. An engineer who came
     * here to compare two runs is not helped by a bare refusal.
     */
    public String notComparableExplanation() {
        if (compatibility.compatible()) {
            return "";
        }
        return "The measurements below are shown for inspection. Vortex will not turn them into a "
                + "regression verdict, because these two runs did not test the same experiment.";
    }
}
