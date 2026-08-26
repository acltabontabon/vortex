package com.acltabontabon.vortex.core.application;

import com.acltabontabon.vortex.core.analysis.Analysis;
import com.acltabontabon.vortex.core.analysis.Confidence;
import com.acltabontabon.vortex.core.analysis.DeterministicSummary;
import com.acltabontabon.vortex.core.analysis.EvidenceIds;
import com.acltabontabon.vortex.core.analysis.Finding;
import com.acltabontabon.vortex.core.analysis.FindingType;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.threshold.Verdict;
import com.acltabontabon.vortex.core.workload.WorkloadModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks an already evidence-resolved analysis for internal consistency, rather than for whether
 * its citations exist.
 *
 * <p>{@link EvidenceReferenceValidator} answers "does this citation resolve to a real
 * measurement?" This answers a different question: "given what this claim says it is, and given
 * what Vortex already decided, is it consistent?" A finding can cite a perfectly real measurement
 * and still overreach — by contradicting the verdict outright, by denying an established
 * breakpoint, by claiming HIGH confidence for something typed as a hypothesis, or by making a
 * claim about one operation using only aggregate evidence.
 *
 * <p>The verdict and breakpoint checks are a small, explicit phrase list, not natural-language
 * understanding — the same kind of bounded heuristic the security model already uses elsewhere in
 * this codebase for advisory (not authoritative) signals. It will not catch every way a model
 * could phrase a contradiction, and it is not meant to; it exists to catch the overwhelming common
 * case cheaply, in code, rather than trusting a prompt instruction alone to hold.
 */
public final class EpistemicIntegrityValidator {

    private static final List<String> PASS_CONTRADICTIONS = List.of(
            "failed to meet", "objective was violated", "objectives were violated",
            "objectives were breached", "did not meet its objective", "breached its objective",
            "the service failed");

    private static final List<String> FAIL_CONTRADICTIONS = List.of(
            "met every objective", "met all objectives", "fully compliant throughout",
            "no objectives were violated", "passed every objective");

    private static final List<String> BREAKPOINT_DENIALS = List.of(
            "no clear limit was found", "no breakpoint was reached", "capacity was not tested",
            "no limit was observed", "the service showed no limit");

    /**
     * Phrasing that only makes sense if throughput were an applied, controlled level — true under
     * {@link WorkloadModel#CLOSED} (concurrency), never under {@link WorkloadModel#OPEN}
     * (arrival-rate), where throughput is an outcome of latency, not an input.
     */
    private static final List<String> CLOSED_MODEL_VOCABULARY = List.of(
            "virtual users were applied", "concurrency was increased to", "the applied concurrency",
            "controlled the number of virtual users");

    /**
     * Phrasing that only makes sense if throughput were offered on a fixed schedule — true under
     * {@link WorkloadModel#OPEN} (arrival-rate), never under {@link WorkloadModel#CLOSED}
     * (concurrency), where a falling request rate is the service slowing down, not the workload
     * easing off.
     */
    private static final List<String> OPEN_MODEL_VOCABULARY = List.of(
            "requests were offered at", "the arrival rate was set to", "the applied request rate",
            "controlled the requests per second");

    /** The outcome of an epistemic-integrity pass. */
    public record Result(Analysis analysis, List<String> contradictionsDropped) {

        public Result {
            contradictionsDropped =
                    contradictionsDropped == null ? List.of() : List.copyOf(contradictionsDropped);
        }
    }

