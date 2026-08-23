package dev.vortex.app.web;

import java.util.List;

/**
 * A completed run's evidence — the twelve fragments {@code evidence.html} used to render, as one
 * object tree. The load axis stays a server-rendered SVG string (see {@link RunDtos}); the timeline
 * plots instead carry raw points ({@link TimelinePlotDto}) for the browser to chart with a real
 * charting library. Both still originate from the one {@code SeriesPlot} in vortex-core that also
 * feeds the PDF export, so the browser and the printed report never disagree about the numbers —
 * only about how those numbers are drawn.
 */
public final class RunEvidenceDtos {

    private RunEvidenceDtos() {
    }

    /**
     * @param targetKind      {@code EXTERNAL_ENDPOINT} | {@code DOCKER_IMAGE} | {@code
     *                        DOCKER_COMPOSE} — always known, even for a historical run predating
     *                        this feature (normalizes to {@code EXTERNAL_ENDPOINT})
     * @param targetSummary   the declared target's own summary, e.g. "Docker: payment-service:1.4.2"
     * @param targetOwnershipLabel "Vortex managed" or "Externally managed"
     * @param resourceSummary the run's confirmed resource envelope, e.g. "0.5 CPU · 512 MiB", or
     *                        null when none was confirmed — never an empty string standing in for
     *                        absence
     */
    public record RunIdentityDto(String executionId, String shortId, String serviceName,
            String serviceVersion, String workloadName, String testTypeLabel, String environmentName,
            String environmentTypeLabel, String classification, String classificationLabel,
            String targetUrl, boolean targetWasRewritten, String targetRewriteReason,
            String targetKind, String targetSummary, String targetOwnershipLabel,
            String resourceSummary,
            String requestedAtIso, String finishedAtDisplay, String durationDisplay,
            /** Raw {@code TestType} enum name, e.g. {@code STRESS} — alongside {@code testTypeLabel}
             *  so a renderer can key stable behaviour off an identifier rather than a display string
             *  that is free to reword. */
            String testType) {
    }

    public record VerdictSectionDto(String question, String verdict, String verdictLabel, String answer,
            List<String> qualifications) {
    }

    public record WorkloadEvidenceDto(boolean open, String modelLabel, String modelGuidance,
            String configuredPeakDisplay, String sourceDescribe, String achievedRateDisplay,
            String deliveredPercent, boolean fellShort, String deliveredCaveat, String requestsDisplay,
            String estimatedRequestsDisplay, String errorRateDisplay, String failuresDisplay,
            String configuredDurationDisplay, String actualDurationDisplay, List<String> operationMix,
            String scriptSourceLabel) {
    }

    public record LatencyRowDto(String percentileLabel, String durationDisplay) {
    }

    public record PerformanceEvidenceDto(List<LatencyRowDto> latencyRows, String maxLatencyDisplay,
            boolean hasLimitsCard, String sloBreakpointDisplay, String sloBreakpointStrengthLabel,
            String sloBreakpointStagesText, String systemSaturationDescribe,
            String systemSaturationExplanation, String headroomDisplay, String headroomRefusal,
            List<String> baselineQuality) {
    }

    /** {@code kind} is {@code LATENCY}, {@code ERROR_RATE}, from the sealed {@code Threshold} type
     *  the result was evaluated against — never guessed client-side from {@code describe}'s text. */
    public record AcceptanceResultDto(String describe, String verdict, String verdictLabel,
            String observed, String note, String kind,
            /** {@code observed} as a fraction of the threshold, e.g. 0.42 at 42% of the limit; null
             *  when the measurement was unavailable. Lets a renderer place a marker on an objective
             *  bar without re-parsing {@code observed}. */
            Double observedPosition) {
    }

    public record AcceptanceEvidenceDto(boolean hasObjectives, List<AcceptanceResultDto> results,
            String absenceExplanation) {
    }

    public record OperationEvidenceDto(String name, boolean hasTraffic, String requestsDisplay,
            String rateDisplay, String p95Display, String p99Display, String errorRateDisplay) {
    }

    public record LoadAxisDto(boolean renderable, String svg,
            boolean drawsBoundary, boolean drawsSaturation, String highestCompliantDisplay,
            String firstNonCompliantDisplay, String boundaryStatement, String saturationDescribe,
            String testedToDisplay) {
    }

    public record TimelineStageRowDto(String levelDisplay, String achievedDisplay, String p95Display,
            String errorRateDisplay, String resultKind, List<String> violatedThresholds,
            List<String> signals, String basisLabel) {
    }

