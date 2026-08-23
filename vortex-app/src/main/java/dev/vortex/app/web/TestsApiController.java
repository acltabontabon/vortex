package dev.vortex.app.web;

import dev.vortex.app.web.WorkspaceDtos.MixRowDto;
import dev.vortex.app.web.WorkspaceDtos.TestRowDto;
import dev.vortex.core.application.ProjectService;
import dev.vortex.core.calibration.CalibrationPolicy;
import dev.vortex.core.calibration.WorkloadSuggestions;
import dev.vortex.core.port.Repositories.ExecutionRepository;
import dev.vortex.core.project.ProjectConfiguration;
import dev.vortex.core.shared.ProjectId;
import dev.vortex.core.shared.WorkloadId;
import dev.vortex.core.threshold.Durations;
import dev.vortex.core.threshold.ThresholdSet;
import dev.vortex.core.workload.LoadShape;
import dev.vortex.core.workload.OperationMix;
import dev.vortex.core.workload.TestType;
import dev.vortex.core.workload.Workload;
import dev.vortex.core.workload.WorkloadModel;
import dev.vortex.core.workload.WorkloadSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The life of a test: create one, change it, copy it, remove it, preview its traffic split.
 *
 * <p>Consolidates what were three Thymeleaf pages (Traffic's list/detail, the workload editor, and
 * Evaluate's question-resolution screen) behind the test inventory (now Overview's own Tests
 * section — see {@code OverviewPage.tsx}) and its editor ({@code TestEditorPage.tsx}). Traffic
 * and Evaluate named what Vortex stores and asked the reader to learn its ontology first; a
 * workload's duplicate/delete actions are just facts about one test, shown where the test already
 * is.
 */
@RestController
@RequestMapping("/api/services/{id}")
public class TestsApiController {

    private final ProjectService projects;
    private final ExecutionRepository executions;
    private final WorkloadView workloadView;
    private final TestDefinitions definitions;
    private final WorkspaceAssembler assembler;
    private final CalibrationPolicy calibration;

