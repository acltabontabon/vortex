package com.acltabontabon.vortex.app.web;

import java.util.List;

/**
 * Wire shapes for the cross-service run history and the two-run comparison screen — the two pieces
 * of the run lifecycle that are not scoped to one execution. {@code MetricDeltaDto} is shared with
 * {@link RunEvidenceDtos}: a comparison here and a comparison embedded in one run's evidence carry
 * exactly the same delta shape, because they are the same computation.
 */
public final class GlobalRunDtos {

    private GlobalRunDtos() {
    }

    public record ProjectOptionDto(String id, String name) {
    }

    public record RunHistoryRowDto(
            String executionId,
            String projectId,
            String projectName,
            String serviceVersion,
            String testTypeLabel,
            String workloadName,
            String environmentName,
            String classificationLabel,
            boolean terminal,
            String verdict,
            String verdictLabel,
            String stateLabel,
            String offeredLoadDisplay,
            String achievedRateDisplay,
            String p95Display,
            String relativeTime) {
    }

    public record RunHistoryDto(
            List<RunHistoryRowDto> rows,
            int totalBeforeFilters,
            List<ProjectOptionDto> projects,
            List<String> evaluations,
            List<String> workloadNames,
            List<String> environments,
            List<String> results) {
    }

    public record CompareSideDto(String executionId, String workloadName, String serviceVersion,
            String environmentName, String requestedAtDisplay) {
    }

    public record CompareResultDto(
            CompareSideDto baseline,
            CompareSideDto candidate,
            boolean baselineReleaseMissing,
            boolean candidateReleaseMissing,
            boolean supportsRegressionVerdict,
            String notComparableExplanation,
            List<String> differences,
            List<RunEvidenceDtos.MetricDeltaDto> deltas,
            String verdictLabel,
            String verdictDescription) {
    }
}