    /**
     * One measured point, or a gap marker ({@code value == null}) where {@link
     * dev.vortex.core.evidence.SeriesPlot} split the series into segments — drawn as a break in the
     * line rather than bridged, so a period where nothing was measured never reads as a measurement.
     */
    public record TimelinePointDto(String atIso, Double value) {
    }

    public record TimelinePlotDto(String label, boolean hasData, String unitSymbol,
            List<TimelinePointDto> points, List<TimelinePointDto> referencePoints,
            Double referenceLevel) {
    }

    public record TimelineSampleRowDto(String timeDisplay, String offeredDisplay, String achievedDisplay,
            String p95Display, String errorRateDisplay) {
    }

    public record TimelineEvidenceDto(boolean present, List<TimelinePlotDto> plots,
            List<TimelineStageRowDto> stages, boolean showsDerivedCaveat,
            List<TimelineSampleRowDto> tableRows, String breakpointAtIso, String levelChangeAtIso) {
    }

    public record ObservedSignalDto(String name, String display, String movement, String sourceLabel,
            String sourceUrl) {
    }

    // ------------------------------------------------------------------ Experiment

    /**
     * One thing that was wrong with the experiment, in the words a reader argues with.
     *
     * @param statement always names the measurement and the threshold it crossed
     */
    public record ValidityFindingDto(String code, String label, String effect, String statement,
            String fromLevel, List<String> evidenceIds) {
    }

    /**
     * Whether this run measured what it claims to.
     *
     * @param assessed false for a run recorded before this axis existed. Distinct from a grade:
     *                 such a run carries no findings and nothing is withheld on its account
     */
    public record ValidityDto(String grade, String label, String explanation, boolean assessed,
            boolean permitsCapacityClaims, List<ValidityFindingDto> findings) {
    }

    // ------------------------------------------------------------------ Resources

    /**
     * A measurement a provider classified, with the system it describes and its limit.
     *
     * @param scope       {@code system_under_test}, {@code load_generator}, or
     *                    {@code load_generator_host}. Rendered distinctly: reading one as another is
     *                    the failure this phase exists to prevent
     * @param limitDisplay empty when the provider published no limit, which is not the same as a
     *                    resource that stayed clear of one
     */
    public record ResourceSignalDto(String id, String name, String kind, String kindLabel,
            String scope, String scopeLabel, String display, String limitDisplay,
            String utilisationDisplay, boolean atItsLimit, String describe,
            /** The same fraction {@code utilisationDisplay} formats, as a number — null under the
             *  same condition {@code utilisationDisplay} is empty. */
            Double utilisationFraction) {
    }

    /**
     * What was observed about each system's resources.
     *
     * @param generator     the load generator's own process or container — the narrowest measurement
     *                      Vortex could isolate, and the only one generator saturation may rest on
     * @param generatorHost the whole machine running the load generator — broader supporting
     *                      telemetry, never proof by itself that the generator was constrained
     * @param generatorObserved false means nobody looked at the machine producing the traffic - never
     *                          that it was healthy
     */
    public record ResourcesDto(boolean present, List<ResourceSignalDto> service,
            List<ResourceSignalDto> generator, List<ResourceSignalDto> generatorHost,
            boolean generatorObserved, List<ObservabilityGapDto> gaps) {
    }

    // ------------------------------------------------------------------ Resource timeline

    public record ResourceTimelinePointDto(String atIso, double value) {
    }

    /**
     * One line on a resource chart.
     *
     * @param scope        {@code SYSTEM_UNDER_TEST}, {@code LOAD_GENERATOR} or {@code DEPENDENCY} —
     *                     rendered distinctly for the same reason {@link ResourceSignalDto#scope}
     *                     is: reading one system's resource as another's is the failure this whole
     *                     phase exists to prevent
     * @param limitDisplay empty when no limit was published
     */
    public record ResourceSeriesDto(String signalId, String providerId, String scope,
            String scopeLabel, String seriesLabel, String unitSymbol,
            List<ResourceTimelinePointDto> points, String display, String limitDisplay,
            String utilisationDisplay, boolean atItsLimit,
            /** The same fraction {@code utilisationDisplay} formats, as a number — null under the
             *  same condition {@code utilisationDisplay} is empty. */
            Double utilisationFraction) {
    }

    public record ResourceKindPlotDto(String kind, String kindLabel, List<ResourceSeriesDto> series) {
    }

