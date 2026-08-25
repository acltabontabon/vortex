package com.acltabontabon.vortex.app.web;

import com.acltabontabon.vortex.core.application.CapacityService;
import com.acltabontabon.vortex.core.application.ProjectService;
import com.acltabontabon.vortex.core.capacity.CapacityObservation;
import com.acltabontabon.vortex.core.capacity.HeadroomCalculator;
import com.acltabontabon.vortex.core.capacity.ProductionObservation;
import com.acltabontabon.vortex.core.evidence.CapacityRange;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.port.Repositories.ExecutionRepository;
import com.acltabontabon.vortex.core.project.Project;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.project.ProjectReadiness;
import com.acltabontabon.vortex.core.threshold.Durations;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The homepage's data, as JSON.
 *
 * <p>This is the same read {@code HomeController} used to perform for the Thymeleaf homepage,
 * moved to serve the React one instead — same collaborators, same scan-once-per-request shape,
 * same domain calls. Only the shape at the edge changed: explicit DTOs instead of a Thymeleaf
 * model, so the wire contract is something chosen on purpose rather than whatever the domain
 * objects happened to serialize to.
 */
@RestController
@RequestMapping("/api/home")
public class HomeApiController {

    /** Enough history to find each service's latest run without reading the whole table. */
    private static final int SCAN_LIMIT = 200;

    private final ProjectService projects;
    private final ExecutionRepository executions;
    private final CapacityService capacity;
    private final Display display;

