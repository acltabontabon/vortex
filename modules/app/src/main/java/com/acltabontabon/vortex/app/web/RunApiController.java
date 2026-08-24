package com.acltabontabon.vortex.app.web;

import com.acltabontabon.vortex.app.service.AnalysisRunner;
import com.acltabontabon.vortex.app.service.EvidenceContextFactory;
import com.acltabontabon.vortex.app.service.TestRunner;
import com.acltabontabon.vortex.app.web.RunDtos.AiAvailabilityDto;
import com.acltabontabon.vortex.app.web.RunDtos.AnalysisDto;
import com.acltabontabon.vortex.app.web.RunDtos.AnalysisPanelDto;
import com.acltabontabon.vortex.app.web.RunDtos.AnalyzeResponse;
import com.acltabontabon.vortex.app.web.RunDtos.CancelResponse;
import com.acltabontabon.vortex.app.web.RunDtos.FindingDto;
import com.acltabontabon.vortex.app.web.RunDtos.MissingTelemetryDto;
import com.acltabontabon.vortex.app.web.RunDtos.NextTestDto;
import com.acltabontabon.vortex.app.web.RunDtos.PreflightCheckDto;
import com.acltabontabon.vortex.app.web.RunDtos.PreflightDto;
import com.acltabontabon.vortex.app.web.RunDtos.PreflightOperationDto;
import com.acltabontabon.vortex.app.web.RunDtos.RecommendationDto;
import com.acltabontabon.vortex.app.web.RunDtos.RunDto;
import com.acltabontabon.vortex.app.web.RunDtos.RunPlanSummaryDto;
import com.acltabontabon.vortex.app.web.RunDtos.RunProgressDto;
import com.acltabontabon.vortex.app.web.RunDtos.SafetyFindingDto;
import com.acltabontabon.vortex.app.web.RunDtos.StartRunRequest;
import com.acltabontabon.vortex.app.web.RunDtos.StartRunResponse;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.AcceptanceEvidenceDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.AcceptanceResultDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.ComparisonEvidenceDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.EvidenceProvenanceDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.FindingRowDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.LatencyRowDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.LoadAxisDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.MetricDeltaDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.ObservabilityEvidenceDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.ObservabilityGapDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.ObservedSignalDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.OperationEvidenceDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.PerformanceEvidenceDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.RunEvidenceDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.RunIdentityDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.TimelineEvidenceDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.TimelinePlotDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.TimelinePointDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.TimelineSampleRowDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.TimelineStageRowDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.VerdictSectionDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.WorkloadEvidenceDto;
import com.acltabontabon.vortex.core.analysis.Analysis;
import com.acltabontabon.vortex.core.analysis.Finding;
import com.acltabontabon.vortex.core.analysis.MissingTelemetry;
import com.acltabontabon.vortex.core.analysis.NextTestSuggestion;
import com.acltabontabon.vortex.core.analysis.Recommendation;
import com.acltabontabon.vortex.core.application.ComparisonService;
import com.acltabontabon.vortex.core.application.PlanResolutionException;
import com.acltabontabon.vortex.core.application.PreflightCheck;
import com.acltabontabon.vortex.core.application.PreflightReport;
import com.acltabontabon.vortex.core.application.ProjectService;
import com.acltabontabon.vortex.core.application.RunEvidenceService;
import com.acltabontabon.vortex.core.evidence.DeterministicFinding;
import com.acltabontabon.vortex.core.evidence.ObservabilityEvidence;
import com.acltabontabon.vortex.core.evidence.OperationEvidence;
import com.acltabontabon.vortex.core.evidence.RunEvidence;
import com.acltabontabon.vortex.core.evidence.SeriesPlot;
import com.acltabontabon.vortex.core.evidence.TimelineEvidence;
import com.acltabontabon.vortex.core.execution.ExecutionProgress;
import com.acltabontabon.vortex.core.execution.ExecutionState;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.plan.SafetyDecision;
import com.acltabontabon.vortex.core.port.ArtifactStore;
import com.acltabontabon.vortex.core.port.Clock;
import com.acltabontabon.vortex.core.port.Repositories.AnalysisRepository;
import com.acltabontabon.vortex.core.port.Repositories.ExecutionRepository;
import com.acltabontabon.vortex.core.safety.SafetyFinding;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.threshold.Threshold;
import com.acltabontabon.vortex.core.threshold.ThresholdResult;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Preparing, starting, watching and reading a test run, as JSON.
 *
 * <p>Same sequence the retired Thymeleaf controller used (preflight shows what will happen and asks
 * for confirmation where consequences are real; the run streams progress without holding a request
 * thread; the result opens with the question the test asked and answers it), same domain calls, same
 * validation. Only the wire shape at the edge is new.
 */
@RestController
public class RunApiController {

    private static final Logger log = LoggerFactory.getLogger(RunApiController.class);
    private static final long SSE_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final ProjectService projects;
    private final TestRunner testRunner;
    private final ExecutionRepository executions;
    private final AnalysisRepository analyses;
    private final AnalysisRunner analysisRunner;
    private final ArtifactStore artifacts;
    private final ComparisonService comparisons;
    private final Clock clock;
    private final RunEvidenceService evidenceService;
    private final WorkloadView workloadView;
    private final WorkloadDiagramRenderer diagram;
    private final EvidenceContextFactory evidenceContext;
    private final LoadAxisRenderer loadAxisRenderer;
    private final Display display;

    private final Map<String, List<SseEmitter>> listeners = new ConcurrentHashMap<>();