    public TestsApiController(ProjectService projects, ExecutionRepository executions,
            WorkloadView workloadView, TestDefinitions definitions,
            WorkspaceAssembler assembler, CalibrationPolicy calibration) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.workloadView = Objects.requireNonNull(workloadView, "workloadView");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.calibration = Objects.requireNonNull(calibration, "calibration");
    }

    // ---------------------------------------------------------------- the operations catalog

    public record OperationDto(String id, String label, String method, String path, boolean mutating) {}

    /**
     * Every operation the editor can build a test's traffic from — independent of any one test,
     * since a new test has no composition yet to read this from.
     */
    @GetMapping("/catalog/operations")
    public List<OperationDto> catalogOperations(@PathVariable String id) {
        ProjectId projectId = ProjectId.of(id);
        return projects.catalog(projectId).map(catalog -> catalog.operations().stream()
                        .map(op -> new OperationDto(op.id().value(), op.label(), op.method().name(),
                                op.path(), op.kind() == dev.vortex.core.catalog.OperationKind.MUTATION))
                        .toList())
                .orElseGet(List::of);
    }

    // ---------------------------------------------------------------- reading one test to edit

    public record TestEditDto(String name, String description, String objective, String type,
            String model, Double rate, Integer vus, long durationMinutes, boolean ramping,
            Double peakRate, Integer stages, String singleOperation, Map<String, Integer> weights) {}

    /**
     * What the editor needs to prefill a form — the raw, editable numbers behind a test, distinct
     * from {@link WorkspaceDtos.TestRowDto}'s already-formatted display strings.
     *
     * <p>{@code rate}/{@code vus} always carry {@link LoadShape#startLevel()}, never
     * {@link LoadShape#peakLevel()} — for a constant shape the two are the same value, but for a
     * ramping one only the start level is what the "Requests per second" field means. Reporting the
     * peak there instead would silently reopen a ramping test as if it were flat: exactly the bug
     * that once made saving an edited breakpoint test discard its stages.
     */
    @GetMapping("/tests/{name}")
    public TestEditDto editTest(@PathVariable String id, @PathVariable String name) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);
        Workload workload = configuration.workloadByName(name).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No test named '" + name + "'"));

        boolean open = workload.model() == WorkloadModel.OPEN;
        boolean ramping = workload.shape().isRamping();
        String singleOperation = workload.operations().isSingleOperation()
                ? workload.operations().operationIds().getFirst().value() : null;

        return new TestEditDto(workload.name(), workload.description(), workload.objective(),
                workload.type().name(), workload.model().name(),
                open ? workload.shape().startLevel().asDouble() : null,
                open ? null : (int) workload.shape().startLevel().asDouble(),
                workload.totalDuration().toMinutes(), ramping,
                ramping ? workload.peakLevel().asDouble() : null,
                ramping ? workload.shape().stages().size() : null,
                singleOperation, definitions.weightsOf(workload));
    }

    // ---------------------------------------------------------------- save / duplicate / delete

    public record TestSaveRequest(String name, String originalName, String type, String description,
            String objective, String model, Double rate, Integer vus, Integer durationMinutes,
            Double peakRate, Integer stages, String singleOperation, Map<String, Integer> weights) {}

    public record TestSaveResponse(String name) {}

    /**
     * Saves a new test, or replaces an existing one. Mirrors the Thymeleaf-era
     * {@code WorkloadController.save} exactly, including that a rename is a delete-then-add (the
     * name is the key) while run history is unaffected — experiment identity deliberately excludes
     * the workload's name (ADR-027).
     */
    @PostMapping("/tests")
    public TestSaveResponse save(@PathVariable String id, @RequestBody TestSaveRequest request) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);
        String originalName = request.originalName();

        try {
            TestType type = TestType.valueOf(request.type());
            WorkloadModel model = request.model() == null || request.model().isBlank()
                    ? WorkloadModel.OPEN : WorkloadModel.valueOf(request.model());
            String slug = definitions.slug(request.name());
            OperationMix mix = definitions.mix(model, request.singleOperation(),
                    request.weights() == null ? Map.of() : request.weights());
            LoadShape shape = definitions.shape(model, request.rate(), request.vus(),
                    orDefault(request.durationMinutes(), 10), request.peakRate(),
                    orDefault(request.stages(), 4));

            String existingKey = originalName == null || originalName.isBlank() ? slug : originalName;
            ThresholdSet existing = configuration.workloadByName(existingKey)
                    .map(Workload::thresholds).orElseGet(ThresholdSet::empty);
            WorkloadSource source = configuration.workloadByName(existingKey)
                    .map(Workload::source).orElseGet(WorkloadSource::manual);

            Workload workload = new Workload(WorkloadId.of(slug), slug,
                    request.description() == null ? "" : request.description(),
                    request.objective() == null ? "" : request.objective(),
                    type, mix, shape, existing, source, Map.of());

            ProjectConfiguration updated = configuration;
            if (originalName != null && !originalName.isBlank()
                    && !originalName.equalsIgnoreCase(slug)) {
                updated = updated.withoutWorkload(originalName);
            }
            projects.saveConfiguration(projectId, updated.withWorkload(workload));
            return new TestSaveResponse(slug);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    /** Copies a test under a new name, so a second workload can start from one that already works. */
    @PostMapping("/tests/{name}/duplicate")
    public TestSaveResponse duplicate(@PathVariable String id, @PathVariable String name) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);

        Workload source = configuration.workloadByName(name).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No test named '" + name + "'"));

        String copy = definitions.availableName(configuration, source.name());
        Workload duplicated = new Workload(WorkloadId.of(copy), copy,
                source.description(), source.objective(), source.type(), source.operations(),
                source.shape(), source.thresholds(),
                // The copy is not the observation: carrying "observed in production" onto a workload
                // somebody is about to change by hand would let an invented number inherit evidence
                // it has no claim to.
                WorkloadSource.manual(), source.k6Options());

        projects.saveConfiguration(projectId, configuration.withWorkload(duplicated));
        return new TestSaveResponse(copy);
    }

    /**
     * Removes a test. A run stores the plan it executed, so deleting the definition it came from
     * cannot change what that run reports.
     */
    @PostMapping("/tests/{name}/delete")
    public void delete(@PathVariable String id, @PathVariable String name) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);

        if (configuration.workloadByName(name).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No test named '" + name + "'");
        }
        projects.saveConfiguration(projectId, configuration.withoutWorkload(name));
    }

    // ---------------------------------------------------------------- live preview

    public record PreviewRequest(String model, Double rate, Integer vus, Integer durationMinutes,
            Double peakRate, Integer stages, String singleOperation, Map<String, Integer> weights) {}

    public record StageDto(double levelValue, String levelDisplay, long durationMillis,
            String durationDisplay) {}

    /**
     * The load shape's real quantities — a level and a duration per stage — not pixel geometry.
     * The browser turns these into a chart's x/y coordinates itself (trivial proportional layout,
     * not business arithmetic); this DTO stays in the same domain terms {@code LoadShape} already
     * uses, so it means the same thing to any future caller, chart or otherwise. Milliseconds, not
     * seconds: {@code LoadShapeDurations} enforces only positive and at most 24 hours, with no
     * minimum-granularity floor, so a stage is not guaranteed to be a whole number of seconds.
     */
    public record ShapeDto(String unit, boolean ramping, double peakLevelValue,
            String peakLevelDisplay, long totalDurationMillis, List<StageDto> stages) {}

    public record PreviewResponse(List<MixRowDto> composition, ShapeDto shape, String problem) {}

    /**
     * The per-operation split, and the load shape, for whatever is currently in the composer. A
     * server round trip rather than arithmetic in the browser, because the numbers have to be the
     * ones the run will use — {@code RateAllocator} apportions by largest remainder with a floor of
     * one unit per operation and reports the drift it could not avoid, and {@code LoadShape} is the
     * one place stage levels/durations are actually decided; a client reimplementation of either
     * would be close, and close is how a preview starts disagreeing with a result.
     */
    @PostMapping("/tests/preview")
    public PreviewResponse preview(@PathVariable String id, @RequestBody PreviewRequest request) {
        ProjectId projectId = ProjectId.of(id);
        try {
            WorkloadModel model = request.model() == null || request.model().isBlank()
                    ? WorkloadModel.OPEN : WorkloadModel.valueOf(request.model());
            LoadShape shape = definitions.shape(model, request.rate(), request.vus(),
                    orDefault(request.durationMinutes(), 10), request.peakRate(),
                    orDefault(request.stages(), 4));
            Workload provisional = new Workload(WorkloadId.of("preview"), "preview", "", "",
                    TestType.SMOKE,
                    definitions.mix(model, request.singleOperation(),
                            request.weights() == null ? Map.of() : request.weights()),
                    shape, ThresholdSet.empty(), WorkloadSource.manual(), Map.of());

            var composition = workloadView.compose(provisional, projects.catalog(projectId).orElse(null));
            List<StageDto> stages = shape.stages().stream()
                    .map(stage -> new StageDto(stage.target().asDouble(), stage.target().displayWithUnit(),
                            stage.duration().toMillis(), Durations.display(stage.duration())))
                    .toList();
            ShapeDto shapeDto = new ShapeDto(model.controlledUnit(), shape.isRamping(),
                    shape.peakLevel().asDouble(), shape.peakLevel().displayWithUnit(),
                    shape.totalDuration().toMillis(), stages);
            return new PreviewResponse(assembler.mix(composition), shapeDto, null);
        } catch (IllegalArgumentException e) {
            // An incomplete form is the normal state while somebody is typing, not an error worth
            // shouting about. The preview says what is missing and waits.
            return new PreviewResponse(null, null, e.getMessage());
        }
    }

    private int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    // ---------------------------------------------------------------- apply proposed workloads

    public record ApplyProductionResponse(boolean applied, String message,
            List<String> createdNames) {}

    /**
     * Turns the domain's calculated workload suggestions into real, editable workloads. Mirrors the
     * Thymeleaf-era {@code ProjectController.applyCalibration} exactly — needs the observed
     * <em>composition</em> as well as the observed volume, since a rate on its own does not say what
     * it consists of, and Vortex will not invent the distribution.
     */
    @PostMapping("/production/apply")
    public ApplyProductionResponse applyProduction(@PathVariable String id) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);

        var observation = configuration.productionObservationIfPresent().orElse(null);
        if (observation == null) {
            return new ApplyProductionResponse(false,
                    "Record your observed production traffic first.", List.of());
        }

        var observedMix = observation.observedMixIfPresent().orElse(null);
        if (observedMix == null) {
            return new ApplyProductionResponse(false,
                    "Vortex knows how much traffic your service receives but not how it is "
                            + "distributed across operations, and it will not guess — a workload "
                            + "built on an invented mix would look production-informed without "
                            + "being it. Add an observed mix to the production section, or define a "
                            + "workload by hand.",
                    List.of());
        }

        var thinCoverage = observation.mixCoverageIfPresent()
                .filter(coverage -> !coverage.isRepresentative())
                .orElse(null);
        if (thinCoverage != null) {
            return new ApplyProductionResponse(false,
                    thinCoverage.describe() + " That is too little to build workloads from: they "
                            + "would carry the authority of production evidence while describing "
                            + "only part of it. Import the operations Vortex could not attribute "
                            + "traffic to, or narrow the observation to the part of the service you "
                            + "mean to test.",
                    List.of());
        }

        ProjectConfiguration updated = configuration;
        List<String> created = new java.util.ArrayList<>();
        for (var proposal : calibration.propose(observation)) {
            Workload workload = WorkloadSuggestions.toWorkload(proposal, observedMix);
            updated = updated.withWorkload(workload);
            created.add(workload.name());
        }
        projects.saveConfiguration(projectId, updated);

        return new ApplyProductionResponse(true,
                "Workloads created from your observed production traffic. Each one records how its "
                        + "number was derived and that it came from an observation.",
                List.copyOf(created));
    }
}