    public HomeApiController(ProjectService projects, ExecutionRepository executions,
            CapacityService capacity, Display display) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.capacity = Objects.requireNonNull(capacity, "capacity");
        this.display = Objects.requireNonNull(display, "display");
    }

    // ---------------------------------------------------------------- wire contract

    public record HomeResponse(List<ServiceCardDto> cards) {}

    public record RunRefDto(String id, String testTypeLabel, String stateLabel) {}

    public record VerdictDto(String verdict, String verdictLabel, String testTypeLabel,
            String answer, String runId, String relativeTime, String isoTimestamp, String p95) {}

    public record RangeMarkerDto(String kind, String label, String displayWithUnit) {}

    /**
     * One configured workload, as much of it as the homepage needs to resolve an intent against.
     *
     * <p>Deliberately not a {@code TestRowDto}. That record carries per-test runnability, which
     * costs a plan resolution — and, through {@code TestRunner.resolve}, a load-generator version
     * subprocess — for every workload it describes. Affordable once for one service's workspace;
     * not N services × M workloads on every homepage load. Whether a particular test can run right
     * now is preflight's own question, and preflight is one click away and answers it itself.
     *
     * @param testType the {@code TestType} enum name — stable, unlike {@code testTypeLabel}, so a
     *                 caller keying behaviour off which kind of test this is keys off an identifier
     *                 rather than prose somebody will reword
     */
    public record WorkloadRefDto(String name, String testType, String testTypeLabel,
            boolean productionInformed) {}

    /**
     * @param nextStepText              the domain's own instruction for the single most useful
     *                                   thing to do next, or null when the checklist is complete
     * @param workloadTestTypeLabel     the question the first configured workload was set up to
     *                                  answer (e.g. "Breakpoint"), or null when none is configured.
     *                                  A deliberately weaker claim than {@code workloads} below —
     *                                  it describes one workload, not the set
     * @param workloadProductionInformed whether that workload derives from observed production
     *                                   traffic rather than being hand-authored; null alongside
     *                                   {@code workloadTestTypeLabel}
     * @param workloads                 every configured workload, so a caller can ask "is there a
     *                                  breakpoint test here?" rather than guessing from the first
     * @param apiImported               whether an operation catalog exists — without one there is
     *                                  nothing to build a workload's operation mix from
     * @param objectivesConfigured      whether thresholds exist. Not a blocker by design (see
     *                                  {@code ProjectReadiness.Kind.EVALUATION}): a run without
     *                                  them still measures everything, it just decides nothing
     * @param productionObserved        whether observed production traffic has been recorded
     * @param recentTerminalRunCount    finished runs for this service <em>within the homepage's own
     *                                  recent scan</em> — a lower bound, never a claim about total
     *                                  history, since the scan is the most recent {@value
     *                                  #SCAN_LIMIT} runs across every service
     * @param updatedAtRelative         when the service's own record last changed. Coarser than a
     *                                  setup-editing timestamp — nothing in the domain tracks that —
     *                                  but real: creation or a metadata edit, not fabricated
     * @param updatedAtIso              {@code updatedAtRelative}, machine-readable — lets the client
     *                                  rank services by actual recency rather than parsing prose
     * @param headroomDisplay           the tested-capacity-over-production-peak multiple (e.g.
     *                                  "1.5×"), only when the two are commensurable; null otherwise
     */
    public record ServiceCardDto(String id, String name, String description,
            boolean running, RunRefDto runningRun, boolean canRun, List<String> blockers,
            String nextStepText, String workloadTestTypeLabel, Boolean workloadProductionInformed,
            List<WorkloadRefDto> workloads, boolean apiImported, boolean objectivesConfigured,
            boolean productionObserved, int recentTerminalRunCount,
            String updatedAtRelative, String updatedAtIso, String headroomDisplay,
            VerdictDto latestVerdict, List<RangeMarkerDto> rangeMarkers,
            int satisfiedCount, int totalCount,
            boolean evidencePredatesRelease, String releaseGapText) {}

    @GetMapping
    public HomeResponse home() {
        List<Project> allProjects = projects.all();
        List<TestExecution> scanned = executions.findRecent(SCAN_LIMIT);

        List<ServiceCard> cards = new ArrayList<>(allProjects.size());
        for (Project project : allProjects) {
            cards.add(cardFor(project, scanned));
        }

        return new HomeResponse(cards.stream().map(this::toDto).toList());
    }

    // ---------------------------------------------------------------- intermediate computation
    //
    // Unchanged from HomeController: one scan for every service's history rather than a query per
    // service, and readiness derived from data already in hand rather than re-read. See that
    // class's history for why this N+1-avoiding shape is deliberate for a local, single-user tool.

    private record ServiceCard(Project project, ProjectConfiguration configuration,
            ProjectReadiness readiness, CapacityObservation capacity,
            HeadroomCalculator.Result headroom, CapacityRange range,
            TestExecution latest, TestExecution running, int terminalRunCount) {

        boolean hasEvidence() {
            return latest != null;
        }

        boolean isRunning() {
            return running != null;
        }

        boolean canRun() {
            return readiness.canRun();
        }

        boolean hasRange() {
            return range != null && range.isRenderable();
        }

        boolean evidencePredatesRelease() {
            if (capacity == null || configuration == null) {
                return false;
            }
            String measured = capacity.serviceVersion();
            String current = configuration.serviceVersion();
            return measured != null && !measured.isBlank()
                    && current != null && !current.isBlank()
                    && !measured.equals(current);
        }
    }

    private ServiceCard cardFor(Project project, List<TestExecution> scanned) {
        List<TestExecution> mine = scanned.stream()
                .filter(execution -> execution.projectId().equals(project.id()))
                .sorted(Comparator.comparing(TestExecution::requestedAt).reversed())
                .toList();

        List<TestExecution> terminal = mine.stream()
                .filter(execution -> execution.state().isTerminal())
                .toList();

        Optional<TestExecution> latest = terminal.stream().findFirst();
        Optional<TestExecution> running = mine.stream()
                .filter(execution -> !execution.state().isTerminal())
                .findFirst();

        ProjectConfiguration configuration = projects.configuration(project.id());

        boolean catalogImported = projects.catalog(project.id())
                .map(catalog -> !catalog.isEmpty())
                .orElse(false);
        ProjectReadiness readiness = configuration.readiness(catalogImported, !mine.isEmpty());

        ProductionObservation production =
                configuration.productionObservationIfPresent().orElse(null);
        CapacityObservation observation = capacity.latest(project.id()).orElse(null);

        HeadroomCalculator.Result headroom = capacity.headroom(observation, production);

        CapacityRange range = observation != null
                ? CapacityRange.from(observation, production)
                : CapacityRange.productionOnly(production);

        return new ServiceCard(project, configuration, readiness, observation, headroom, range,
                latest.orElse(null), running.orElse(null), terminal.size());
    }

    // ---------------------------------------------------------------- DTO mapping

    private ServiceCardDto toDto(ServiceCard card) {
        RunRefDto runningRun = card.isRunning()
                ? new RunRefDto(card.running().id().value(),
                        card.running().plan().testType().label(), card.running().state().label())
                : null;

        VerdictDto latestVerdict = card.hasEvidence() ? toDto(card.latest()) : null;

        List<RangeMarkerDto> rangeMarkers = card.hasRange()
                ? card.range().markers().stream()
                        .map(marker -> new RangeMarkerDto(marker.kind().name(), marker.label(),
                                marker.level().displayWithUnit()))
                        .toList()
                : List.of();

        String releaseGapText = card.evidencePredatesRelease()
                ? Display.releaseGapText(card.capacity().serviceVersion(),
                        card.configuration().serviceVersion())
                : null;

        // Shortened for a compact row; the full instruction is what the setup checklist itself shows.
        String nextStepText = card.readiness().nextAction()
                .map(next -> display.shorten(next.nextStep(), 64))
                .orElse(null);

        List<WorkloadRefDto> workloads = card.configuration().workloads().stream()
                .map(workload -> new WorkloadRefDto(workload.name(), workload.type().name(),
                        workload.type().label(), workload.source().isProductionInformed()))
                .toList();

        var primaryWorkload = card.configuration().workloads().stream().findFirst();
        String workloadTestTypeLabel = primaryWorkload.map(w -> w.type().label()).orElse(null);
        Boolean workloadProductionInformed = primaryWorkload
                .map(w -> w.source().isProductionInformed()).orElse(null);

        String headroomDisplay = card.headroom().isAvailable()
                ? card.headroom().value().map(com.acltabontabon.vortex.core.capacity.Headroom::display).orElse(null)
                : null;

        return new ServiceCardDto(
                card.project().id().value(), card.project().name(), card.project().description(),
                card.isRunning(), runningRun,
                card.canRun(), card.readiness().blockers().stream().map(ProjectReadiness.Item::label).toList(),
                nextStepText, workloadTestTypeLabel, workloadProductionInformed,
                workloads, card.readiness().apiImported(),
                card.readiness().thresholdsConfigured(), card.readiness().productionObserved(),
                card.terminalRunCount(),
                display.relative(card.project().updatedAt()),
                DateTimeFormatter.ISO_INSTANT.format(card.project().updatedAt()), headroomDisplay,
                latestVerdict, rangeMarkers,
                (int) card.readiness().satisfiedCount(), card.readiness().totalCount(),
                card.evidencePredatesRelease(), releaseGapText);
    }

    private VerdictDto toDto(TestExecution execution) {
        String answer = execution.summaryIfPresent().isPresent()
                ? execution.summary().answer()
                : execution.plan().testType().label() + " · " + execution.state().label();
        String p95 = execution.resultsIfPresent()
                .flatMap(results -> results.latency().p95())
                .map(Durations::display)
                .orElse(null);
        return new VerdictDto(execution.verdict().name(), display.verdictLabel(execution.verdict()),
                execution.plan().testType().label(), answer, execution.id().value(),
                display.relative(execution.requestedAt()),
                DateTimeFormatter.ISO_INSTANT.format(execution.requestedAt()), p95);
    }
}
