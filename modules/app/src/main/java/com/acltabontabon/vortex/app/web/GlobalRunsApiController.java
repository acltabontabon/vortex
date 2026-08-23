package com.acltabontabon.vortex.app.web;

import com.acltabontabon.vortex.app.service.ComparisonAnalysisRunner;
import com.acltabontabon.vortex.app.web.GlobalRunDtos.CompareResultDto;
import com.acltabontabon.vortex.app.web.GlobalRunDtos.CompareSideDto;
import com.acltabontabon.vortex.app.web.GlobalRunDtos.ProjectOptionDto;
import com.acltabontabon.vortex.app.web.GlobalRunDtos.RunHistoryDto;
import com.acltabontabon.vortex.app.web.GlobalRunDtos.RunHistoryRowDto;
import com.acltabontabon.vortex.app.web.RunDtos.AiAvailabilityDto;
import com.acltabontabon.vortex.app.web.RunDtos.AnalyzeResponse;
import com.acltabontabon.vortex.app.web.RunDtos.ComparisonAnalysisDto;
import com.acltabontabon.vortex.app.web.RunDtos.ComparisonAnalysisPanelDto;
import com.acltabontabon.vortex.app.web.RunDtos.FindingDto;
import com.acltabontabon.vortex.app.web.RunDtos.MissingTelemetryDto;
import com.acltabontabon.vortex.app.web.RunEvidenceDtos.MetricDeltaDto;
import com.acltabontabon.vortex.core.analysis.Finding;
import com.acltabontabon.vortex.core.analysis.MissingTelemetry;
import com.acltabontabon.vortex.core.application.ComparisonService;
import com.acltabontabon.vortex.core.application.ProjectService;
import com.acltabontabon.vortex.core.comparison.ComparisonAnalysis;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.port.Repositories.ExecutionRepository;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.shared.ProjectId;
import java.util.List;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The two run screens that are not scoped to one service: the run history across every service, and
 * two runs set side by side. Same filtering, same lookup, same AI-comparison sequence the retired
 * {@code runs.html}/{@code compare.html} controller used — carried over exactly, as JSON.
 */
@RestController
public class GlobalRunsApiController {

    private final ProjectService projects;
    private final ExecutionRepository executions;
    private final ComparisonService comparisons;
    private final ComparisonAnalysisRunner comparisonAnalysisRunner;
    private final Display display;

    public GlobalRunsApiController(ProjectService projects, ExecutionRepository executions,
            ComparisonService comparisons, ComparisonAnalysisRunner comparisonAnalysisRunner,
            Display display) {
        this.projects = projects;
        this.executions = executions;
        this.comparisons = comparisons;
        this.comparisonAnalysisRunner = comparisonAnalysisRunner;
        this.display = display;
    }

    // ==================================================================== history

    @GetMapping("/api/runs")
    public RunHistoryDto history(@RequestParam(required = false) String project,
            @RequestParam(required = false) String evaluation,
            @RequestParam(required = false) String workload,
            @RequestParam(required = false) String environment,
            @RequestParam(required = false) String result) {

        List<TestExecution> all = project == null || project.isBlank()
                ? executions.findRecent(100)
                : executions.findByProject(ProjectId.of(project), 100);

        List<TestExecution> filtered = all.stream()
                .filter(run -> matches(evaluation, run.plan().testType().name()))
                .filter(run -> matches(workload, run.plan().workloadName()))
                .filter(run -> matches(environment, run.plan().environmentName()))
                .filter(run -> matches(result, run.verdict().name()))
                .toList();

        List<ProjectOptionDto> projectOptions = projects.all().stream()
                .map(p -> new ProjectOptionDto(p.id().value(), p.name()))
                .toList();

        return new RunHistoryDto(
                filtered.stream().map(this::toDto).toList(),
                all.size(),
                projectOptions,
                distinct(all, run -> run.plan().testType().name()),
                distinct(all, run -> run.plan().workloadName()),
                distinct(all, run -> run.plan().environmentName()),
                distinct(all, run -> run.verdict().name()));
    }

    private RunHistoryRowDto toDto(TestExecution execution) {
        var plan = execution.plan();
        var achievedRate = execution.resultsIfPresent()
                .flatMap(results -> results.achievedRateIfPresent());
        var p95 = execution.resultsIfPresent().flatMap(results -> results.latency().p95());

        return new RunHistoryRowDto(
                execution.id().value(), plan.projectId().value(), plan.projectName(),
                plan.serviceVersion(), plan.testType().label(), plan.workloadName(),
                plan.environmentName(), plan.classification().label(),
                execution.isTerminal(), execution.verdict().name(),
                display.verdictLabel(execution.verdict()), execution.state().label(),
                plan.peakLevel().displayWithUnit(),
                achievedRate.map(rate -> rate.display()).orElse(null),
                p95.map(display::duration).orElse(null),
                display.relative(execution.requestedAt()));
    }

