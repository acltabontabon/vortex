package dev.vortex.core.application;

import dev.vortex.core.catalog.Operation;
import dev.vortex.core.catalog.OperationBinding;
import dev.vortex.core.catalog.PayloadProvenance;
import dev.vortex.core.catalog.ServiceCatalog;
import dev.vortex.core.data.Dataset;
import dev.vortex.core.data.DatasetException;
import dev.vortex.core.data.DatasetHome;
import dev.vortex.core.data.DatasetRef;
import dev.vortex.core.data.DatasetValue;
import dev.vortex.core.data.RequestData;
import dev.vortex.core.environment.Environment;
import dev.vortex.core.intent.TestIntent;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.OperationKeys;
import dev.vortex.core.plan.PlannedDataset;
import dev.vortex.core.plan.PlannedOperation;
import dev.vortex.core.plan.RunnerKind;
import dev.vortex.core.plan.SafetyDecision;
import dev.vortex.core.plan.ScriptSource;
import dev.vortex.core.project.Project;
import dev.vortex.core.port.DatasetStore;
import dev.vortex.core.project.ProjectConfiguration;
import dev.vortex.core.workload.Workload;
import dev.vortex.core.shared.OperationId;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.shared.TestPlanId;
import dev.vortex.core.threshold.ThresholdSet;
import dev.vortex.core.workload.AllocatedRate;
import dev.vortex.core.workload.RateAllocation;
import dev.vortex.core.workload.RateAllocator;
import dev.vortex.core.workload.WorkloadModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Turns user intent into an executable plan.
 *
 * <p>This is where {@code vortex.yaml} — what somebody asked for — becomes an
 * {@link EffectiveTestPlan}: what will actually happen. Defaults are applied, the environment is
 * resolved, the total arrival rate is divided across the operation mix, the runner is chosen, each
 * operation is copied into the plan so it is self-contained, and every operation is assigned the k6
 * workload key its measurements will later be attributed through.
 *
 * <p>Resolution is strictly one-directional. Nothing computed here is written back into the user's
 * configuration: the allocated 18/30/66/6 split belongs to this run, while the configuration
 * continues to say "120/sec, 15/25/55/5", which is the thing a human wrote and will want to edit
 * next week.
 *
 * <p>The plan is deliberately a snapshot rather than a set of references. A report opened in six
 * months must describe the test that ran, not the test today's configuration would produce.
 */
public final class PlanResolver {

    private final RateAllocator rateAllocator;
    private final RequestDataResolver requestDataResolver;
    private final DatasetStore datasets;

    public PlanResolver(RateAllocator rateAllocator, RequestDataResolver requestDataResolver,
            DatasetStore datasets) {
        this.rateAllocator = Objects.requireNonNull(rateAllocator, "rateAllocator");
        this.requestDataResolver = Objects.requireNonNull(requestDataResolver, "requestDataResolver");
        this.datasets = Objects.requireNonNull(datasets, "datasets");
    }

    /**
     * What the caller must supply beyond the project's own configuration.
     *
     * @param serviceVersion the release under test, overriding whatever the configuration says.
     *                       Blank means "use the configured value" rather than "no version" — a
     *                       caller that has nothing to say about the release should say nothing.
     */
    public record ResolutionRequest(
            String workloadName,
            String environmentName,
            TestIntent intent,
            RunnerKind runner,
            ScriptSource scriptSource,
            List<SafetyDecision> safetyDecisions,
            TargetRewrite targetRewrite,
            String serviceVersion) {

        public ResolutionRequest {
            safetyDecisions = safetyDecisions == null ? List.of() : List.copyOf(safetyDecisions);
            runner = runner == null ? RunnerKind.LOCAL_BINARY : runner;
            scriptSource = scriptSource == null ? ScriptSource.GENERATED : scriptSource;
            serviceVersion = serviceVersion == null ? "" : serviceVersion.trim();
        }
    }

