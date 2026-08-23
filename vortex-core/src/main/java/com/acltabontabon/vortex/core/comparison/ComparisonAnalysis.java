package com.acltabontabon.vortex.core.comparison;

import com.acltabontabon.vortex.core.analysis.AnalysisProvenance;
import com.acltabontabon.vortex.core.analysis.AnalysisState;
import com.acltabontabon.vortex.core.analysis.Finding;
import com.acltabontabon.vortex.core.analysis.MissingTelemetry;
import com.acltabontabon.vortex.core.shared.AnalysisId;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An AI interpretation of what materially changed between two executions.
 *
 * <p>Deliberately smaller than {@link com.acltabontabon.vortex.core.analysis.Analysis}: the job here is to
 * explain deltas Vortex has already computed and flag which deserve investigation, not to propose
 * remediation — so there are no recommendations and no next test in this first version. Everything
 * else follows the same discipline as a single-run analysis: every finding cites evidence (here,
 * {@code delta:} identifiers), and an unresolvable citation is discarded rather than shown.
 *
 * @param id            stable identifier
 * @param baselineId    the earlier execution being compared
 * @param candidateId   the later execution being compared
 * @param state         lifecycle state, reusing {@link AnalysisState} — the same PENDING → RUNNING
 *                       → COMPLETED/FAILED shape applies unchanged
 * @param conclusion     the headline interpretation
 * @param findings       supporting interpretations, each citing resolved deltas
 * @param missingTelemetry deltas or comparisons the interpretation referred to but could not resolve
 * @param provenance     which model and prompt produced this
 * @param failureMessage why the interpretation failed, when it did
 */
public record ComparisonAnalysis(
        AnalysisId id,
        ExecutionId baselineId,
        ExecutionId candidateId,
        AnalysisState state,
        String conclusion,
        List<Finding> findings,
        List<MissingTelemetry> missingTelemetry,
        AnalysisProvenance provenance,
        String failureMessage) {

    public ComparisonAnalysis {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(baselineId, "baselineId");
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(state, "state");
        conclusion = conclusion == null ? "" : conclusion;
        findings = findings == null ? List.of() : List.copyOf(findings);
        missingTelemetry = missingTelemetry == null ? List.of() : List.copyOf(missingTelemetry);
        failureMessage = failureMessage == null ? "" : failureMessage;
    }

    public static ComparisonAnalysis failed(AnalysisId id, ExecutionId baselineId,
            ExecutionId candidateId, String message) {
        return new ComparisonAnalysis(id, baselineId, candidateId, AnalysisState.FAILED, "",
                List.of(), List.of(), null, message);
    }

    public boolean isUsable() {
        return state == AnalysisState.COMPLETED && !conclusion.isBlank();
    }

    public Optional<AnalysisProvenance> provenanceIfPresent() {
        return Optional.ofNullable(provenance);
    }
}
