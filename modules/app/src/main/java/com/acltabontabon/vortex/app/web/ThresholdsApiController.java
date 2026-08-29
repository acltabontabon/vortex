package com.acltabontabon.vortex.app.web;

import com.acltabontabon.vortex.core.application.ProjectService;
import com.acltabontabon.vortex.core.application.ThresholdHistoryEntry;
import com.acltabontabon.vortex.core.application.ThresholdHistoryService;
import com.acltabontabon.vortex.core.application.ThresholdRecommendationService;
import com.acltabontabon.vortex.core.port.Clock;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.shared.Percentile;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.threshold.Durations;
import com.acltabontabon.vortex.core.threshold.ErrorRateThreshold;
import com.acltabontabon.vortex.core.threshold.LatencyThreshold;
import com.acltabontabon.vortex.core.threshold.ObjectiveNarrative;
import com.acltabontabon.vortex.core.threshold.Threshold;
import com.acltabontabon.vortex.core.threshold.ThresholdScope;
import com.acltabontabon.vortex.core.threshold.ThresholdSet;
import com.acltabontabon.vortex.core.threshold.recommend.EvidenceQuality;
import com.acltabontabon.vortex.core.threshold.recommend.SanityFinding;
import com.acltabontabon.vortex.core.threshold.recommend.ThresholdEvidence;
import com.acltabontabon.vortex.core.threshold.recommend.ThresholdProvenance;
import com.acltabontabon.vortex.core.threshold.recommend.ThresholdRecommendation;
import com.acltabontabon.vortex.core.threshold.recommend.ThresholdRecommender;
import com.acltabontabon.vortex.core.threshold.recommend.ThresholdSanityChecker;
import com.acltabontabon.vortex.core.threshold.recommend.ThresholdSetProvenance;
import com.acltabontabon.vortex.core.workload.Observation;
import com.acltabontabon.vortex.core.workload.Workload;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Threshold Assistant's backend surface: evidence-backed recommendations, live sanity checking,
 * saving a workload's own thresholds with the evidence behind them, and reading a threshold's history.
 *
 * <p>Owns evidence selection, comparison math, recommendation calculation and consistency validation —
 * per the feature's own design, the frontend only ever renders what this returns and never computes a
 * number itself. Recommendation is read here through {@link ThresholdRecommender}, which is
 * architecturally separate from {@code ThresholdEvaluator} (runtime pass/fail) by an ArchUnit rule —
 * nothing in this controller, or anything it calls, may influence how a run is actually evaluated.
 */
@RestController
@RequestMapping("/api/services/{id}")
public class ThresholdsApiController {

    private final ProjectService projects;
    private final ThresholdRecommendationService evidence;
    private final ThresholdRecommender recommender;
    private final ThresholdSanityChecker sanityChecker;
    private final ThresholdHistoryService history;
    private final Clock clock;

    public ThresholdsApiController(ProjectService projects, ThresholdRecommendationService evidence,
            ThresholdRecommender recommender, ThresholdSanityChecker sanityChecker,
            ThresholdHistoryService history, Clock clock) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.recommender = Objects.requireNonNull(recommender, "recommender");
        this.sanityChecker = Objects.requireNonNull(sanityChecker, "sanityChecker");
        this.history = Objects.requireNonNull(history, "history");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---------------------------------------------------------------------------------- shared DTOs

    /**
     * One threshold, in wire form. {@code id} is derived from content and only ever appears in a
     * response — the domain computes it, nobody supplies it.
     */
    public record ThresholdDto(String id, String kind, Double percentile, Long maxMillis,
            Double maxErrorPercent, String operationId, String describe) {}

    public record ThresholdProvenanceDto(String source, String sourceLabel, String detail,
            String windowFrom, String windowTo, String derivation, String evidenceQuality,
            String baselineExecutionId) {}

    // ------------------------------------------------------------------------------ recommendation

