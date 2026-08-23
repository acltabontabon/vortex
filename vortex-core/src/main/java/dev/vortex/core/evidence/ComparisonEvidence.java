package dev.vortex.core.evidence;

import dev.vortex.core.comparison.ExecutionComparison;
import dev.vortex.core.comparison.MetricDelta;
import dev.vortex.core.comparison.RegressionVerdict;
import dev.vortex.core.shared.ExecutionId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * This run set against an earlier one that tested the same thing.
 *
 * <p>Comparison is permissive and regression evaluation is strict, and that separation is preserved
 * here: {@code comparison} always has something to show, while {@code verdict} is absent whenever
 * the two runs are not equivalent. A report may therefore show the numbers side by side and still
 * decline to call anything a regression, which is the only honest thing to do when the experiments
 * differed.
 *
 * @param baselineLabel how the earlier run identifies itself, e.g. its service version
 * @param verdict       null when the runs are not comparable
 */
public record ComparisonEvidence(
        ExecutionId baselineId,
        String baselineLabel,
        Instant baselineFinishedAt,
        ExecutionComparison comparison,
        RegressionVerdict verdict) {

    public ComparisonEvidence {
        Objects.requireNonNull(baselineId, "baselineId");
        Objects.requireNonNull(comparison, "comparison");
        baselineLabel = baselineLabel == null ? "" : baselineLabel;
    }

    public List<MetricDelta> deltas() {
        return comparison.deltas();
    }

    public boolean supportsVerdict() {
        return verdict != null && verdict != RegressionVerdict.NOT_COMPARABLE;
    }

    public Optional<RegressionVerdict> verdictIfPresent() {
        return Optional.ofNullable(verdict);
    }

    /** Why no regression verdict is offered, when none is. Empty when one is. */
    public String notComparableExplanation() {
        return supportsVerdict() ? "" : comparison.notComparableExplanation();
    }

    /** What differed between the two runs, when they were not equivalent. */
    public List<String> differences() {
        return comparison.differences();
    }
}