    /**
     * An adjustment to the target that the runner requires.
     *
     * <p>Always surfaced in preflight rather than applied silently: a user who configured
     * {@code http://localhost:8080} and whose traffic actually went to
     * {@code http://host.docker.internal:8080} deserves to be told, because the two are not the same
     * address and one of them may not resolve.
     */
    public record TargetRewrite(String newHost, String reason) {
    }

    public EffectiveTestPlan resolve(Project project, ProjectConfiguration configuration,
            ServiceCatalog catalog, ResolutionRequest request) {

        List<String> problems = new ArrayList<>();

        Workload workload = configuration.workloadByName(request.workloadName()).orElse(null);
        if (workload == null) {
            problems.add("No workload named '" + request.workloadName() + "' is configured. "
                    + (configuration.workloads().isEmpty()
                    ? "This project has no workloads yet."
                    : "Available workloads: " + configuration.workloads().stream()
                            .map(Workload::name).toList()));
        }

        Environment environment = configuration.environmentByName(request.environmentName())
                .orElse(null);
        if (environment == null) {
            problems.add("No environment named '" + request.environmentName() + "' is configured. "
                    + (configuration.environments().isEmpty()
                    ? "This project has no environments yet."
                    : "Available environments: " + configuration.environments().stream()
                            .map(Environment::name).toList()));
        }

        if (!problems.isEmpty()) {
            throw new PlanResolutionException(
                    "This project's configuration cannot produce a runnable test yet.", problems);
        }

        // Keys are assigned once, here, and carried on the plan. Everything downstream — script
        // generation and metric attribution alike — reads them rather than re-deriving them, so a
        // pair of operation ids that sanitise to the same key can never be confused for one another.
        Map<OperationId, String> scenarioKeys =
                OperationKeys.assign(workload.operations().operationIds());

        Map<OperationId, AllocatedRate> allocations = allocate(workload);

        List<PlannedOperation> planned = new ArrayList<>();
        for (OperationId operationId : workload.operations().operationIds()) {
            resolveOperation(workload, configuration, catalog, operationId,
                    scenarioKeys.get(operationId), allocations.get(operationId), problems)
                    .ifPresent(planned::add);
        }

        if (!problems.isEmpty()) {
            throw new PlanResolutionException(
                    "Some operations in this workload cannot be executed yet.", problems);
        }

        var configuredTarget = environment.baseUrl();
        var effectiveTarget = configuredTarget;
        String rewriteReason = "";
        if (request.targetRewrite() != null) {
            effectiveTarget = configuredTarget.withHost(request.targetRewrite().newHost());
            rewriteReason = request.targetRewrite().reason();
        }

        TestIntent intent = request.intent() != null
                ? request.intent()
                : new TestIntent(workload.type(), workload.objective());

        // Release identity, highest precedence first: the command line, then the committed
        // configuration, then nothing. A pipeline passes --service-version "$GIT_SHA" and gets the
        // build it actually checked out, whatever the file happens to say.
        String serviceVersion = request.serviceVersion().isBlank()
                ? configuration.serviceVersion()
                : request.serviceVersion();

        ThresholdSet thresholds = workload.effectiveThresholds(configuration.thresholds());

        List<PlannedDataset> plannedDatasets = resolveDatasets(project, planned, problems);
        if (!problems.isEmpty()) {
            throw new PlanResolutionException(
                    "The data this workload's requests need could not be resolved.", problems);
        }

        DatasetHome home = DatasetHome.of(project.id(), project.workspacePath());
        Map<DatasetRef, PlannedDataset> byRef = new LinkedHashMap<>();
        plannedDatasets.forEach(dataset -> byRef.put(dataset.ref(), dataset));
        problems.addAll(RequestDataValidator.validate(planned, byRef,
                ref -> datasets.read(home, ref)));
        if (!problems.isEmpty()) {
            throw new PlanResolutionException(
                    "The request data for this workload cannot be sent as configured.", problems);
        }

        return new EffectiveTestPlan(
                TestPlanId.generate(),
                project.id(),
                project.name(),
                serviceVersion,
                intent,
                workload.name(),
                workload.description(),
                workload.type(),
                workload.model(),
                workload.peakLevel(),
                workload.stages(),
                planned,
                plannedDatasets,
                workload.source(),
                thresholds,
                environment.name(),
                environment.type(),
                configuredTarget,
                effectiveTarget,
                rewriteReason,
                environment.dependencyMode(),
                environment.classification(),
                environment.headers(),
                workload.k6Options(),
                request.runner(),
                request.scriptSource(),
                request.safetyDecisions(),
                null)
                .withComputedFingerprint();
    }

