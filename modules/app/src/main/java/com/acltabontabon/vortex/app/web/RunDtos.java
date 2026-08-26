package com.acltabontabon.vortex.app.web;

import java.util.List;

/**
 * Wire shapes for the run lifecycle — preflight, the live SSE view, a completed run's evidence,
 * and the two AI interpretation panels.
 *
 * <p>The load axis stays server-rendered: {@code LoadAxis} is documented in {@code vortex-core} as
 * semantic, not geometric — the actual SVG path math lives in {@code LoadAxisRenderer}, which this
 * controller still calls, embedding the resulting markup as a string field. Re-deriving that
 * geometry in React would be a real second implementation of the same drawing logic for no reader-
 * facing benefit — nothing here needs the raw geometry, only the picture. See {@link
 * RunEvidenceDtos} for why the timeline plots take the opposite approach.
 */
public final class RunDtos {

    private RunDtos() {
    }

    // ---------------------------------------------------------------- preflight

    public record PreflightOperationDto(String name, String sharePercent, String rateDisplay) {
    }

    public record PreflightCheckDto(String name, String statusKind, String statusLabel, String detail,
            String remedy) {
    }

    public record SafetyFindingDto(String severityKind, String severityLabel, String title, String detail) {
    }

    public record PreflightDto(
            boolean canRun,
            String plainEnglishSummary,
            String classification,
            String classificationLabel,
            String classificationCaveat,
            boolean targetRewritten,
            String configuredTarget,
            String effectiveTarget,
            String targetRewriteReason,
            String testTypeLabel,
            String testTypeQuestion,
            String workloadName,
            String environmentName,
            String environmentTypeLabel,
            String dependencyModeLabel,
            String durationDisplay,
            String workloadModelLabel,
            String peakLevelDisplay,
            String workloadSourceDescribe,
            List<PreflightOperationDto> operations,
            boolean compositionRenderable,
            String compositionSvg,
            String offeredLoad,
            boolean hasRequestEstimate,
            Long requests,
            String estimateCaveat,
            List<String> mutatingOperations,
            List<PreflightCheckDto> checks,
            List<SafetyFindingDto> safetyFindings,
            List<String> requiredChallenges,
            String fingerprintShortHash,
            String runnerLabel,
            String scriptSourceLabel,
            List<String> thresholdDescriptions,
            String error,
            List<String> errorDetails) {
    }

    public record StartRunRequest(String workload, String environment, String objective, String confirmation) {
    }

    public record StartRunResponse(boolean started, String executionId, String error, List<String> errorDetails) {
    }

    // ---------------------------------------------------------------- the live/terminal read

    /** Field-for-field against the original SSE payload shape the retired Thymeleaf run-live page used. */
    /**
     * @param message         a human-readable status line, currently populated only while target
     *                        preparation is in progress ({@code state == "STARTING"}) — the frontend
     *                        renders it verbatim, without parsing, deduplicating, or accumulating it
     *                        into a checklist. Empty for an ordinary external-endpoint run, which
     *                        never has anything to say here, and empty again once {@code RUNNING}
     *                        starts (traffic status lives in {@code stage} from that point on)
     * @param resourceReading the target's live CPU/memory reading in the most recent bucket, or null
     *                        — see {@code ExecutionProgress.currentResourceReading}'s own javadoc for
     *                        why this is always null in v1
     */
    public record RunProgressDto(String state, String elapsed, String stage, double percent,
            String targetRate, String currentRate, String p95, String errorRate, String message,
            ResourceReadingDto resourceReading) {
    }

    public record ResourceReadingDto(String cpu, String memory) {
    }

    public record RunPlanSummaryDto(
            String projectId,
            String projectName,
            String testTypeLabel,
            String testTypeQuestion,
            String workloadName,
            String environmentName,
            String targetDisplay,
            String environmentTypeLabel,
            String workloadModelLabel,
            String peakLevelDisplay,
            boolean singleOperation,
            String operationsSummary,
            String classification,
            String classificationLabel,
            String classificationCaveat,
            String totalDurationDisplay) {
    }

    public record RunDto(
            String executionId,
            boolean running,
            boolean terminal,
            String stateLabel,
            RunPlanSummaryDto plan,
            RunProgressDto progress,
            String requestedAtDisplay,
            String startedAtDisplay,
            boolean failed,
            String failureLabel,
            String failureGuidance,
            String failureDetail,
            boolean cancelled,
            RunEvidenceDtos.RunEvidenceDto evidence) {
    }

    public record CancelResponse(boolean cancelled, String message) {
    }

    // ---------------------------------------------------------------- AI analysis

    public record FindingDto(String statement, String typeKind, String typeLabel,
            String confidenceKind, String confidenceLabel, List<String> evidenceIds) {
    }

    public record RecommendationDto(String action, String rationale, List<String> evidenceIds) {
    }

    public record NextTestDto(String action, String rationale, String wouldDistinguish, List<String> evidenceIds) {
    }

    public record MissingTelemetryDto(String what, String whyItMatters) {
    }

    /** {@code state} is {@code "COMPLETED" | "FAILED" | "PENDING" | "RUNNING"}; {@code
     *  failureMessage} is only non-null when {@code state} is {@code "FAILED"}. */
    public record AnalysisDto(String state, String conclusion, List<FindingDto> findings,
            List<RecommendationDto> recommendations, List<MissingTelemetryDto> missingTelemetry,
            NextTestDto nextTest, String provenanceDescribe, String failureMessage) {
    }

    public record AiAvailabilityDto(boolean available, String problem, String remedy) {
    }

    public record AnalysisPanelDto(boolean analysing, AnalysisDto latest, int earlierCount,
            List<AnalysisDto> earlier, AiAvailabilityDto availability) {
    }

    public record AnalyzeResponse(boolean started, String message) {
    }

    // ---------------------------------------------------------------- comparison

    /** Same {@code state}/{@code failureMessage} convention as {@link AnalysisDto}. */
    public record ComparisonAnalysisDto(String state, String conclusion, List<FindingDto> findings,
            List<MissingTelemetryDto> missingTelemetry, String provenanceDescribe,
            String failureMessage) {
    }

    public record ComparisonAnalysisPanelDto(boolean analysing, ComparisonAnalysisDto latest,
            AiAvailabilityDto availability) {
    }
}
