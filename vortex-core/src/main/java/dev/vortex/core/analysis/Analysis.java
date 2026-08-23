package dev.vortex.core.analysis;

import dev.vortex.core.shared.AnalysisId;
import dev.vortex.core.shared.ExecutionId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An AI interpretation of one execution's measurements.
 *
 * <p>An execution may have many analyses. They accumulate rather than replace: re-analysing with a
 * newer model or prompt adds a record, and the earlier one remains inspectable. Measurements are
 * immutable; interpretations are versioned.
 *
 * <p>Everything here is downstream of deterministic truth. The verdict, the breakpoints, the
 * threshold results and the headroom are computed before any of this exists, and nothing in an
 * analysis can change them.
 *
 * @param id               stable identifier
 * @param executionId      the execution being interpreted
 * @param state            lifecycle state
 * @param conclusion       the headline interpretation
 * @param findings         supporting interpretations, each citing resolved evidence
 * @param recommendations  suggested next actions, all requiring human approval
 * @param missingTelemetry measurements whose absence limited the analysis
 * @param nextTest         the highest-information experiment to run next, when one would genuinely
 *                         help
 * @param provenance       which model and prompt produced this
 * @param failureMessage   why the analysis failed, when it did
 */
public record Analysis(
        AnalysisId id,
        ExecutionId executionId,
        AnalysisState state,
        String conclusion,
        List<Finding> findings,
        List<Recommendation> recommendations,
        List<MissingTelemetry> missingTelemetry,
        NextTestSuggestion nextTest,
        AnalysisProvenance provenance,
        String failureMessage) {

    public Analysis {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(state, "state");
        conclusion = conclusion == null ? "" : conclusion;
        findings = findings == null ? List.of() : List.copyOf(findings);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        missingTelemetry = missingTelemetry == null ? List.of() : List.copyOf(missingTelemetry);
        failureMessage = failureMessage == null ? "" : failureMessage;
    }

    public static Analysis pending(AnalysisId id, ExecutionId executionId) {
        return new Analysis(id, executionId, AnalysisState.PENDING, "", List.of(), List.of(),
                List.of(), null, null, "");
    }

    public static Analysis failed(AnalysisId id, ExecutionId executionId, String message) {
        return new Analysis(id, executionId, AnalysisState.FAILED, "", List.of(), List.of(),
                List.of(), null, null, message);
    }

    public Analysis transitionTo(AnalysisState next) {
        state.requireTransitionTo(next);
        return new Analysis(id, executionId, next, conclusion, findings, recommendations,
                missingTelemetry, nextTest, provenance, failureMessage);
    }

    public boolean isUsable() {
        return state == AnalysisState.COMPLETED && !conclusion.isBlank();
    }

    public Optional<NextTestSuggestion> nextTestIfPresent() {
        return Optional.ofNullable(nextTest);
    }

    public Optional<AnalysisProvenance> provenanceIfPresent() {
        return Optional.ofNullable(provenance);
    }

    /** The strongest finding, used as the headline hypothesis on the result page. */
    public Optional<Finding> primaryFinding() {
        return findings.stream()
                .filter(Finding::isSupported)
                .max(java.util.Comparator.comparing(f -> f.confidence().ordinal() * -1));
    }
}
