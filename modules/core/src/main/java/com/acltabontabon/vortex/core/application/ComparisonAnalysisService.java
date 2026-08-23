package com.acltabontabon.vortex.core.application;

import com.acltabontabon.vortex.core.analysis.AnalysisState;
import com.acltabontabon.vortex.core.comparison.Comparability;
import com.acltabontabon.vortex.core.comparison.ComparisonAnalysis;
import com.acltabontabon.vortex.core.comparison.ExecutionComparison;
import com.acltabontabon.vortex.core.comparison.RegressionVerdict;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.port.PerformanceAssistant;
import com.acltabontabon.vortex.core.shared.AnalysisId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Adds AI interpretation to a deterministic comparison, without ever computing its own deltas.
 *
 * <p>Sibling of {@link AnalysisService}. The one structural difference: an incomparable pair is
 * detected before the assistant is ever called. {@link Comparability#INVALID} is itself a
 * deterministic fact — Vortex already knows an arrival-rate run cannot be meaningfully set against
 * a concurrency run — so no inference is spent reaching that conclusion a second time.
 *
 * <p>Comparisons are not persisted the way single-run analyses are: the deterministic comparison
 * itself is recomputed on every view, and this mirrors that — a comparison is an exploratory,
 * many-to-many query, not a fixed record with its own accumulating history.
 */
public final class ComparisonAnalysisService {

    private final ComparisonService comparisons;
    private final ComparisonEvidenceAssembler evidenceAssembler;
    private final PerformanceAssistant assistant;
    private final EvidenceReferenceValidator validator;

    public ComparisonAnalysisService(ComparisonService comparisons,
            ComparisonEvidenceAssembler evidenceAssembler, PerformanceAssistant assistant,
            EvidenceReferenceValidator validator) {
        this.comparisons = Objects.requireNonNull(comparisons, "comparisons");
        this.evidenceAssembler = Objects.requireNonNull(evidenceAssembler, "evidenceAssembler");
        this.assistant = Objects.requireNonNull(assistant, "assistant");
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    public ComparisonAnalysis analyze(TestExecution baseline, TestExecution candidate) {
        ComparisonService.Result result = comparisons.compareAndEvaluate(baseline, candidate);
        ExecutionComparison comparison = result.comparison();
        RegressionVerdict verdict = result.verdict();

        Comparability comparability = evidenceAssembler.classify(baseline, candidate, comparison);
        if (comparability == Comparability.INVALID) {
            return new ComparisonAnalysis(AnalysisId.generate(), baseline.id(), candidate.id(),
                    AnalysisState.COMPLETED,
                    "These two executions are not meaningfully comparable: "
                            + String.join("; ", comparison.differences().isEmpty()
                                    ? List.of("nothing was measured on one or both sides")
                                    : comparison.differences())
                            + ". The figures are not shown as a regression result.",
                    List.of(), List.of(), null, "");
        }

        var availability = assistant.availability();
        if (!availability.available()) {
            return ComparisonAnalysis.failed(AnalysisId.generate(), baseline.id(), candidate.id(),
                    availability.problem() + " " + availability.remedy());
        }

        ComparisonAnalysis produced;
        try {
            produced = assistant.compareExecutions(baseline, candidate, comparison, verdict);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ComparisonAnalysis.failed(AnalysisId.generate(), baseline.id(), candidate.id(),
                    "The comparison interpretation did not complete: " + message
                            + " The computed differences above are unaffected.");
        }

        if (produced.state() == AnalysisState.FAILED) {
            return produced;
        }

        Set<String> availableDeltaIds = new LinkedHashSet<>();
        comparison.deltas().forEach(delta -> availableDeltaIds.add(delta.evidenceId()));

        return validator.validateComparison(produced, availableDeltaIds).analysis();
    }

    /** Whether an interpretation can be offered at all right now. */
    public PerformanceAssistant.Availability availability() {
        return assistant.availability();
    }
}
