package dev.vortex.core.analysis;

import dev.vortex.core.metrics.MeasuredResults;
import dev.vortex.core.threshold.ThresholdEvaluation;
import dev.vortex.core.threshold.Verdict;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything Vortex can say about a run without consulting a language model.
 *
 * <p>This is the product's centre of gravity. It is produced for every completed execution, before
 * and independently of any AI involvement, and it must be good enough on its own that a team with
 * no local model installed still gets a genuinely useful result: the question the test asked, the
 * answer, the offered and achieved throughput, per-operation figures, latency percentiles, error
 * rate, threshold-by-threshold verdicts, and where the objectives were first violated.
 *
 * <p>AI interpretation is layered on top of this — never in place of it.
 *
 * @param question         what this run set out to learn
 * @param verdict          the deterministic outcome
 * @param answer           a one-line answer to the question, in plain language
 * @param results          the normalised measurements
 * @param thresholds       objective-by-objective evaluation
 * @param sloBreakpoint    where objectives were first violated, when the workload reached that point
 * @param systemSaturation whether the system stopped coping, reported conservatively
 * @param notes            caveats that must accompany the result, such as the environment class
 */
public record DeterministicSummary(
        String question,
        Verdict verdict,
        String answer,
        MeasuredResults results,
        ThresholdEvaluation thresholds,
        SloBreakpoint sloBreakpoint,
        SystemSaturation systemSaturation,
        List<String> notes,
        LimitFindings limits) {

    public DeterministicSummary {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(results, "results");
        Objects.requireNonNull(thresholds, "thresholds");
        question = question == null ? "" : question;
        answer = answer == null ? "" : answer;
        notes = notes == null ? List.of() : List.copyOf(notes);
        limits = limits == null ? LimitFindings.notEvaluated() : limits;
    }

    /**
     * A summary from before the four limits were computed separately.
     *
     * <p>Retained at the previous arity so widening did not mean editing every construction site,
     * and it reports the limits as <em>not evaluated</em> rather than as none found — an older run
     * did not establish that its service had no throughput ceiling; nobody looked for one.
     */
    public DeterministicSummary(String question, Verdict verdict, String answer,
            MeasuredResults results, ThresholdEvaluation thresholds, SloBreakpoint sloBreakpoint,
            SystemSaturation systemSaturation, List<String> notes) {
        this(question, verdict, answer, results, thresholds, sloBreakpoint, systemSaturation, notes,
                LimitFindings.notEvaluated());
    }

    public Optional<SloBreakpoint> sloBreakpointIfPresent() {
        return Optional.ofNullable(sloBreakpoint);
    }

    public Optional<SystemSaturation> systemSaturationIfPresent() {
        return Optional.ofNullable(systemSaturation);
    }

    public boolean passed() {
        return verdict == Verdict.PASS;
    }

    /**
     * The evidence available to interpretation, as stable references.
     *
     * <p>An AI finding may only cite identifiers that appear here. Anything else is a claim about a
     * measurement that was never taken.
     *
     * <p>Delegates to {@link EvidenceIds} so that the set shown to the assistant and the set the
     * validator accepts are, unavoidably, the same set. They were briefly defined separately, and
     * drifted apart immediately.
     */
    public List<String> availableEvidenceIds() {
        return EvidenceIds.availableFor(results, thresholds);
    }
}