    /**
     * @param rawValue the underlying number, in milliseconds for a latency metric or percent for an
     *                 error rate — never parsed from {@code displayValue}, which exists for display
     *                 only. Lets the frontend set a field or build a comparison request numerically
     *                 without ever parsing formatted text, per the feature's "frontend never
     *                 calculates" rule.
     */
    public record ThresholdEvidenceDto(String displayValue, double rawValue, String sourceLabel,
            String window, String evidenceQuality, boolean stale, String runQuality, String executionId) {}

    public record ThresholdRecommendationOptionDto(String label, String source, String sourceLabel,
            String displayValue, double rawValue, String derivation, String evidenceQuality) {}

    public record ThresholdRecommendationPanelDto(ThresholdEvidenceDto production,
            List<ThresholdEvidenceDto> baselines, List<ThresholdRecommendationOptionDto> recommendations) {}

    public record WorkloadThresholdsDto(List<ThresholdDto> thresholds,
            Map<String, ThresholdProvenanceDto> provenance) {}

    /** A workload's own threshold overrides, and the evidence behind each one — what the composer
     *  prefills its Objectives region from. */
    @GetMapping("/tests/{name}/thresholds")
    public WorkloadThresholdsDto workloadThresholds(@PathVariable String id, @PathVariable String name) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);
        Workload workload = configuration.workloadByName(name).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No test named '" + name + "'"));

        List<ThresholdDto> thresholds = workload.thresholds().thresholds().stream()
                .map(ThresholdsApiController::toDto).toList();
        Map<String, ThresholdProvenanceDto> provenance = new LinkedHashMap<>();
        for (Threshold threshold : workload.thresholds().thresholds()) {
            workload.thresholdProvenance().forThreshold(threshold.id())
                    .ifPresent(p -> provenance.put(threshold.id(), toDto(p)));
        }
        return new WorkloadThresholdsDto(thresholds, provenance);
    }

    /**
     * The service-level objectives every workload inherits unless it sets its own, and the evidence
     * behind each one — what the Configuration page's Objectives section prefills from. This is the
     * normal home for a service's thresholds; a per-workload override (above) exists for the case one
     * specific test genuinely needs different intent, and is not required for ordinary use.
     */
    @GetMapping("/thresholds")
    public WorkloadThresholdsDto projectThresholds(@PathVariable String id) {
        ProjectConfiguration configuration = projects.configuration(ProjectId.of(id));

        List<ThresholdDto> thresholds = configuration.thresholds().thresholds().stream()
                .map(ThresholdsApiController::toDto).toList();
        Map<String, ThresholdProvenanceDto> provenance = new LinkedHashMap<>();
        for (Threshold threshold : configuration.thresholds().thresholds()) {
            configuration.thresholdProvenance().forThreshold(threshold.id())
                    .ifPresent(p -> provenance.put(threshold.id(), toDto(p)));
        }
        return new WorkloadThresholdsDto(thresholds, provenance);
    }

    /** Saves the service-level objectives, and the evidence behind each one. */
    @PutMapping("/thresholds")
    public SaveThresholdsResponse saveProjectThresholds(@PathVariable String id,
            @RequestBody SaveThresholdsRequest request) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);

        try {
            ThresholdSet proposed = toThresholdSet(request.thresholds());
            List<SanityFinding> consistency = sanityChecker.checkConsistency(proposed);
            if (!consistency.isEmpty()) {
                List<SanityFindingDto> dtos = consistency.stream()
                        .map(f -> new SanityFindingDto(f.severity().name(), f.thresholdId(), f.message()))
                        .toList();
                return new SaveThresholdsResponse(false,
                        "These objectives contradict each other and cannot be saved.", dtos);
            }

            ThresholdSetProvenance provenance = toProvenanceSet(request.provenance());
            projects.saveConfiguration(projectId, configuration.withThresholds(proposed, provenance));
            return new SaveThresholdsResponse(true, null, List.of());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    private static ThresholdProvenanceDto toDto(ThresholdProvenance p) {
        return new ThresholdProvenanceDto(p.source().name(), p.source().label(), p.detail(),
                p.observedAt().fromIfPresent().map(Object::toString).orElse(null),
                p.observedAt().toIfPresent().map(Object::toString).orElse(null),
                p.derivation(), p.quality().name(), p.baselineExecutionId());
    }

    /**
     * Available evidence and deterministic candidate values for one metric — the "Help me choose"
     * panel's entire payload. Absent evidence produces an empty panel, never an error: missing
     * evidence must never block configuring or running a test.
     *
     * @param workload narrows baseline candidacy to one workload's own history; omitted for the
     *                 normal, service-level case, which aggregates across the whole project
     */
    @GetMapping("/tests/threshold-recommendation")
    public ThresholdRecommendationPanelDto recommendation(@PathVariable String id,
            @RequestParam(required = false) String workload, @RequestParam String metric,
            @RequestParam(required = false) Double percentile,
            @RequestParam(required = false) Double improvementPercent) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);
        Instant now = clock.now();
        BigDecimal improvementFraction = improvementPercent == null
                ? null : BigDecimal.valueOf(improvementPercent).movePointLeft(2);

        boolean latency = "LATENCY".equalsIgnoreCase(metric);
        if (latency && percentile == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "a latency recommendation requires a percentile");
        }

        ThresholdEvidence thresholdEvidence = latency
                ? this.evidence.latencyEvidence(projectId, configuration, workload, Percentile.of(percentile))
                : this.evidence.errorRateEvidence(projectId, configuration, workload);

        List<ThresholdRecommendation> recommendations = latency
                ? recommender.recommendLatency(thresholdEvidence, now, improvementFraction)
                : recommender.recommendErrorRate(thresholdEvidence, now, improvementFraction);

        return new ThresholdRecommendationPanelDto(
                thresholdEvidence.productionIfPresent().map(p -> toDto(p, now)).orElse(null),
                thresholdEvidence.baselines().stream().map(b -> toDto(b, now)).toList(),
                recommendations.stream().map(ThresholdsApiController::toDto).toList());
    }

    private static ThresholdEvidenceDto toDto(ThresholdEvidence.ProductionEvidence production, Instant now) {
        String display = production.latency() != null
                ? Durations.display(production.latency()) : production.errorRate().display();
        double raw = production.latency() != null
                ? production.latency().toMillis() : production.errorRate().asPercent();
        EvidenceQuality quality = EvidenceQuality.ofProduction(
                production.fetched(), production.mixCoverageComplete(), production.isStale(now));
        return new ThresholdEvidenceDto(display, raw, production.source(), production.observedAt().describe(),
                quality.name(), production.isStale(now), null, null);
    }

    private static ThresholdEvidenceDto toDto(ThresholdEvidence.BaselineEvidence baseline, Instant now) {
        String display = baseline.latency() != null
                ? Durations.display(baseline.latency()) : baseline.errorRate().display();
        double raw = baseline.latency() != null
                ? baseline.latency().toMillis() : baseline.errorRate().asPercent();
        boolean stale = baseline.isStale(now);
        EvidenceQuality quality = baseline.quality() == com.acltabontabon.vortex.core.validity.RunQuality.INVALID
                ? EvidenceQuality.LIMITED : EvidenceQuality.ofBaseline(baseline.quality(), 1, stale);
        return new ThresholdEvidenceDto(display, raw, "run " + baseline.executionId(), "", quality.name(), stale,
                baseline.quality().label(), baseline.executionId());
    }

    private static ThresholdRecommendationOptionDto toDto(ThresholdRecommendation recommendation) {
        ThresholdProvenance provenance = recommendation.provenance();
        double raw = recommendation.latencyValue() != null
                ? recommendation.latencyValue().toMillis() : recommendation.errorRateValue().asPercent();
        return new ThresholdRecommendationOptionDto(recommendation.label(), provenance.source().name(),
                provenance.source().label(), recommendation.displayValue(), raw,
                provenance.derivationIfPresent().orElse(""), provenance.quality().name());
    }

    // -------------------------------------------------------------------------------- sanity check

    /**
     * @param productionByThresholdId reference value (ms for latency, percent for error rate) keyed
     *                                by {@code Threshold.id()} — per-threshold, not shared, because a
     *                                p95 objective and a p99 objective in the same request compare
     *                                against different production percentiles
     * @param baselineByThresholdId   the same shape, for the best Vortex baseline
     */
    public record SanityCheckRequest(List<ThresholdDto> thresholds, String workload,
            Map<String, Double> productionByThresholdId, Map<String, Double> baselineByThresholdId) {}

    public record SanityFindingDto(String severity, String thresholdId, String message) {}

    public record SanityCheckResponse(List<SanityFindingDto> findings, boolean blocksSave,
            Map<String, String> comparisons) {}

    /**
     * Deterministic checks against a proposed set — contradictory percentiles (blocks save) plus
     * advisory strict/loose flags against whatever comparison values the caller supplies — and, for
     * every threshold with a reference to compare against, the one-line "how does this compare" text
     * ({@link ObjectiveNarrative#compareLatency}/{@code compareErrorRate}) that drives live-typing
     * feedback. Production is preferred over baseline when both are supplied — the same precedence
     * {@code ThresholdRecommender} uses for "Balanced". Called both from live-typing feedback and,
     * again, as the actual gate at save time — the client's own check is never trusted on its own.
     */
    @PostMapping("/tests/thresholds/sanity-check")
    public SanityCheckResponse sanityCheck(@PathVariable String id, @RequestBody SanityCheckRequest request) {
        ThresholdSet proposed = toThresholdSet(request.thresholds());
        List<SanityFinding> findings = new java.util.ArrayList<>(sanityChecker.checkConsistency(proposed));
        Map<String, String> comparisons = new LinkedHashMap<>();
        Map<String, Double> productionByThresholdId = request.productionByThresholdId() == null
                ? Map.of() : request.productionByThresholdId();
        Map<String, Double> baselineByThresholdId = request.baselineByThresholdId() == null
                ? Map.of() : request.baselineByThresholdId();

        for (Threshold threshold : proposed.thresholds()) {
            Double productionRaw = productionByThresholdId.get(threshold.id());
            Double baselineRaw = baselineByThresholdId.get(threshold.id());
            if (threshold instanceof LatencyThreshold latency) {
                Duration production = productionRaw == null ? null : Duration.ofMillis(productionRaw.longValue());
                Duration baseline = baselineRaw == null ? null : Duration.ofMillis(baselineRaw.longValue());
                findings.addAll(sanityChecker.checkLatencyThreshold(latency, production, baseline));
                if (production != null) {
                    comparisons.put(threshold.id(),
                            ObjectiveNarrative.compareLatency(latency.maximum(), production, "current production behavior"));
                } else if (baseline != null) {
                    comparisons.put(threshold.id(),
                            ObjectiveNarrative.compareLatency(latency.maximum(), baseline, "your Vortex baseline"));
                }
            } else if (threshold instanceof ErrorRateThreshold errorRate) {
                ErrorRate production = productionRaw == null ? null : ErrorRate.ofPercent(productionRaw);
                ErrorRate baseline = baselineRaw == null ? null : ErrorRate.ofPercent(baselineRaw);
                findings.addAll(sanityChecker.checkErrorRateThreshold(errorRate, production));
                if (production != null) {
                    comparisons.put(threshold.id(),
                            ObjectiveNarrative.compareErrorRate(errorRate.maximum(), production, "current production behavior"));
                } else if (baseline != null) {
                    comparisons.put(threshold.id(),
                            ObjectiveNarrative.compareErrorRate(errorRate.maximum(), baseline, "your Vortex baseline"));
                }
            }
        }

        boolean blocks = findings.stream().anyMatch(f -> f.severity() == com.acltabontabon.vortex.core.threshold.recommend.Severity.INVALID);
        List<SanityFindingDto> dtos = findings.stream()
                .map(f -> new SanityFindingDto(f.severity().name(), f.thresholdId(), f.message()))
                .toList();
        return new SanityCheckResponse(dtos, blocks, comparisons);
    }

    // ------------------------------------------------------------------------------------- saving

    public record SaveThresholdsRequest(List<ThresholdDto> thresholds, Map<String, ThresholdProvenanceDto> provenance) {}

    public record SaveThresholdsResponse(boolean ok, String error, List<SanityFindingDto> findings) {}

    /** Saves a workload's own threshold overrides, and the evidence behind each one. */
    @PutMapping("/tests/{name}/thresholds")
    public SaveThresholdsResponse saveThresholds(@PathVariable String id, @PathVariable String name,
            @RequestBody SaveThresholdsRequest request) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);
        Workload workload = configuration.workloadByName(name).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No test named '" + name + "'"));

        try {
            ThresholdSet proposed = toThresholdSet(request.thresholds());
            List<SanityFinding> consistency = sanityChecker.checkConsistency(proposed);
            if (!consistency.isEmpty()) {
                List<SanityFindingDto> dtos = consistency.stream()
                        .map(f -> new SanityFindingDto(f.severity().name(), f.thresholdId(), f.message()))
                        .toList();
                return new SaveThresholdsResponse(false,
                        "These objectives contradict each other and cannot be saved.", dtos);
            }

            ThresholdSetProvenance provenance = toProvenanceSet(request.provenance());
            projects.saveConfiguration(projectId,
                    configuration.withWorkload(workload.withThresholds(proposed, provenance)));
            return new SaveThresholdsResponse(true, null, List.of());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------------------------ history

    public record ThresholdHistoryEntryDto(String executionId, String value, String at) {}

    @GetMapping("/tests/{name}/thresholds/history")
    public List<ThresholdHistoryEntryDto> thresholdHistory(@PathVariable String id, @PathVariable String name,
            @RequestParam String thresholdId) {
        ProjectId projectId = ProjectId.of(id);
        return history.history(projectId, name, thresholdId).stream()
                .map(ThresholdsApiController::toDto)
                .toList();
    }

    /** The service-level threshold's history, aggregated across every workload's runs. */
    @GetMapping("/thresholds/history")
    public List<ThresholdHistoryEntryDto> projectThresholdHistory(@PathVariable String id,
            @RequestParam String thresholdId) {
        ProjectId projectId = ProjectId.of(id);
        return history.history(projectId, null, thresholdId).stream()
                .map(ThresholdsApiController::toDto)
                .toList();
    }

    private static ThresholdHistoryEntryDto toDto(ThresholdHistoryEntry entry) {
        return new ThresholdHistoryEntryDto(entry.executionId(), entry.value(), entry.at().toString());
    }

    // ----------------------------------------------------------------------------------- narrative

    public record NarrativeRequest(List<ThresholdDto> thresholds) {}

    public record NarrativeResponse(String narrative, String breakpointCondition) {}

    /** The deterministic, templated plain-language summary of what a set of objectives requires. */
    @PostMapping("/tests/thresholds/narrative")
    public NarrativeResponse narrative(@PathVariable String id, @RequestBody NarrativeRequest request) {
        ThresholdSet thresholds = toThresholdSet(request.thresholds());
        String narrative = ObjectiveNarrative.describe(thresholds, Map.of());
        String breakpoint = thresholds.isEmpty() ? null : ObjectiveNarrative.describeBreakpointCondition(thresholds);
        return new NarrativeResponse(narrative, breakpoint);
    }

    // ------------------------------------------------------------------------------------- shared

    private static ThresholdSet toThresholdSet(List<ThresholdDto> dtos) {
        if (dtos == null || dtos.isEmpty()) {
            return ThresholdSet.empty();
        }
        return new ThresholdSet(dtos.stream().map(ThresholdsApiController::toDomain).toList());
    }

    private static Threshold toDomain(ThresholdDto dto) {
        ThresholdScope scope = dto.operationId() == null || dto.operationId().isBlank()
                ? ThresholdScope.OVERALL : ThresholdScope.of(OperationId.of(dto.operationId()));
        if ("ERROR_RATE".equalsIgnoreCase(dto.kind())) {
            if (dto.maxErrorPercent() == null) {
                throw new IllegalArgumentException("an error-rate threshold requires maxErrorPercent");
            }
            return new ErrorRateThreshold(scope, ErrorRate.ofPercent(dto.maxErrorPercent()));
        }
        if (dto.percentile() == null || dto.maxMillis() == null) {
            throw new IllegalArgumentException("a latency threshold requires percentile and maxMillis");
        }
        return new LatencyThreshold(scope, Percentile.of(dto.percentile()), Duration.ofMillis(dto.maxMillis()));
    }

    static ThresholdDto toDto(Threshold threshold) {
        String operationId = threshold.scope().operationIfPresent().map(OperationId::value).orElse(null);
        if (threshold instanceof LatencyThreshold latency) {
            return new ThresholdDto(threshold.id(), "LATENCY", latency.percentile().asPercent(),
                    latency.maximum().toMillis(), null, operationId, threshold.describe());
        }
        ErrorRateThreshold errorRate = (ErrorRateThreshold) threshold;
        return new ThresholdDto(threshold.id(), "ERROR_RATE", null, null,
                errorRate.maximum().asPercent(), operationId, threshold.describe());
    }

    private ThresholdSetProvenance toProvenanceSet(Map<String, ThresholdProvenanceDto> requested) {
        Instant now = clock.now();
        Map<String, ThresholdProvenance> byId = new LinkedHashMap<>();
        if (requested != null) {
            requested.forEach((thresholdId, dto) -> byId.put(thresholdId, toDomain(dto, now)));
        }
        return new ThresholdSetProvenance(byId);
    }

    private static ThresholdProvenance toDomain(ThresholdProvenanceDto dto, Instant now) {
        com.acltabontabon.vortex.core.threshold.recommend.ThresholdSource source =
                com.acltabontabon.vortex.core.threshold.recommend.ThresholdSource.valueOf(dto.source());
        String detail = dto.detail() == null ? "" : dto.detail();

        if (source == com.acltabontabon.vortex.core.threshold.recommend.ThresholdSource.MANUAL_OBJECTIVE) {
            return ThresholdProvenance.manual(now);
        }
        if (source == com.acltabontabon.vortex.core.threshold.recommend.ThresholdSource.SLO
                || source == com.acltabontabon.vortex.core.threshold.recommend.ThresholdSource.EXTERNAL_REQUIREMENT) {
            return ThresholdProvenance.attributed(source, detail, now);
        }

        Observation observedAt = dto.windowFrom() == null ? Observation.unknown()
                : dto.windowTo() == null ? Observation.at(Instant.parse(dto.windowFrom()))
                : Observation.over(Instant.parse(dto.windowFrom()), Instant.parse(dto.windowTo()));
        EvidenceQuality quality = dto.evidenceQuality() == null
                ? EvidenceQuality.LIMITED : EvidenceQuality.valueOf(dto.evidenceQuality());
        return ThresholdProvenance.derived(source, detail, observedAt,
                dto.derivation() == null ? "" : dto.derivation(), quality,
                dto.baselineExecutionId() == null ? "" : dto.baselineExecutionId(), now);
    }
}