    /**
     * CPU, memory and the rest of a run's resource behaviour over time.
     *
     * @param completenessStatus {@code COMPLETE}, {@code PARTIAL} or {@code UNAVAILABLE} — presence
     *                           of the underlying artifact is not the same question as whether it
     *                           describes the whole run, and the page must never show a partial
     *                           series as though it were complete
     * @param completenessReason empty for a complete run; why otherwise
     */
    public record ResourceTimelineEvidenceDto(boolean present, String completenessStatus,
            String completenessReason, List<ResourceKindPlotDto> plots) {
    }

    // ------------------------------------------------------------------ Capacity

    public record ConditionRowDto(String condition, String label, String outcome,
            String outcomeLabel, String statement) {
    }

    public record LimitRowDto(String kind, String label, String level, String describe,
            boolean established) {
    }

    /**
     * What this run says about how much the service can carry.
     *
     * @param sustainableDisplay the headline, or empty when none was established
     * @param refusal            why there is no headline. Never both this and a figure, never neither
     * @param highestPassing     today's tested compliant level, kept beneath and explicitly not a
     *                           capacity claim
     */
    public record CapacityDto(boolean present, String sustainableDisplay, String refusal,
            String highestPassing, String strengthLabel, List<ConditionRowDto> conditions,
            List<LimitRowDto> limits, String firstLimit, boolean noLimitEstablished,
            String headroomDisplay, String headroomRefusal) {
    }

    // ------------------------------------------------------------------ Load

    /**
     * What the generator was asked for and what it managed.
     *
     * @param droppedDisplay empty when the engine reported nothing, which is not zero drops
     */
    public record LoadSummaryDto(String requestedDisplay, String achievedDisplay,
            String iterationRateDisplay, String droppedDisplay, boolean droppedWork,
            String observedConcurrency, String deliveredShare) {
    }

    // ------------------------------------------------------------------ Reliability

    public record OutcomeRowDto(String label, long count, String share) {
    }

    /**
     * What kind of outcomes the run produced.
     *
     * @param reported false means nothing was classified, which must never read as everything
     *                 having succeeded
     */
    public record ReliabilityDto(boolean reported, String errorRateDisplay,
            List<OutcomeRowDto> byResponseClass, List<OutcomeRowDto> byFailureClass) {
    }

    public record ObservabilityGapDto(String what, String howToCollect) {
    }

    public record ObservabilityEvidenceDto(boolean present, List<ObservedSignalDto> signals,
            List<String> providersConsulted, List<ObservabilityGapDto> gaps) {
    }

    public record FindingRowDto(String levelKind, String levelLabel, String headline, String detail,
            boolean hasDetail, String strengthLabel, List<String> evidenceIds) {
    }

    public record MetricDeltaDto(String metric, String display, String percentChangeDisplay,
            /** {@code MetricDelta.isDegradation()} at the evaluator's own noise threshold — null when
             *  the change was too small to classify or a percentage does not apply. A page must never
             *  re-derive this by re-comparing baseline/candidate itself: whether a movement counts as
             *  a regression is a domain decision, made once. */
            Boolean isDegradation,
            /** {@code MetricDelta.percentChange()} as a signed number — null under the same condition
             *  {@code percentChangeDisplay} reads "—". */
            Double percentChange) {
    }

    public record ComparisonEvidenceDto(String baselineLabel, String baselineFinishedAtDisplay,
            List<MetricDeltaDto> deltas, boolean supportsVerdict, String verdictLabel,
            String verdictDescription, String notComparableExplanation, List<String> differences) {
    }

    public record EvidenceProvenanceDto(String vortexVersion, String engineVersion,
            String runtimeVersion, String dockerImage, String configurationHash,
            List<String> secretReferences, String artifactDirectory, String reproductionCommand,
            boolean hasArtifacts, List<String> artifactNames) {
    }

    public record RunEvidenceDto(
            RunIdentityDto identity,
            VerdictSectionDto verdict,
            WorkloadEvidenceDto workload,
            PerformanceEvidenceDto performance,
            AcceptanceEvidenceDto acceptance,
            boolean hasOperationBreakdown,
            List<OperationEvidenceDto> operations,
            LoadAxisDto loadAxis,
            TimelineEvidenceDto timeline,
            ObservabilityEvidenceDto observability,
            boolean hasFindings,
            List<FindingRowDto> findings,
            ComparisonEvidenceDto comparison,
            EvidenceProvenanceDto provenance,
            boolean releaseMoved,
            String previousCompatibleExecutionId,
            ValidityDto validity,
            ResourcesDto resources,
            ResourceTimelineEvidenceDto resourceTimeline,
            CapacityDto capacity,
            LoadSummaryDto load,
            ReliabilityDto reliability) {
    }
}
