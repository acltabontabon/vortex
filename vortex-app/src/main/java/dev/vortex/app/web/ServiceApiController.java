package dev.vortex.app.web;

import dev.vortex.app.web.WorkspaceDtos.EvidenceDto;
import dev.vortex.app.web.WorkspaceDtos.OverviewDto;
import dev.vortex.app.web.WorkspaceDtos.RunSummaryDto;
import dev.vortex.app.web.WorkspaceDtos.RunsDto;
import dev.vortex.app.web.WorkspaceDtos.ServiceHeaderDto;
import dev.vortex.app.web.WorkspaceDtos.TestRowDto;
import dev.vortex.app.web.WorkspaceDtos.TestsDto;
import dev.vortex.core.capacity.CapacityObservation;
import dev.vortex.core.capacity.HeadroomCalculator;
import dev.vortex.core.capacity.ProductionObservation;
import dev.vortex.core.environment.Environment;
import dev.vortex.core.evidence.CapacityRange;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.port.Repositories.ExecutionRepository;
import dev.vortex.core.project.ProjectConfiguration;
import dev.vortex.core.shared.ProjectId;
import dev.vortex.core.threshold.Threshold;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * One service, as the workbench reads it.
 *
 * <p>Three reads, matching the three things a screen needs rather than the tables underneath: the
 * chrome that every tab shows, the operational summary Overview opens with, and the inventory of
 * things that can actually be executed. Each is one request, because a screen assembled from four
 * round trips can show four moments and disagree with itself about which run was the latest.
 *
 * <p>Nothing here decides anything. Readiness, runnability, comparability, provenance, capacity and
 * every refusal arrive already decided from {@code vortex-core}; this controller chooses a wire
 * shape for them and nothing else.
 */
@RestController
@RequestMapping("/api/services/{id}")
public class ServiceApiController {

    private final WorkspaceAssembler assembler;
    private final ExecutionRepository executions;
    private final dev.vortex.core.application.CapacityService capacity;

    public ServiceApiController(WorkspaceAssembler assembler, ExecutionRepository executions,
            dev.vortex.core.application.CapacityService capacity) {
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.capacity = Objects.requireNonNull(capacity, "capacity");
    }

    /** The workspace chrome: who this service is, where it points, and whether it can run. */
    @GetMapping
    public ServiceHeaderDto header(@PathVariable String id) {
        WorkspaceAssembler.Context context = context(id);
        return header(context);
    }

    /**
     * The operational landing page.
     *
     * <p>Ordered as somebody arriving at an unfamiliar service reads it: what production sends this
     * thing, what it is expected to achieve, what has been established about it, what happened last
     * — and then the tests, which are the reason anyone came.
     */
    @GetMapping("/overview")
    public OverviewDto overview(@PathVariable String id) {
        WorkspaceAssembler.Context context = context(id);
        ProjectConfiguration configuration = context.configuration();

        TestExecution latest = context.latestTerminal();

        List<RunSummaryDto> recentRuns = context.recent(assembler.overviewRunLimit()).stream()
                .map(execution -> assembler.runSummary(execution, null))
                .toList();

        // Each test's own evidence, not just the service-wide reading above — a service showing more
        // than one test must never leave a reader guessing which test a figure belongs to.
        Map<String, CapacityObservation> capacityByWorkload =
                capacity.latestPerWorkload(ProjectId.of(id));

        return new OverviewDto(
                header(context),
                assembler.production(context.production(), context.catalog()),
                objectives(configuration),
                assembler.capacity(context.observation(), context.headroom()),
                assembler.range(context.range()),
                latest == null ? null : assembler.runSummary(latest, null),
                assembler.tests(context.project(), configuration, context.catalog(),
                        context.history(), capacityByWorkload, context.production()),
                recentRuns,
                // The domain's own next step, not an invented one: a service that has never run
                // anything is the one case where Vortex has a specific first test to suggest.
                !context.readiness().testExecuted() && context.readiness().canRun(),
                context.evidencePredatesRelease(),
                context.evidencePredatesRelease()
                        ? Display.releaseGapText(context.observation().serviceVersion(),
                                configuration.serviceVersion())
                        : null);
    }

    /** Recent runs shown on Overview are capped tighter than a page dedicated to history. */
    private static final int RUNS_PAGE_LIMIT = 200;

    /**
     * Every execution recorded for this service, newest first, each aware of whether its test still
     * describes the same experiment.
     *
     * <p>Fetched at its own limit rather than reusing the header's history read, which is capped at
     * {@link WorkspaceAssembler#RECENT_RUN_LIMIT} for screens that only need the last few — a page
     * whose entire purpose is history should not silently truncate at that number.
     */
    @GetMapping("/runs")
    public RunsDto runs(@PathVariable String id) {
        WorkspaceAssembler.Context context = context(id);
        ProjectId projectId = ProjectId.of(id);
        List<TestExecution> history = executions.findByProject(projectId, RUNS_PAGE_LIMIT).stream()
                .filter(execution -> execution.state().isTerminal())
                .toList();
        List<RunSummaryDto> runs = assembler.runSummaries(projectId, context.configuration(),
                history);
        return new RunsDto(header(context), runs);
    }

    /**
     * What Vortex has established: the conclusion first, the conditions it holds under, its history
     * across releases, and the runs behind it.
     */
    @GetMapping("/evidence")
    public EvidenceDto evidence(@PathVariable String id) {
        WorkspaceAssembler.Context context = context(id);
        ProjectConfiguration configuration = context.configuration();

        java.util.SequencedMap<String, List<CapacityObservation>> byVersion =
                capacity.historyByVersion(ProjectId.of(id));
        ProductionObservation production = context.production();

        return new EvidenceDto(
                header(context),
                assembler.capacity(context.observation(), context.headroom()),
                assembler.range(context.range()),
                assembler.headroomLabel(context.headroom()),
                assembler.production(production, context.catalog()),
                context.evidencePredatesRelease(),
                assembler.capacityHistory(byVersion, production, configuration.serviceVersion()),
                assembler.runSummaries(ProjectId.of(id), configuration,
                        context.history().stream()
                                .filter(execution -> execution.state().isTerminal())
                                .toList()));
    }

    /** Everything this service can execute, plus the questions a new one could answer. */
    @GetMapping("/tests")
    public TestsDto tests(@PathVariable String id) {
        WorkspaceAssembler.Context context = context(id);
        ProjectConfiguration configuration = context.configuration();

        List<TestRowDto> tests = assembler.tests(context.project(), configuration,
                context.catalog(), context.history());

        return new TestsDto(header(context), tests, assembler.testTypes(configuration),
                configuration.environments().stream().map(Environment::name).toList());
    }

    // ---------------------------------------------------------------- helpers

    private ServiceHeaderDto header(WorkspaceAssembler.Context context) {
        return assembler.header(context.project(), context.configuration(), context.catalog(),
                context.readiness(), context.runCount(), context.running());
    }

    /**
     * The objectives, in the domain's own phrasing.
     *
     * <p>Empty is a real answer and is left empty rather than filled with a placeholder: a run
     * without objectives still happens, and what changes is that its result is {@code Not
     * evaluated}, which the screens say for themselves.
     */
    private List<String> objectives(ProjectConfiguration configuration) {
        return configuration.thresholds().thresholds().stream()
                .map(Threshold::describe)
                .toList();
    }

    private WorkspaceAssembler.Context context(String id) {
        try {
            return assembler.context(ProjectId.of(id), executions);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }
}