    /**
     * Reads every dataset this plan's requests name, and records what it found.
     *
     * <p>Read now, at resolution, rather than trusted from configuration. A dataset's fields and
     * size are facts about a file that somebody may have edited since they configured it, and the
     * content hash is what lets two runs be told apart when they did. Resolving here also means a
     * dataset that has gone missing stops the plan being built rather than surfacing as an
     * undefined variable on the first request.
     *
     * <p>The rows themselves are not copied onto the plan. They are read again when the run stages
     * a copy for the engine, because a dataset large enough to be realistic is large enough not to
     * want a second copy of it held for the lifetime of a page.
     */
    private List<PlannedDataset> resolveDatasets(Project project, List<PlannedOperation> operations,
            List<String> problems) {

        Set<DatasetRef> referenced = new LinkedHashSet<>();
        for (PlannedOperation operation : operations) {
            referenced.addAll(operation.referencedDatasets());
        }
        if (referenced.isEmpty()) {
            return List.of();
        }

        DatasetHome home = DatasetHome.of(project.id(), project.workspacePath());
        List<PlannedDataset> resolved = new ArrayList<>();
        for (DatasetRef ref : referenced) {
            try {
                Dataset dataset = datasets.find(home, ref).orElse(null);
                if (dataset == null) {
                    problems.add(datasetMissing(ref));
                    continue;
                }
                if (dataset.isEmpty()) {
                    problems.add("Dataset '" + ref.name() + "' has no rows, so every value it "
                            + "supplies would be undefined. Add records to it, or point these "
                            + "values at a dataset that has some.");
                    continue;
                }
                resolved.add(new PlannedDataset(ref, dataset.format(),
                        PlannedDataset.stagedFileNameFor(ref), dataset.fields(),
                        dataset.recordCount(), dataset.contentHash()));
            } catch (DatasetException e) {
                problems.add("Dataset '" + ref.name() + "' could not be read: " + e.getMessage());
            }
        }
        return resolved;
    }

    private String datasetMissing(DatasetRef ref) {
        return switch (ref.scope()) {
            case LOCAL -> "Dataset '" + ref.name() + "' is not on this machine. It is held locally "
                    + "rather than committed with the service, so a checkout that has never seen it "
                    + "cannot resolve it. Add the dataset here, or make it portable so it travels "
                    + "with the configuration.";
            case PORTABLE -> "Dataset '" + ref.name() + "' should be committed with this service, "
                    + "but no file for it was found in its directory. Add it to the repository, or "
                    + "change these values to use a dataset held on this machine.";
        };
    }

    /**
     * Divides the workload's total arrival rate across its operations.
     *
     * <p>Empty for a concurrency workload, which controls virtual users rather than arrivals. There
     * is nothing to divide there: {@code Workload} already requires such a workload to drive a single
     * operation, precisely because splitting virtual users would not split traffic.
     */
    private Map<OperationId, AllocatedRate> allocate(Workload workload) {
        if (workload.model() != WorkloadModel.OPEN) {
            return Map.of();
        }
        RequestsPerSecond total = (RequestsPerSecond) workload.peakLevel();
        RateAllocation allocation = rateAllocator.allocate(total, workload.operations());
        Map<OperationId, AllocatedRate> byOperation = new LinkedHashMap<>();
        for (AllocatedRate allocated : allocation.allocations()) {
            byOperation.put(allocated.operationId(), allocated);
        }
        return byOperation;
    }