    public RunApiController(ProjectService projects, TestRunner testRunner,
            ExecutionRepository executions, AnalysisRepository analyses, AnalysisRunner analysisRunner,
            ArtifactStore artifacts, ComparisonService comparisons, Clock clock,
            RunEvidenceService evidenceService, WorkloadView workloadView,
            WorkloadDiagramRenderer diagram, EvidenceContextFactory evidenceContext,
            LoadAxisRenderer loadAxisRenderer, Display display) {
        this.projects = projects;
        this.testRunner = testRunner;
        this.executions = executions;
        this.analyses = analyses;
        this.analysisRunner = analysisRunner;
        this.artifacts = artifacts;
        this.comparisons = comparisons;
        this.clock = clock;
        this.evidenceService = evidenceService;
        this.workloadView = workloadView;
        this.diagram = diagram;
        this.evidenceContext = evidenceContext;
        this.loadAxisRenderer = loadAxisRenderer;
        this.display = display;
    }

    // ==================================================================== preflight

    @GetMapping("/api/services/{id}/preflight")
    public PreflightDto preflight(@PathVariable String id,
            @RequestParam(required = false) String workload,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String objective) {

        ProjectId projectId = ProjectId.of(id);
        var configuration = projects.configuration(projectId);

        String workloadName = workload != null ? workload
                : configuration.workloads().stream().findFirst().map(w -> w.name()).orElse(null);
        String environmentName = environment != null ? environment
                : configuration.environments().stream().findFirst().map(e -> e.name()).orElse(null);

        if (workloadName == null || environmentName == null) {
            return emptyPreflight("This project needs at least one workload and one environment "
                    + "before it can run a test.", List.of());
        }

        PreflightReport report;
        try {
            report = testRunner.prepare(projectId, workloadName, environmentName, objective, List.of());
        } catch (PlanResolutionException e) {
            return emptyPreflight(e.getMessage(), e.problems());
        }

        return toDto(report);
    }

    private PreflightDto emptyPreflight(String error, List<String> details) {
        return new PreflightDto(
                false,          // canRun
                null,           // plainEnglishSummary
                null, null, null, // classification, classificationLabel, classificationCaveat
                false, null, null, null, // targetRewritten, configuredTarget, effectiveTarget, targetRewriteReason
                null, null,     // testTypeLabel, testTypeQuestion
                null, null,     // workloadName, environmentName
                null, null,     // environmentTypeLabel, dependencyModeLabel
                null,           // durationDisplay
                null, null, null, // workloadModelLabel, peakLevelDisplay, workloadSourceDescribe
                List.of(),      // operations
                false, null,    // compositionRenderable, compositionSvg
                null,           // offeredLoad
                false, null,    // hasRequestEstimate, requests
                null,           // estimateCaveat
                List.of(),      // mutatingOperations
                List.of(), List.of(), List.of(), // checks, safetyFindings, requiredChallenges
                null, null, null, // fingerprintShortHash, runnerLabel, scriptSourceLabel
                List.of(),      // thresholdDescriptions
                error, details);
    }

    private PreflightDto toDto(PreflightReport report) {
        EffectiveTestPlan plan = report.plan();
        var composition = workloadView.compose(plan);

        List<PreflightOperationDto> operations = plan.operations().stream()
                .map(op -> new PreflightOperationDto(op.name(), op.sharePercent(),
                        op.arrivalRateIfPresent().map(r -> r.display() + "/sec").orElse(null)))
                .toList();

        Long requests = plan.estimatedRequests().orElse(null);

        List<String> mutating = plan.operations().stream()
                .filter(op -> op.isMutating())
                .map(op -> op.method() + " " + op.pathTemplate())
                .distinct()
                .toList();

        List<PreflightCheckDto> checks = report.checks().stream()
                .map(check -> new PreflightCheckDto(check.name(), check.status().name(),
                        check.status().label(), check.detail(), check.remedy()))
                .toList();

        List<SafetyFindingDto> safetyFindings = report.safety().findings().stream()
                .map(f -> new SafetyFindingDto(f.severity().name(), f.severity().label(), f.title(), f.detail()))
                .toList();

        return new PreflightDto(
                report.canRun(),
                report.plainEnglishSummary(),
                plan.classification().name(), plan.classification().label(), plan.classification().caveat(),
                plan.targetWasRewritten(),
                plan.configuredTargetIfPresent().map(target -> target.value())
                        .orElseGet(() -> plan.executionTarget().summary()),
                plan.effectiveTargetIfPresent().map(target -> target.value())
                        .orElseGet(() -> plan.executionTarget().summary()),
                plan.targetRewriteReason(),
                plan.testType().label(), plan.intent().question(),
                plan.workloadName(), plan.environmentName(), plan.environmentType().label(),
                plan.dependencyMode().label(), display.duration(plan.totalDuration()),
                plan.workloadModel().label(), plan.peakLevel().displayWithUnit(),
                plan.workloadSource().describe(),
                operations,
                diagram.isRenderable(composition), diagram.isRenderable(composition)
                        ? diagram.render(composition, plan.peakLevel().displayWithUnit(), plan.projectName()) : null,
                plan.peakLevel().displayWithUnit(), requests != null,
                requests, plan.requestEstimateCaveat(),
                mutating,
                checks, safetyFindings, report.safety().requiredChallenges(),
                plan.fingerprint().shortHash(), plan.runner().label(), plan.scriptSource().label(),
                plan.thresholds().isEmpty() ? List.of() : plan.thresholds().thresholds().stream()
                        .map(Threshold::describe).toList(),
                null, List.of());
    }

    // ==================================================================== starting a run

