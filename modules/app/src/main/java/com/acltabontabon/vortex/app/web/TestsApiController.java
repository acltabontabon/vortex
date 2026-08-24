package com.acltabontabon.vortex.app.web;

import com.acltabontabon.vortex.app.web.WorkspaceDtos.MixRowDto;
import com.acltabontabon.vortex.app.web.WorkspaceDtos.TestRowDto;
import com.acltabontabon.vortex.core.application.ProjectService;
import com.acltabontabon.vortex.core.calibration.CalibrationPolicy;
import com.acltabontabon.vortex.core.calibration.WorkloadSuggestion;
import com.acltabontabon.vortex.core.calibration.WorkloadSuggestions;
import com.acltabontabon.vortex.core.capacity.ProductionObservation;
import com.acltabontabon.vortex.core.environment.Environment;
import com.acltabontabon.vortex.core.environment.EnvironmentType;
import com.acltabontabon.vortex.core.port.Repositories.ExecutionRepository;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.recommendation.ShapeKind;
import com.acltabontabon.vortex.core.recommendation.ShapeKindClassifier;
import com.acltabontabon.vortex.core.recommendation.WorkloadRecommendation;
import com.acltabontabon.vortex.core.recommendation.WorkloadRecommender;
import com.acltabontabon.vortex.core.safety.ExecutionPolicy;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.shared.WorkloadId;
import com.acltabontabon.vortex.core.threshold.Durations;
import com.acltabontabon.vortex.core.threshold.ThresholdSet;
import com.acltabontabon.vortex.core.workload.LoadShape;
import com.acltabontabon.vortex.core.workload.OperationMix;
import com.acltabontabon.vortex.core.workload.TestType;
import com.acltabontabon.vortex.core.workload.Workload;
import com.acltabontabon.vortex.core.workload.WorkloadModel;
import com.acltabontabon.vortex.core.workload.WorkloadSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final WorkloadRecommender recommender;
    private final ExecutionPolicy executionPolicy;

    public TestsApiController(ProjectService projects, ExecutionRepository executions,
            WorkloadView workloadView, TestDefinitions definitions,
            WorkspaceAssembler assembler, CalibrationPolicy calibration,
            WorkloadRecommender recommender, ExecutionPolicy executionPolicy) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.workloadView = Objects.requireNonNull(workloadView, "workloadView");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.assembler = Objects.requireNonNull(assembler, "assembler");
        this.calibration = Objects.requireNonNull(calibration, "calibration");
        this.recommender = Objects.requireNonNull(recommender, "recommender");
        this.executionPolicy = Objects.requireNonNull(executionPolicy, "executionPolicy");
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
                                op.path(), op.kind() == com.acltabontabon.vortex.core.catalog.OperationKind.MUTATION))
                        .toList())
                .orElseGet(List::of);
    }

    // ---------------------------------------------------------------- reading one test to edit

    public record TestEditDto(String name, String description, String objective, String type,
            String model, Double rate, Integer vus, long durationMinutes, boolean ramping,
            Double peakRate, Integer stages, String singleOperation, Map<String, Integer> weights,
            String shapeKind, List<StageInputDto> explicitStages) {}

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
        List<StageInputDto> explicitStages = ramping
                ? workload.shape().stages().stream()
                        .map(s -> new StageInputDto(s.target().asDouble(), s.duration().toSeconds()))
                        .toList()
                : List.of();

        return new TestEditDto(workload.name(), workload.description(), workload.objective(),
                workload.type().name(), workload.model().name(),
                open ? workload.shape().startLevel().asDouble() : null,
                open ? null : (int) workload.shape().startLevel().asDouble(),
                workload.totalDuration().toMinutes(), ramping,
                ramping ? workload.peakLevel().asDouble() : null,
                ramping ? workload.shape().stages().size() : null,
                singleOperation, definitions.weightsOf(workload),
                ShapeKindClassifier.classify(workload.shape()).name(), explicitStages);
    }

    // ---------------------------------------------------------------- save / duplicate / delete

    public record TestSaveRequest(String name, String originalName, String type, String description,
            String objective, String model, Double rate, Integer vus, Integer durationMinutes,
            Double peakRate, Integer stages, String singleOperation, Map<String, Integer> weights,
            String shapeKind, SpikeParamsDto spikeParams, List<StageInputDto> explicitStages) {}

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
            LoadShape shape = resolveShape(model, request.shapeKind(), request.spikeParams(),
                    request.explicitStages(), request.rate(), request.vus(),
                    request.durationMinutes(), request.peakRate(), request.stages());

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

    // ---------------------------------------------------------------- workload recommendation

    public record StageInputDto(double level, long durationSeconds) {}

    public record SpikeParamsDto(double baseline, double peak, double holdBeforeMinutes,
            double holdAtPeakMinutes) {}

    public record RecommendationDto(
            String type, String model, String shapeKind, String purpose, String headline,
            Double startLevel, Integer durationMinutes, List<StageInputDto> explicitStages,
            boolean productionInformed, boolean safetyCeilingApplied,
            String sourceDescription, String derivation, List<String> availableShapeKinds) {}

    /**
     * What Vortex recommends for a test type, given whatever production observation and safety
     * envelope the service already has — the sole source of "what should this workload look like."
     * The composer renders this; it never invents a rate, duration or stage count itself.
     */
    @GetMapping("/tests/recommendation")
    public RecommendationDto recommendation(@PathVariable String id,
            @RequestParam String type, @RequestParam(required = false) String model) {
        ProjectId projectId = ProjectId.of(id);
        ProjectConfiguration configuration = projects.configuration(projectId);
        TestType testType = TestType.valueOf(type);
        WorkloadModel workloadModel = model == null || model.isBlank()
                ? WorkloadModel.OPEN : WorkloadModel.valueOf(model);

        EnvironmentType environmentType = configuration.environments().stream().findFirst()
                .map(Environment::type).orElse(EnvironmentType.LOCAL_ISOLATED);
        ProductionObservation observation = configuration.productionObservationIfPresent().orElse(null);

        WorkloadRecommendation recommendation = recommender.recommend(testType, workloadModel,
                observation, executionPolicy.limits(), environmentType);

        LoadShape shape = recommendation.shape();
        List<StageInputDto> explicitStages = shape.isRamping()
                ? shape.stages().stream()
                        .map(s -> new StageInputDto(s.target().asDouble(), s.duration().toSeconds()))
                        .toList()
                : List.of();

        return new RecommendationDto(testType.name(), workloadModel.name(),
                recommendation.shapeKind().name(), recommendation.purpose(), recommendation.headline(),
                shape.startLevel().asDouble(), (int) shape.totalDuration().toMinutes(), explicitStages,
                recommendation.isProductionInformed(), recommendation.safetyCeilingApplied(),
                recommendation.source().describe(),
                recommendation.source().derivationIfPresent().orElse(null),
                ShapeKind.relevantFor(testType).stream().map(Enum::name).toList());
    }

    /**
     * The single shape-resolution rule shared by save and preview, in priority order: an explicit
     * stage list (a recommendation's own ramp, untouched since it was applied — may be non-uniform,
     * e.g. capped by a safety ceiling) beats a spike's four parameters, which beats the ordinary
     * equal-spacing ramp/steady builder. Keeping this in one place means save and preview can never
     * describe the same request differently.
     */
    private LoadShape resolveShape(WorkloadModel model, String shapeKind, SpikeParamsDto spikeParams,
            List<StageInputDto> explicitStages, Double rate, Integer vus, Integer durationMinutes,
            Double peakRate, Integer stages) {
        if (explicitStages != null && !explicitStages.isEmpty()) {
            return definitions.explicitShape(model, explicitStages);
        }
        if ("SPIKE".equals(shapeKind) && spikeParams != null) {
            return definitions.spikeShape(model, spikeParams);
        }
        return definitions.shape(model, rate, vus, orDefault(durationMinutes, 10), peakRate,
                orDefault(stages, 4));
    }

    // ---------------------------------------------------------------- live preview

    public record PreviewRequest(String model, Double rate, Integer vus, Integer durationMinutes,
            Double peakRate, Integer stages, String singleOperation, Map<String, Integer> weights,
            String type, String shapeKind, SpikeParamsDto spikeParams,
            List<StageInputDto> explicitStages) {}

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

    public record PreviewResponse(List<MixRowDto> composition, ShapeDto shape, String headline,
            String problem) {}

    /**
     * The per-operation split, the load shape, and its plain-language headline, for whatever is
     * currently in the composer. A server round trip rather than arithmetic in the browser, because
     * the numbers have to be the ones the run will use — {@code RateAllocator} apportions by largest
     * remainder with a floor of one unit per operation and reports the drift it could not avoid, and
     * {@code LoadShape} is the one place stage levels/durations are actually decided; a client
     * reimplementation of either would be close, and close is how a preview starts disagreeing with a
     * result. The headline reuses {@link WorkloadRecommendation#headlineFor} — the recommendation
     * card and the live preview must never disagree about how the same numbers read in English.
     */
    @PostMapping("/tests/preview")
    public PreviewResponse preview(@PathVariable String id, @RequestBody PreviewRequest request) {
        ProjectId projectId = ProjectId.of(id);
        try {
            WorkloadModel model = request.model() == null || request.model().isBlank()
                    ? WorkloadModel.OPEN : WorkloadModel.valueOf(request.model());
            LoadShape shape = resolveShape(model, request.shapeKind(), request.spikeParams(),
                    request.explicitStages(), request.rate(), request.vus(),
                    request.durationMinutes(), request.peakRate(), request.stages());
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

            TestType type = request.type() == null || request.type().isBlank()
                    ? TestType.SMOKE : TestType.valueOf(request.type());
            ShapeKind shapeKind = request.shapeKind() == null || request.shapeKind().isBlank()
                    ? ShapeKind.STEADY : ShapeKind.valueOf(request.shapeKind());
            String headline = WorkloadRecommendation.headlineFor(type, shapeKind, shape, false);

            return new PreviewResponse(assembler.mix(composition), shapeDto, headline, null);
        } catch (IllegalArgumentException e) {
            // An incomplete form is the normal state while somebody is typing, not an error worth
            // shouting about. The preview says what is missing and waits.
            return new PreviewResponse(null, null, null, e.getMessage());
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
            Workload workload = workloadForProposal(proposal, observedMix, configuration, observation);
            updated = updated.withWorkload(workload);
            created.add(workload.name());
        }
        projects.saveConfiguration(projectId, updated);

        return new ApplyProductionResponse(true,
                "Workloads created from your observed production traffic. Each one records how its "
                        + "number was derived and that it came from an observation.",
                List.copyOf(created));
    }

    /**
     * A breakpoint proposal is capped at this environment's configured safety limit — same rule the
     * Composer's own recommendation applies — so bulk-applying never silently saves a ramp above
     * what a fresh recommendation would ever offer. Every other proposal is adopted as
     * {@code CalibrationPolicy} proposed it, unchanged.
     */
    private Workload workloadForProposal(WorkloadSuggestion proposal, OperationMix observedMix,
            ProjectConfiguration configuration, ProductionObservation observation) {
        if (proposal.type() != TestType.BREAKPOINT) {
            return WorkloadSuggestions.toWorkload(proposal, observedMix);
        }

        EnvironmentType environmentType = configuration.environments().stream().findFirst()
                .map(Environment::type).orElse(EnvironmentType.LOCAL_ISOLATED);
        WorkloadRecommendation capped = recommender.recommend(TestType.BREAKPOINT, WorkloadModel.OPEN,
                observation, executionPolicy.limits(), environmentType);

        WorkloadSource source = capped.safetyCeilingApplied()
                ? proposal.source().withDerivation(proposal.derivation()
                        + " Capped at this environment's configured safety limit of "
                        + capped.shape().peakLevel().displayWithUnit() + ".")
                : proposal.source();

        return new Workload(WorkloadId.of(proposal.name()), proposal.name(), proposal.description(),
                "", TestType.BREAKPOINT, observedMix, capped.shape(), ThresholdSet.empty(), source,
                Map.of());
    }
}