    private Optional<PlannedOperation> resolveOperation(Workload workload,
            ProjectConfiguration configuration, ServiceCatalog catalog, OperationId operationId,
            String scenarioKey, AllocatedRate allocatedRate, List<String> problems) {

        Optional<Operation> found = catalog.find(operationId);
        if (found.isEmpty()) {
            // Reported once when the catalog is empty: a project adopted from a committed
            // vortex.yaml names operations but has no imported specification yet, and repeating
            // "operation not found" for every operation obscures the one thing that is actually
            // wrong.
            String problem = catalog.operations().isEmpty()
                    ? "No API description has been imported for this project, so the operations its "
                    + "workloads name cannot be resolved. Import the service's OpenAPI document, then "
                    + "run this test again."
                    : "Workload '" + workload.name() + "' references operation '" + operationId.value()
                    + "', which is not in the imported API description. Re-import the specification, "
                    + "or edit the workload.";
            if (!problems.contains(problem)) {
                problems.add(problem);
            }
            return Optional.empty();
        }

        Operation operation = found.get();
        OperationBinding binding = configuration.bindingOrDefault(operationId);

        if (!operation.isExecutable(binding)) {
            problems.add("Workload '" + workload.name() + "' includes " + operation.label()
                    + ", which changes data. Review its request data on the operations page before "
                    + "running this test — Vortex will not execute a mutating operation just because "
                    + "it could generate schema-valid JSON for it.");
            return Optional.empty();
        }

        // Request data is resolved by folding layers, most general first. Today there are two: what
        // the specification implies, and what a person decided about this operation. The fold is
        // written out rather than inlined because the list is the extension point — a workload-scoped
        // layer would be a third element here and nothing else would move.
        RequestData requestData = List.of(
                        requestDataResolver.specificationLayer(operation),
                        binding.requestData())
                .stream()
                .reduce(RequestData.EMPTY, (base, layer) -> layer.layeredOver(base));

        // Checked against the catalog rather than the plan, because the plan does not carry the
        // schema — and a value the service is documented to reject should stop a run rather than
        // become a hundred per cent error rate somebody has to interpret.
        problems.addAll(RequestDataValidator.validateAgainstSchema(operation, requestData,
                operation.label()));

        PayloadProvenance provenance = provenanceOf(operation, binding, requestData);

        // The guarded factory, not the canonical constructor: it takes an AllocatedRate, and the
        // only way to obtain one of those is to have asked RateAllocator to divide a total.
        return Optional.of(PlannedOperation.driving(
                operation.id(),
                operation.label(),
                scenarioKey,
                operation.method(),
                operation.path(),
                requestData,
                provenance,
                binding.expect(),
                allocatedRate));
    }

    /**
     * Where this request's payload came from, so a report never mistakes a schema-generated body for
     * business-validated data.
     *
     * <p>A body with any field bound to a dataset is dataset-supplied, whatever the base document
     * was: the values that make the request meaningful came from data somebody curated, and that is
     * a stronger claim than "Vortex made this up from a schema" and a weaker one than "a person wrote
     * this exact payload".
     */
    private PayloadProvenance provenanceOf(Operation operation, OperationBinding binding,
            RequestData requestData) {
        boolean fromDataset = requestData.bodyValues().values().stream()
                .anyMatch(value -> value instanceof DatasetValue);
        if (fromDataset) {
            return PayloadProvenance.DATASET_SUPPLIED;
        }
        if (binding.bodyIfPresent().isPresent() || !requestData.bodyValues().isEmpty()) {
            return PayloadProvenance.HUMAN_AUTHORED;
        }
        return operation.body().map(b -> b.provenance()).orElse(PayloadProvenance.SCHEMA_GENERATED);
    }

    /** The rate allocation for a workload, for preview before a run. */
    public Optional<RateAllocation> previewAllocation(Workload workload) {
        if (workload.model() != WorkloadModel.OPEN) {
            return Optional.empty();
        }
        return Optional.of(rateAllocator.allocate(
                (RequestsPerSecond) workload.peakLevel(), workload.operations()));
    }
}