    private boolean matches(String wanted, String actual) {
        return wanted == null || wanted.isBlank() || wanted.equalsIgnoreCase(actual);
    }

    private List<String> distinct(List<TestExecution> runs, Function<TestExecution, String> of) {
        return runs.stream().map(of).filter(value -> !value.isBlank()).distinct().sorted().toList();
    }

    // ==================================================================== compare

    @GetMapping("/api/runs/compare")
    public CompareResultDto compare(@RequestParam String baseline, @RequestParam String candidate) {
        TestExecution left = find(baseline);
        TestExecution right = find(candidate);

        var result = comparisons.compareAndEvaluate(left, right);
        var comparison = result.comparison();
        var verdict = result.verdict();

        List<MetricDeltaDto> deltas = comparison.deltas().stream()
                .map(delta -> new MetricDeltaDto(delta.metric(), delta.display(), delta.percentChangeDisplay(),
                        delta.isDegradation(com.acltabontabon.vortex.core.comparison.RegressionEvaluator.NOISE_THRESHOLD_PERCENT)
                                .orElse(null),
                        delta.percentChange().map(java.math.BigDecimal::doubleValue).orElse(null)))
                .toList();

        return new CompareResultDto(
                toSide(left), toSide(right),
                left.plan().serviceVersion() == null || left.plan().serviceVersion().isBlank(),
                right.plan().serviceVersion() == null || right.plan().serviceVersion().isBlank(),
                comparison.supportsRegressionVerdict(), comparison.notComparableExplanation(),
                comparison.differences(), deltas,
                result.supportsVerdict() ? verdict.label() : null,
                result.supportsVerdict() ? verdict.description() : null);
    }

    private CompareSideDto toSide(TestExecution execution) {
        var plan = execution.plan();
        return new CompareSideDto(execution.id().value(), plan.workloadName(),
                plan.serviceVersion() == null || plan.serviceVersion().isBlank()
                        ? null : plan.serviceVersion(),
                plan.environmentName(), display.timestamp(execution.requestedAt()));
    }

    private TestExecution find(String id) {
        return executions.findById(ExecutionId.of(id)).orElseThrow(
                () -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "No execution with id " + id));
    }

    /** Requests an AI interpretation of a comparison — always after the fact, never able to change
     * the computed differences. */
    @PostMapping("/api/runs/compare/analyze")
    public AnalyzeResponse analyzeComparison(@RequestParam String baseline, @RequestParam String candidate) {
        TestExecution left = find(baseline);
        TestExecution right = find(candidate);

        var availability = comparisonAnalysisRunner.availability();
        if (!availability.available()) {
            return new AnalyzeResponse(false, availability.problem() + " " + availability.remedy());
        }

        boolean started = comparisonAnalysisRunner.start(left, right);
        return new AnalyzeResponse(started, started
                ? "Interpreting. The differences above are already final — this only adds interpretation."
                : "An interpretation of this comparison is already in progress.");
    }

    @GetMapping("/api/runs/compare/analysis")
    public ComparisonAnalysisPanelDto comparisonAnalysisPanel(@RequestParam String baseline,
            @RequestParam String candidate) {
        ExecutionId baselineId = ExecutionId.of(baseline);
        ExecutionId candidateId = ExecutionId.of(candidate);

        boolean analysing = comparisonAnalysisRunner.isRunning(baselineId, candidateId);
        var latest = comparisonAnalysisRunner.latest(baselineId, candidateId).orElse(null);
        var availability = comparisonAnalysisRunner.availability();

        return new ComparisonAnalysisPanelDto(analysing, latest != null ? toDto(latest) : null,
                new AiAvailabilityDto(availability.available(), availability.problem(), availability.remedy()));
    }

    private ComparisonAnalysisDto toDto(ComparisonAnalysis analysis) {
        return new ComparisonAnalysisDto(
                analysis.conclusion(),
                analysis.findings().stream().map(this::toDto).toList(),
                analysis.missingTelemetry().stream().map(this::toDto).toList(),
                analysis.provenanceIfPresent().map(p -> p.describe()).orElse(null));
    }

    private FindingDto toDto(Finding finding) {
        return new FindingDto(finding.statement(), finding.type().name(), finding.type().label(),
                display.confidenceLabel(finding.confidence()), finding.evidenceIds());
    }

    private MissingTelemetryDto toDto(MissingTelemetry missing) {
        return new MissingTelemetryDto(missing.what(), missing.whyItMatters());
    }
}