    @PostMapping("/api/services/{id}/run")
    public StartRunResponse start(@PathVariable String id, @RequestBody StartRunRequest request) {
        ProjectId projectId = ProjectId.of(id);

        PreflightReport report;
        try {
            report = testRunner.prepare(projectId, request.workload(), request.environment(),
                    request.objective(), List.of());
        } catch (PlanResolutionException e) {
            return new StartRunResponse(false, null, e.getMessage(), e.problems());
        }

        if (!report.canRun()) {
            return new StartRunResponse(false, null, "Preflight checks failed, so no traffic was generated.",
                    report.failures().stream().map(check -> check.name() + ": " + check.detail()).toList());
        }

        List<String> required = report.safety().requiredChallenges();
        if (!required.isEmpty()) {
            String confirmation = request.confirmation();
            boolean confirmed = confirmation != null
                    && required.stream().anyMatch(challenge -> challenge.equals(confirmation.trim()));
            if (!confirmed) {
                return new StartRunResponse(false, null,
                        "This run needs confirmation. Type " + String.join(" or ", required) + " to proceed.",
                        List.of());
            }
        }

        List<SafetyDecision> decisions = report.safety().warnings().stream()
                .map(finding -> new SafetyDecision(finding.policyId(), finding.title(), clock.now()))
                .toList();

        var plan = testRunner.resolve(projectId, request.workload(), request.environment(),
                request.objective(), decisions);
        var execution = testRunner.create(plan);

        Thread.ofVirtual()
                .name("vortex-run-" + execution.id().value())
                .start(() -> {
                    try {
                        testRunner.execute(execution.id(), progress -> publish(execution.id(), progress));
                    } catch (RuntimeException e) {
                        log.error("Execution {} failed unexpectedly", execution.id(), e);
                    } finally {
                        finish(execution.id());
                    }
                });

        return new StartRunResponse(true, execution.id().value(), null, List.of());
    }

    @PostMapping("/api/runs/{id}/cancel")
    public CancelResponse cancel(@PathVariable String id) {
        boolean cancelled = testRunner.cancel(ExecutionId.of(id));
        return new CancelResponse(cancelled, cancelled
                ? "Stopping the run. Partial artifacts are kept."
                : "That run is not currently in progress.");
    }