    public Result validate(Analysis analysis, EffectiveTestPlan plan, DeterministicSummary summary) {
        List<Finding> kept = new ArrayList<>();
        List<String> contradicted = new ArrayList<>();

        for (Finding finding : analysis.findings()) {
            String lower = finding.statement().toLowerCase(Locale.ROOT);

            if (contradictsVerdict(lower, summary.verdict()) || deniesBreakpoint(lower, summary)
                    || mixesWorkloadModel(lower, plan)) {
                contradicted.add(finding.statement());
                continue;
            }

            Finding adjusted = capConfidenceToType(finding);
            adjusted = downgradeIfUnscopedOperationClaim(adjusted, lower, plan);
            kept.add(adjusted);
        }

        // The headline conclusion is the most visible sentence in the whole analysis. A finding
        // that contradicts the verdict is dropped above; a conclusion that does the same is
        // replaced with the deterministic one-line answer Vortex already computed, rather than
        // left standing or simply blanked.
        String conclusion = contradictsVerdict(analysis.conclusion().toLowerCase(Locale.ROOT),
                summary.verdict())
                ? summary.answer()
                : analysis.conclusion();

        Analysis validated = new Analysis(analysis.id(), analysis.executionId(), analysis.state(),
                conclusion, kept, analysis.recommendations(), analysis.missingTelemetry(),
                analysis.nextTest(), analysis.provenance(), analysis.failureMessage());

        return new Result(validated, contradicted);
    }

    private boolean contradictsVerdict(String lowerStatement, Verdict verdict) {
        List<String> phrases = switch (verdict) {
            case PASS -> PASS_CONTRADICTIONS;
            case FAIL -> FAIL_CONTRADICTIONS;
            case NOT_EVALUATED -> List.of();
        };
        return phrases.stream().anyMatch(lowerStatement::contains);
    }

    private boolean deniesBreakpoint(String lowerStatement, DeterministicSummary summary) {
        return summary.sloBreakpointIfPresent().isPresent()
                && BREAKPOINT_DENIALS.stream().anyMatch(lowerStatement::contains);
    }

    /**
     * Catches a finding that describes this run's throughput using the other workload model's
     * vocabulary — e.g. calling an arrival-rate run's request rate an "applied concurrency". The two
     * models fail differently when a service slows down (see {@link WorkloadModel}'s own Javadoc), so
     * reasoning built on the wrong one is confidently wrong in a way that reads as insight.
     */
    private boolean mixesWorkloadModel(String lowerStatement, EffectiveTestPlan plan) {
        List<String> wrongModelVocabulary = plan.workloadModel() == WorkloadModel.OPEN
                ? CLOSED_MODEL_VOCABULARY
                : OPEN_MODEL_VOCABULARY;
        return wrongModelVocabulary.stream().anyMatch(lowerStatement::contains);
    }

    /**
     * A hypothesis or correlation carrying more confidence than its type permits is the specific
     * failure mode this class exists to stop: an unconfirmed explanation, dressed up as settled,
     * merely because two figures moved together. The claim is kept — it may be a fair hypothesis —
     * only its stated certainty is corrected.
     */
    private Finding capConfidenceToType(Finding finding) {
        Confidence ceiling = finding.type().maxConfidence();
        if (finding.confidence().ordinal() < ceiling.ordinal()) {
            return new Finding(finding.statement(), finding.type(), ceiling, finding.evidenceIds());
        }
        return finding;
    }

    /**
     * A finding that names a specific operation but cites only aggregate evidence is claiming an
     * operation-level authority the evidence does not support — the aggregate figures describe the
     * mix, not any one operation in it. Downgraded rather than dropped, since the underlying
     * observation may still be a reasonable hypothesis once relabelled as one.
     */
    private Finding downgradeIfUnscopedOperationClaim(Finding finding, String lowerStatement,
            EffectiveTestPlan plan) {

        boolean namesOperation = plan.operations().stream()
                .anyMatch(operation -> lowerStatement.contains(
                        operation.name().toLowerCase(Locale.ROOT)));
        if (!namesOperation) {
            return finding;
        }

        boolean hasOperationEvidence = finding.evidenceIds().stream()
                .anyMatch(id -> id.startsWith(EvidenceIds.METRIC + "operation."));
        if (hasOperationEvidence) {
            return finding;
        }

        return new Finding(finding.statement(), FindingType.HYPOTHESIS, Confidence.LOW,
                finding.evidenceIds());
    }
}