    /**
     * Serves one stored artifact.
     *
     * <p>Streams rather than reading the file into a String, because an execution directory holds
     * the compressed raw sample stream, which for a long run reaches hundreds of megabytes. And it
     * labels the response by extension: everything here was previously sent as {@code text/plain},
     * which corrupts anything that is not text.
     */
    @GetMapping("/runs/{id}/artifacts/{name}")
    public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> artifact(
            @PathVariable String id, @PathVariable String name) {

        ExecutionId executionId = ExecutionId.of(id);
        return artifacts.open(executionId, name)
                .map(stream -> org.springframework.http.ResponseEntity.ok()
                        .contentType(ArtifactMediaTypes.forName(name))
                        .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                                ArtifactMediaTypes.dispositionFor(name))
                        .body((org.springframework.core.io.Resource)
                                new org.springframework.core.io.InputStreamResource(stream)))
                .orElseGet(() -> org.springframework.http.ResponseEntity.notFound().build());
    }

    // ==================================================================== watching a run

    @GetMapping(value = "/api/runs/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String id) {
        ExecutionId executionId = ExecutionId.of(id);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);

        listeners.computeIfAbsent(id, key -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(id, emitter));
        emitter.onTimeout(() -> remove(id, emitter));
        emitter.onError(error -> remove(id, emitter));

        testRunner.progressFor(executionId).ifPresent(progress -> send(emitter, progress));

        if (!testRunner.isRunning(executionId)) {
            complete(emitter);
        }
        return emitter;
    }

    private void publish(ExecutionId executionId, ExecutionProgress progress) {
        List<SseEmitter> current = listeners.get(executionId.value());
        if (current == null) {
            return;
        }
        for (SseEmitter emitter : current) {
            send(emitter, progress);
        }
    }

    private void send(SseEmitter emitter, ExecutionProgress progress) {
        try {
            emitter.send(SseEmitter.event().name("progress").data(toProgressDto(progress)));
        } catch (IOException | IllegalStateException e) {
            log.debug("Progress listener closed: {}", e.getMessage());
        }
    }

    private RunProgressDto toProgressDto(ExecutionProgress progress) {
        long seconds = Math.max(0, progress.elapsed().toSeconds());
        String elapsed = String.format("%02d:%02d", seconds / 60, seconds % 60);
        return new RunProgressDto(
                progress.state().label(),
                elapsed,
                progress.stageLabel(),
                progress.completionFraction().map(fraction -> fraction * 100).orElse(0.0),
                progress.targetLevelIfPresent().map(level -> level.displayWithUnit()).orElse(""),
                progress.currentRateIfPresent().map(rate -> rate.displayWithUnit()).orElse("—"),
                progress.currentP95IfPresent().map(p95 -> p95.toMillis() + " ms").orElse("—"),
                progress.currentErrorRateIfPresent().map(rate -> rate.display()).orElse("—"),
                progress.message(),
                progress.currentResourceReadingIfPresent()
                        .map(reading -> new RunDtos.ResourceReadingDto(reading.cpu(), reading.memory()))
                        .orElse(null));
    }

    private void finish(ExecutionId executionId) {
        List<SseEmitter> current = listeners.remove(executionId.value());
        if (current == null) {
            return;
        }
        for (SseEmitter emitter : current) {
            try {
                emitter.send(SseEmitter.event().name("finished").data("done"));
            } catch (IOException | IllegalStateException e) {
                log.debug("Could not signal completion: {}", e.getMessage());
            }
            complete(emitter);
        }
    }

    private void complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            log.debug("Could not close a progress stream: {}", e.getMessage());
        }
    }

    private void remove(String executionId, SseEmitter emitter) {
        List<SseEmitter> current = listeners.get(executionId);
        if (current != null) {
            current.remove(emitter);
        }
    }

    // ==================================================================== results

    @GetMapping("/api/runs/{id}")
    public RunDto result(@PathVariable String id) {
        ExecutionId executionId = ExecutionId.of(id);
        TestExecution execution = executions.findById(executionId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No execution with id " + id));

        EffectiveTestPlan plan = execution.plan();
        boolean running = !execution.isTerminal();
        RunProgressDto progressDto = testRunner.progressFor(executionId).map(this::toProgressDto).orElse(null);

        RunPlanSummaryDto planSummary = new RunPlanSummaryDto(
                plan.projectId().value(), plan.projectName(), plan.testType().label(), plan.intent().question(),
                plan.workloadName(), plan.environmentName(),
                plan.effectiveTargetIfPresent().map(target -> target.value())
                        .orElseGet(() -> plan.executionTarget().summary()),
                plan.environmentType().label(), plan.workloadModel().label(), plan.peakLevel().displayWithUnit(),
                plan.isSingleOperation(),
                plan.operations().stream().map(op -> op.name()).reduce((a, b) -> a + ", " + b).orElse(""),
                plan.classification().name(), plan.classification().label(), plan.classification().caveat(),
                display.duration(plan.totalDuration()));

        if (execution.state() != ExecutionState.COMPLETED) {
            var failureReason = execution.failureReasonIfPresent().orElse(null);
            return new RunDto(id, running, execution.isTerminal(), execution.state().label(), planSummary,
                    progressDto, display.timestamp(execution.requestedAt()),
                    execution.startedAt() != null ? display.timestamp(execution.startedAt()) : null,
                    failureReason != null, failureReason != null ? failureReason.label() : null,
                    failureReason != null ? failureReason.guidance() : null,
                    execution.failureDetail(),
                    execution.state() == ExecutionState.CANCELLED,
                    null);
        }

        var previous = comparisons.previousCompatible(execution).orElse(null);
        RunEvidence evidence = evidenceService.assemble(execution,
                analyses.findLatest(executionId).orElse(null), previous,
                evidenceContext.forExecution(execution),
                artifacts.directoryFor(executionId), artifacts.list(executionId));

        String measured = execution.plan().serviceVersion();
        String current = projects.configuration(execution.projectId()).serviceVersion();
        boolean releaseMoved = measured != null && !measured.isBlank()
                && current != null && !current.isBlank() && !measured.equals(current);

        RunEvidenceDto evidenceDto = toDto(evidence, releaseMoved, previous);

        return new RunDto(id, false, true, execution.state().label(), planSummary, progressDto,
                display.timestamp(execution.requestedAt()), display.timestamp(execution.startedAt()),
                false, null, null, null, false, evidenceDto);
    }

    // ==================================================================== evidence assembly

    private RunEvidenceDto toDto(RunEvidence evidence, boolean releaseMoved, TestExecution previous) {
        var identity = evidence.identity();
        RunIdentityDto identityDto = new RunIdentityDto(
                identity.executionId().value(), identity.shortId(), identity.serviceName(),
                identity.serviceVersionIfPresent().orElse(null), identity.workloadName(),
                identity.testType().label(), identity.environmentName(), identity.environmentType().label(),
                identity.classification().name(), identity.classification().label(), identity.targetUrl(),
                identity.targetWasRewritten(), identity.targetRewriteReason(),
                identity.targetKind(), identity.targetSummary(), identity.targetOwnershipLabel(),
                identity.resourceSummaryIfPresent().orElse(null),
                identity.requestedAt() != null ? identity.requestedAt().toString() : null,
                display.timestamp(identity.finishedAt()),
                identity.durationIfPresent().map(display::duration).orElse(null),
                identity.testType().name());

        VerdictSectionDto verdictDto = new VerdictSectionDto(
                evidence.question(), evidence.verdict().name(), display.verdictLabel(evidence.verdict()),
                evidence.answer(), evidence.qualifications());

        var workload = evidence.workload();
        WorkloadEvidenceDto workloadDto = new WorkloadEvidenceDto(
                workload.isOpen(), workload.model().label(), workload.model().guidance(),
                workload.configuredPeak().displayWithUnit(), workload.source().describe(),
                workload.achievedRateIfPresent().map(r -> r.displayWithUnit()).orElse(null),
                workload.deliveredPercent().orElse(null), workload.fellShort(), workload.deliveredCaveat(),
                String.valueOf(workload.requests()),
                workload.estimatedRequestsIfPresent().map(String::valueOf).orElse(null),
                evidence.performance().errorRate().display(), String.valueOf(workload.failures()),
                display.duration(workload.configuredDuration()), display.duration(workload.actualDuration()),
                workload.operationMix(), workload.scriptSource().label());

        PerformanceEvidenceDto performanceDto = toDto(evidence.performance());
        AcceptanceEvidenceDto acceptanceDto = toDto(evidence.acceptance());
        List<OperationEvidenceDto> operationsDto = evidence.operations().stream().map(this::toDto).toList();
        LoadAxisDto loadAxisDto = toLoadAxisDto(evidence);
        TimelineEvidenceDto timelineDto = toDto(evidence.timeline(), evidence.hasTimeline());
        ObservabilityEvidenceDto observabilityDto = toDto(evidence.observability(), evidence.hasObservability());
        List<FindingRowDto> findingsDto = evidence.findings().stream().map(this::toDto).toList();
        ComparisonEvidenceDto comparisonDto = evidence.comparisonIfPresent().map(this::toDto).orElse(null);
        EvidenceProvenanceDto provenanceDto = toDto(evidence.provenance());

        return new RunEvidenceDto(identityDto, verdictDto, workloadDto, performanceDto, acceptanceDto,
                evidence.hasOperationBreakdown(), operationsDto, loadAxisDto, timelineDto, observabilityDto,
                evidence.hasFindings(), findingsDto, comparisonDto, provenanceDto, releaseMoved,
                previous != null ? previous.id().value() : null,
                toValidityDto(evidence), toResourcesDto(evidence), toResourceTimelineDto(evidence),
                toCapacityDto(evidence), toLoadSummaryDto(evidence), toReliabilityDto(evidence));
    }

    // ---------------------------------------------------------------- Phase 4 sections

    /**
     * Whether the experiment was carried out as specified.
     *
     * <p>Every finding's statement is passed through as written. The domain composed it to name the
     * measurement and the threshold it crossed, and re-wording it here would produce two versions of
     * the same qualification - one in the export and a friendlier one on the page.
     */
    private RunEvidenceDtos.ValidityDto toValidityDto(RunEvidence evidence) {
        var quality = evidence.quality();
        return new RunEvidenceDtos.ValidityDto(
                quality.quality().name(),
                quality.quality().label(),
                quality.quality().explanation(),
                quality.quality().isAssessed(),
                quality.permitsAnyCapacityClaim(),
                quality.findings().stream()
                        .map(finding -> new RunEvidenceDtos.ValidityFindingDto(
                                finding.reason().name(),
                                finding.reason().label(),
                                finding.effect().name(),
                                finding.statement(),
                                finding.fromLevelIfPresent()
                                        .map(level -> level.displayWithUnit()).orElse(null),
                                finding.evidenceIds()))
                        .toList());
    }

    /**
     * Typed resources, split by whose they are.
     *
     * <p>Split here rather than in React so the page cannot accidentally render a generator's CPU
     * beside the service's. That confusion is the single most damaging one in load testing, and it
     * should not be one careless {@code .map()} away.
     */
    private RunEvidenceDtos.ResourcesDto toResourcesDto(RunEvidence evidence) {
        List<RunEvidenceDtos.ResourceSignalDto> service = new java.util.ArrayList<>();
        List<RunEvidenceDtos.ResourceSignalDto> generator = new java.util.ArrayList<>();
        List<RunEvidenceDtos.ResourceSignalDto> generatorHost = new java.util.ArrayList<>();

        for (var signal : evidence.observability().signals()) {
            signal.resourceIfPresent().ifPresent(resource -> {
                var row = toResourceDto(resource);
                if (resource.scope().describesTheServiceUnderTest()) {
                    service.add(row);
                } else if (resource.scope()
                        == com.acltabontabon.vortex.core.resource.ResourceScope.LOAD_GENERATOR) {
                    generator.add(row);
                } else if (resource.scope()
                        == com.acltabontabon.vortex.core.resource.ResourceScope.LOAD_GENERATOR_HOST) {
                    generatorHost.add(row);
                }
            });
        }

        List<RunEvidenceDtos.ObservabilityGapDto> gaps = evidence.observability().gaps().stream()
                .map(gap -> new RunEvidenceDtos.ObservabilityGapDto(gap.what(), gap.howToCollect()))
                .toList();

        return new RunEvidenceDtos.ResourcesDto(
                !service.isEmpty() || !generator.isEmpty() || !generatorHost.isEmpty()
                        || !gaps.isEmpty(),
                service, generator, generatorHost,
                !generator.isEmpty() || !generatorHost.isEmpty(), gaps);
    }

    /**
     * CPU, memory and the rest of a run's resource behaviour over time — grouped by kind server-side
     * (see {@code ResourceTimelineEvidence}) so the page never has to decide for itself whether two
     * signals belong on the same chart.
     */
    private RunEvidenceDtos.ResourceTimelineEvidenceDto toResourceTimelineDto(RunEvidence evidence) {
        var resourceTimeline = evidence.resourceTimeline();
        List<RunEvidenceDtos.ResourceKindPlotDto> plots = resourceTimeline.plots().stream()
                .map(plot -> new RunEvidenceDtos.ResourceKindPlotDto(
                        plot.kind().name(), plot.label(),
                        plot.series().stream().map(this::toResourceSeriesDto).toList()))
                .toList();
        return new RunEvidenceDtos.ResourceTimelineEvidenceDto(
                resourceTimeline.present(),
                resourceTimeline.completeness().status().name(),
                resourceTimeline.completeness().reason(),
                plots);
    }

    private RunEvidenceDtos.ResourceSeriesDto toResourceSeriesDto(
            com.acltabontabon.vortex.core.evidence.ResourceTimelineEvidence.ResourceSeriesEvidence series) {
        List<RunEvidenceDtos.ResourceTimelinePointDto> points = series.points().stream()
                .map(point -> new RunEvidenceDtos.ResourceTimelinePointDto(
                        point.at().toString(), point.value()))
                .toList();
        return new RunEvidenceDtos.ResourceSeriesDto(
                series.signalId(), series.providerId(), series.scope().name(), series.scopeLabel(),
                series.seriesLabel(), series.unitSymbol(), points, series.display(),
                series.limitDisplay(), series.utilisationDisplay(), series.atItsLimit(),
                series.utilisationFraction(), series.limitValue());
    }

    private RunEvidenceDtos.ResourceSignalDto toResourceDto(
            com.acltabontabon.vortex.core.resource.ResourceSignal resource) {

        return new RunEvidenceDtos.ResourceSignalDto(
                resource.signalId(),
                resource.name(),
                resource.kind().name(),
                resource.kind().label(),
                resource.scope().name(),
                resource.scope().label(),
                resource.observation().display(),
                resource.limitIfPresent().map(limit -> limit.display()).orElse(""),
                resource.utilisation()
                        .map(used -> String.format("%.0f%%", used * 100))
                        .orElse(""),
                resource.isAtItsLimit(),
                resource.describe(),
                resource.utilisation().orElse(null));
    }

    /**
     * The capacity block: the four limits, the five conditions, and the headline or its refusal.
     *
     * <p>Sustainable capacity is the headline. The highest level that passed keeps its name and sits
     * beneath it, explicitly not a capacity claim.
     */
    private RunEvidenceDtos.CapacityDto toCapacityDto(RunEvidence evidence) {
        var performance = evidence.performance();
        var sustainable = evidence.sustainableCapacity();

        List<RunEvidenceDtos.ConditionRowDto> conditions = sustainable.conditions().stream()
                .map(condition -> new RunEvidenceDtos.ConditionRowDto(
                        condition.condition().name(), condition.condition().label(),
                        condition.outcome().name(), condition.outcome().label(),
                        condition.statement()))
                .toList();

        var limits = evidence.limits();
        List<RunEvidenceDtos.LimitRowDto> rows = new java.util.ArrayList<>();
        limits.objectiveBreakpointIfPresent().ifPresent(breakpoint -> rows.add(
                new RunEvidenceDtos.LimitRowDto("OBJECTIVE_BREAKPOINT", "Objective breakpoint",
                        breakpoint.level().displayWithUnit(), breakpoint.describe(), true)));
        rows.add(ceilingRow(limits));
        rows.add(resourceLimitRow(limits));
        limits.systemSaturationIfPresent().ifPresent(saturation -> rows.add(
                new RunEvidenceDtos.LimitRowDto("SYSTEM_SATURATION", "System saturation",
                        saturation.lowerBoundIfPresent()
                                .map(level -> level.displayWithUnit()).orElse(""),
                        saturation.describe(), true)));

        boolean present = sustainable.isEstablished() || !conditions.isEmpty() || !rows.isEmpty()
                || performance.headroomIfPresent().isPresent();

        return new RunEvidenceDtos.CapacityDto(
                present,
                sustainable.levelIfPresent().map(level -> level.displayWithUnit()).orElse(""),
                sustainable.isEstablished() ? "" : sustainable.headline(),
                sustainable.highestLevelThatPassedIfPresent()
                        .map(level -> level.displayWithUnit()).orElse(""),
                sustainable.strength().label(),
                conditions,
                rows,
                limits.describeFirst(),
                limits.noneEstablished(),
                performance.headroomIfPresent().map(headroom -> headroom.display()).orElse(""),
                performance.headroomRefusal());
    }

    private RunEvidenceDtos.LimitRowDto ceilingRow(com.acltabontabon.vortex.core.analysis.LimitFindings limits) {
        var ceiling = limits.throughputCeiling();
        if (ceiling == null) {
            return new RunEvidenceDtos.LimitRowDto("THROUGHPUT_CEILING", "Throughput ceiling", "",
                    "Not evaluated for this run.", false);
        }
        return new RunEvidenceDtos.LimitRowDto("THROUGHPUT_CEILING", "Throughput ceiling",
                ceiling.levelIfPresent().map(level -> level.displayWithUnit()).orElse(""),
                ceiling.describe(), ceiling.isQuotable());
    }

    private RunEvidenceDtos.LimitRowDto resourceLimitRow(
            com.acltabontabon.vortex.core.analysis.LimitFindings limits) {

        var resourceLimit = limits.resourceLimit();
        if (resourceLimit == null) {
            return new RunEvidenceDtos.LimitRowDto("RESOURCE_LIMIT", "Resource limit", "",
                    "Not evaluated for this run.", false);
        }
        return new RunEvidenceDtos.LimitRowDto("RESOURCE_LIMIT", "Resource limit",
                resourceLimit.levelIfPresent().map(level -> level.displayWithUnit()).orElse(""),
                resourceLimit.describe(), resourceLimit.wasReached());
    }

    /**
     * What was asked for, and what the generator managed.
     *
     * <p>Dropped work is rendered only when the engine reported it. An empty string is "nobody
     * measured this"; a zero would say the generator kept up, which is a claim this run may not have
     * made.
     */
    private RunEvidenceDtos.LoadSummaryDto toLoadSummaryDto(RunEvidence evidence) {
        var results = evidence.performance().results();
        var generation = results.generation();

        return new RunEvidenceDtos.LoadSummaryDto(
                results.targetLoadIfPresent().map(level -> level.displayWithUnit()).orElse(""),
                results.achievedRateIfPresent().map(rate -> rate.displayWithUnit()).orElse(""),
                generation.iterationRateIfPresent()
                        .map(rate -> String.format("%.1f/sec", rate)).orElse(""),
                generation.iterationsDroppedIfPresent().map(String::valueOf).orElse(""),
                generation.droppedWork(),
                results.series().points().stream()
                        .map(point -> point.observedVusIfPresent().orElse(0))
                        .max(Integer::compareTo)
                        .filter(peak -> peak > 0)
                        .map(String::valueOf)
                        .orElse(""),
                evidence.workload().deliveredFractionIfPresent()
                        .map(fraction -> String.format("%.0f%%", fraction * 100))
                        .orElse(""));
    }

    /**
     * What kind of outcomes the run produced.
     *
     * <p>{@code reported} false means nothing was classified. The page has to show that as a gap
     * rather than as an empty table, which would read as every request having succeeded.
     */
    private RunEvidenceDtos.ReliabilityDto toReliabilityDto(RunEvidence evidence) {
        var reliability = evidence.performance().results().reliability();
        long total = Math.max(reliability.total(), 1);

        List<RunEvidenceDtos.OutcomeRowDto> responses = reliability.byResponseClass().entrySet()
                .stream()
                .map(entry -> new RunEvidenceDtos.OutcomeRowDto(entry.getKey().label(),
                        entry.getValue(),
                        String.format("%.1f%%", entry.getValue() * 100.0 / total)))
                .toList();

        List<RunEvidenceDtos.OutcomeRowDto> failures = reliability.byFailureClass().entrySet()
                .stream()
                .map(entry -> new RunEvidenceDtos.OutcomeRowDto(entry.getKey().label(),
                        entry.getValue(),
                        String.format("%.1f%%", entry.getValue() * 100.0 / total)))
                .toList();

        return new RunEvidenceDtos.ReliabilityDto(reliability.wasReported(),
                evidence.performance().errorRate().display(), responses, failures);
    }

    private PerformanceEvidenceDto toDto(com.acltabontabon.vortex.core.evidence.PerformanceEvidence performance) {
        List<LatencyRowDto> latencyRows = performance.latency().sorted().entrySet().stream()
                .map(e -> new LatencyRowDto(e.getKey().label(), display.duration(e.getValue())))
                .toList();
        boolean hasLimits = performance.sloBreakpointIfPresent().isPresent()
                || performance.systemSaturationIfPresent().isPresent()
                || performance.headroomIfPresent().isPresent()
                || performance.productionIfPresent().isPresent();
        var breakpoint = performance.sloBreakpointIfPresent().orElse(null);
        var saturation = performance.systemSaturationIfPresent().orElse(null);
        var headroom = performance.headroomIfPresent().orElse(null);
        return new PerformanceEvidenceDto(
                latencyRows, performance.latency().isEmpty() ? null : display.duration(performance.latency().maximum()),
                hasLimits,
                breakpoint != null ? breakpoint.level().displayWithUnit() : null,
                breakpoint != null ? display.evidenceStrengthLabel(breakpoint.strength()) : null,
                breakpoint != null ? breakpoint.stagesObserved() + " levels" : null,
                saturation != null ? saturation.describe() : null,
                saturation != null ? saturation.explanation() : null,
                headroom != null ? headroom.multiple().toPlainString() + "×" : null,
                performance.headroomRefusalIfPresent().orElse(null),
                performance.baselineQuality());
    }

    private AcceptanceEvidenceDto toDto(com.acltabontabon.vortex.core.evidence.AcceptanceEvidence acceptance) {
        List<AcceptanceResultDto> results = acceptance.hasObjectives()
                ? acceptance.results().stream().map(this::toDto).toList() : List.of();
        return new AcceptanceEvidenceDto(acceptance.hasObjectives(), results, acceptance.absenceExplanation());
    }

    private AcceptanceResultDto toDto(ThresholdResult result) {
        String kind = switch (result.threshold()) {
            case com.acltabontabon.vortex.core.threshold.LatencyThreshold t -> "LATENCY";
            case com.acltabontabon.vortex.core.threshold.ErrorRateThreshold t -> "ERROR_RATE";
        };
        return new AcceptanceResultDto(result.threshold().describe(), result.verdict().name(),
                display.verdictLabel(result.verdict()), result.observed(), result.note(), kind,
                result.observedPosition());
    }

    private OperationEvidenceDto toDto(OperationEvidence operation) {
        if (!operation.hasTraffic()) {
            return new OperationEvidenceDto(operation.name(), false, null, null, null, null, null);
        }
        var metrics = operation.metricsIfPresent().orElseThrow();
        return new OperationEvidenceDto(operation.name(), true, String.valueOf(metrics.requests()),
                metrics.achievedRateIfPresent().map(r -> r.display() + " /sec").orElse("—"),
                metrics.latency().p95().map(display::duration).orElse("—"),
                metrics.latency().p99().map(display::duration).orElse("—"),
                metrics.errorRate().display());
    }

    private LoadAxisDto toLoadAxisDto(RunEvidence evidence) {
        var axis = loadAxisRenderer.axisFor(evidence);
        boolean renderable = loadAxisRenderer.isRenderable(axis);
        return new LoadAxisDto(renderable, renderable ? loadAxisRenderer.render(axis) : null,
                axis.drawsBoundary(), axis.drawsSaturation(),
                axis.highestCompliantIfPresent().map(l -> l.displayWithUnit()).orElse(null),
                axis.firstNonCompliantIfPresent().map(l -> l.displayWithUnit()).orElse(null),
                axis.drawsBoundary() ? null : axis.boundaryStatement(),
                axis.saturationIfPresent().map(s -> s.describe()).orElse(null),
                renderable ? axis.testedTo().displayWithUnit() : null);
    }

    private TimelineEvidenceDto toDto(TimelineEvidence timeline, boolean present) {
        if (!present) {
            return new TimelineEvidenceDto(false, List.of(), List.of(), false, List.of(), null, null);
        }
        List<TimelinePlotDto> plots = List.of(
                toDto(timeline.throughputPlot()),
                toDto(timeline.latencyPlot()),
                toDto(timeline.errorRatePlot()));

        List<TimelineStageRowDto> stages = timeline.stages().stream()
                .map(stage -> new TimelineStageRowDto(
                        stage.targetLoad().displayWithUnit(),
                        stage.achievedRate() != null ? stage.achievedRate().display() : "—",
                        stage.p95() != null ? display.duration(stage.p95()) : "—",
                        stage.errorRate().display(),
                        stage.violatedThresholds().isEmpty() ? "met" : "violated",
                        stage.violatedThresholds(),
                        stage.signals().stream().map(s -> s.name() + " " + s.value()).toList(),
                        stage.basis().label()))
                .toList();

        boolean showsCaveat = !timeline.stages().isEmpty()
                && !timeline.stages().get(0).basis().canStrengthenAFinding();

        List<TimelineSampleRowDto> tableRows = timeline.tableRows().stream()
                .map(point -> new TimelineSampleRowDto(
                        display.time(point.at()),
                        point.targetLoadIfPresent().map(l -> l.display()).orElse("—"),
                        point.requestRateIfPresent().map(r -> r.display()).orElse("—"),
                        point.p95IfPresent().map(display::duration).orElse("—"),
                        point.errorRate().display()))
                .toList();

        String breakpointAtIso = timeline.breakpointAtIfPresent().map(Instant::toString).orElse(null);
        String levelChangeAtIso = timeline.levelChangeAtIfPresent().map(Instant::toString).orElse(null);

        return new TimelineEvidenceDto(true, plots, stages, showsCaveat, tableRows, breakpointAtIso,
                levelChangeAtIso);
    }

    private TimelinePlotDto toDto(SeriesPlot plot) {
        if (!plot.hasData()) {
            return new TimelinePlotDto(plot.label(), false, plot.unitSymbol(), List.of(), List.of(), null);
        }
        Double referenceLevel = plot.referenceLevelIfPresent()
                .map(level -> level * plot.scaleMaximum())
                .orElse(null);
        return new TimelinePlotDto(plot.label(), true, plot.unitSymbol(),
                flatten(plot.segments()), flatten(plot.reference()), referenceLevel);
    }

    /**
     * Concatenates a plot's segments into one series for the client charting library, with a single
     * {@code value: null} point between segments — the library treats a null value as a break in the
     * line, which keeps a gap a gap instead of bridging it into an invented measurement (ADR-021).
     */
    private List<TimelinePointDto> flatten(List<SeriesPlot.Segment> segments) {
        if (segments.isEmpty()) {
            return List.of();
        }
        List<TimelinePointDto> points = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                List<SeriesPlot.PlotPoint> previous = segments.get(i - 1).points();
                Instant gapStart = previous.get(previous.size() - 1).at();
                Instant gapEnd = segments.get(i).points().get(0).at();
                Instant midpoint = gapStart.plus(Duration.between(gapStart, gapEnd).dividedBy(2));
                points.add(new TimelinePointDto(midpoint.toString(), null));
            }
            for (SeriesPlot.PlotPoint point : segments.get(i).points()) {
                points.add(new TimelinePointDto(point.at().toString(), point.value()));
            }
        }
        return List.copyOf(points);
    }

    private ObservabilityEvidenceDto toDto(ObservabilityEvidence observability, boolean present) {
        if (!present) {
            return new ObservabilityEvidenceDto(false, List.of(), List.of(), List.of());
        }
        List<ObservedSignalDto> signals = observability.signals().stream()
                .map(signal -> new ObservedSignalDto(signal.name(), signal.display(),
                        signal.movement().orElse(null), signal.source().name(),
                        signal.provenance().flatMap(p -> p.sourceUrlIfPresent()).orElse(null)))
                .toList();
        List<ObservabilityGapDto> gaps = observability.gaps().stream()
                .map(gap -> new ObservabilityGapDto(gap.what(), gap.howToCollect()))
                .toList();
        return new ObservabilityEvidenceDto(true, signals, observability.providersConsulted(), gaps);
    }

    private FindingRowDto toDto(DeterministicFinding finding) {
        return new FindingRowDto(finding.level().name(), finding.level().label(), finding.headline(),
                finding.detail(), finding.hasDetail(), display.evidenceStrengthLabel(finding.strength()),
                finding.evidenceIds());
    }

    private ComparisonEvidenceDto toDto(com.acltabontabon.vortex.core.evidence.ComparisonEvidence comparison) {
        List<MetricDeltaDto> deltas = comparison.deltas().stream()
                .map(delta -> new MetricDeltaDto(delta.metric(), delta.display(), delta.percentChangeDisplay(),
                        delta.isDegradation(com.acltabontabon.vortex.core.comparison.RegressionEvaluator.NOISE_THRESHOLD_PERCENT)
                                .orElse(null),
                        delta.percentChange().map(java.math.BigDecimal::doubleValue).orElse(null)))
                .toList();
        var verdict = comparison.verdictIfPresent().orElse(null);
        return new ComparisonEvidenceDto(comparison.baselineLabel(), display.timestamp(comparison.baselineFinishedAt()),
                deltas, comparison.supportsVerdict(), verdict != null ? verdict.name() : null,
                verdict != null ? verdict.description() : null,
                comparison.notComparableExplanation(), comparison.differences());
    }

    private EvidenceProvenanceDto toDto(com.acltabontabon.vortex.core.evidence.EvidenceProvenance provenance) {
        return new EvidenceProvenanceDto(provenance.toolVersions().vortexVersion(),
                provenance.toolVersions().engineVersion(), provenance.toolVersions().runtimeVersion(),
                provenance.toolVersions().dockerImageIfPresent().orElse(null),
                provenance.configurationHash(), provenance.secretReferences(), provenance.artifactDirectory(),
                provenance.reproductionCommand(), provenance.hasArtifacts(), provenance.artifactNames());
    }

    // ==================================================================== AI analysis

    @PostMapping("/api/runs/{id}/analyze")
    public AnalyzeResponse analyze(@PathVariable String id) {
        ExecutionId executionId = ExecutionId.of(id);
        var availability = analysisRunner.availability();
        if (!availability.available()) {
            return new AnalyzeResponse(false, availability.problem() + " " + availability.remedy());
        }
        boolean started = analysisRunner.start(executionId);
        return new AnalyzeResponse(started, started
                ? "Analysing. The measurements above are already final — this only adds interpretation."
                : "An analysis of this run is already in progress.");
    }

    @GetMapping("/api/runs/{id}/analysis")
    public AnalysisPanelDto analysisPanel(@PathVariable String id) {
        ExecutionId executionId = ExecutionId.of(id);
        boolean analysing = analysisRunner.isRunning(executionId);
        var latest = analysisRunner.latest(executionId).orElse(null);
        var history = analysisRunner.history(executionId);
        var availability = analysisRunner.availability();

        List<AnalysisDto> earlier = history.size() > 1
                ? history.subList(1, history.size()).stream().map(this::toDto).toList() : List.of();

        return new AnalysisPanelDto(analysing, latest != null ? toDto(latest) : null,
                Math.max(0, history.size() - 1), earlier,
                new AiAvailabilityDto(availability.available(), availability.problem(), availability.remedy()));
    }

    private AnalysisDto toDto(Analysis analysis) {
        return new AnalysisDto(
                analysis.conclusion(),
                analysis.findings().stream().map(this::toDto).toList(),
                analysis.recommendations().stream().map(this::toDto).toList(),
                analysis.missingTelemetry().stream().map(this::toDto).toList(),
                analysis.nextTestIfPresent().map(this::toDto).orElse(null),
                analysis.provenanceIfPresent().map(p -> p.describe()).orElse(null));
    }

    private FindingDto toDto(Finding finding) {
        return new FindingDto(finding.statement(), finding.type().name(), finding.type().label(),
                display.confidenceLabel(finding.confidence()), finding.evidenceIds());
    }

    private RecommendationDto toDto(Recommendation recommendation) {
        return new RecommendationDto(recommendation.action(), recommendation.rationale(),
                recommendation.evidenceIds());
    }

    private NextTestDto toDto(NextTestSuggestion nextTest) {
        return new NextTestDto(nextTest.action(), nextTest.rationale(), nextTest.wouldDistinguish(),
                nextTest.evidenceIds());
    }

    private MissingTelemetryDto toDto(MissingTelemetry missing) {
        return new MissingTelemetryDto(missing.what(), missing.whyItMatters());
    }
}
