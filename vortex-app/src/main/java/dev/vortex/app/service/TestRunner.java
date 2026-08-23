package dev.vortex.app.service;

import dev.vortex.app.service.EvidenceContextFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vortex.core.application.CapacityService;
import dev.vortex.core.application.DeterministicAnalyzer;
import dev.vortex.core.application.ExecutionService;
import dev.vortex.core.application.PlanResolver;
import dev.vortex.core.application.PreflightReport;
import dev.vortex.core.application.PreflightService;
import dev.vortex.core.application.ProjectService;
import dev.vortex.core.data.DatasetHome;
import dev.vortex.core.execution.ExecutionProgress;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.intent.TestIntent;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.RunnerKind;
import dev.vortex.core.plan.SafetyDecision;
import dev.vortex.core.plan.ScriptSource;
import dev.vortex.core.port.ArtifactStore;
import dev.vortex.core.port.Repositories;
import dev.vortex.core.execution.ExecutionArtifacts;
import dev.vortex.core.execution.ExecutionState;
import dev.vortex.core.application.RunEvidenceService;
import dev.vortex.app.evidence.EvidenceJsonWriter;
import dev.vortex.app.evidence.EvidenceMarkdownWriter;
import dev.vortex.core.port.Clock;
import dev.vortex.core.port.PerformanceEngine;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.shared.ProjectId;
import dev.vortex.k6.K6PerformanceEngine;
import dev.vortex.persistence.JsonDocuments;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns "run the production-peak workload" into a completed, evaluated, recorded test.
 *
 * <p>The web interface starts a run on a background thread and streams progress.
 *
 * <p>The sequence is deliberate: resolve the plan, snapshot it, check safety, run, collect, evaluate,
 * record capacity. AI is not part of it — a run is complete and has a verdict before any model is
 * consulted.
 */
@Service
public class TestRunner {

    private static final Logger log = LoggerFactory.getLogger(TestRunner.class);

    private final ProjectService projects;
    private final PlanResolver planResolver;
    private final PreflightService preflight;
    private final ExecutionService executions;
    private final CapacityService capacity;
    private final DeterministicAnalyzer analyzer;
    private final K6PerformanceEngine engine;
    private final ArtifactStore artifacts;
    private final Clock clock;
    private final RunEvidenceService runEvidence;
    private final EvidenceJsonWriter jsonWriter = new EvidenceJsonWriter();
    private final EvidenceMarkdownWriter markdownWriter = new EvidenceMarkdownWriter();
    private final Repositories.ExecutionRepository executionRepository;
    private final ObjectMapper json = JsonDocuments.mapper();

    /** Cancellation flags for runs currently in flight, keyed by execution. */
    private final Map<String, AtomicBoolean> cancellations = new ConcurrentHashMap<>();

    /** The most recent progress for each running execution, so a page reload can pick it up. */
    private final Map<String, ExecutionProgress> latestProgress = new ConcurrentHashMap<>();

    private final EvidenceContextFactory evidenceContext;

    public TestRunner(ProjectService projects, PlanResolver planResolver, PreflightService preflight,
            ExecutionService executions, CapacityService capacity, DeterministicAnalyzer analyzer,
            K6PerformanceEngine engine, ArtifactStore artifacts, Clock clock,
            RunEvidenceService runEvidence,
            Repositories.ExecutionRepository executionRepository,
            EvidenceContextFactory evidenceContext) {
        this.projects = projects;
        this.planResolver = planResolver;
        this.preflight = preflight;
        this.executions = executions;
        this.capacity = capacity;
        this.analyzer = analyzer;
        this.engine = engine;
        this.artifacts = artifacts;
        this.clock = clock;
        this.runEvidence = runEvidence;
        this.executionRepository = executionRepository;
        this.evidenceContext = evidenceContext;
    }

    /**
     * Builds the plan that would be executed, without executing it.
     *
     * <p>This is what the preflight screen renders. The plan it shows is the same object that gets
     * snapshotted and run, so what a user approves is exactly what happens.
     */
    public PreflightReport prepare(ProjectId projectId, String workloadName, String environmentName,
            String objective, List<SafetyDecision> decisions) {

        return prepare(projectId, workloadName, environmentName, objective, decisions, "");
    }

    /** As above, for a caller that knows which release is under test. */
    public PreflightReport prepare(ProjectId projectId, String workloadName, String environmentName,
            String objective, List<SafetyDecision> decisions, String serviceVersion) {

        EffectiveTestPlan plan = resolve(projectId, workloadName, environmentName, objective,
                decisions, serviceVersion);
        return preflight.check(plan);
    }

    /**
     * Resolves configuration into the plan that will actually be executed.
     *
     * <p>Called twice on the way to a run: once to build the preflight report, and again after the
     * user has confirmed, so the safety decisions they gave become part of the plan and therefore
     * part of the permanent record of what was agreed to.
     */
    public EffectiveTestPlan resolve(ProjectId projectId, String workloadName, String environmentName,
            String objective, List<SafetyDecision> decisions) {

        return resolve(projectId, workloadName, environmentName, objective, decisions, "");
    }

    /**
     * As above, overriding the release under test.
     *
     * @param serviceVersion blank to use whatever the configuration records
     */
    public EffectiveTestPlan resolve(ProjectId projectId, String workloadName, String environmentName,
            String objective, List<SafetyDecision> decisions, String serviceVersion) {

        var project = projects.find(projectId).orElseThrow(
                () -> new IllegalArgumentException("No project with id " + projectId));
        var configuration = projects.configuration(projectId);
        var catalog = projects.catalog(projectId)
                .orElseGet(dev.vortex.core.catalog.ServiceCatalog::empty);

        TestIntent intent = configuration.workloadByName(workloadName)
                .map(workload -> new TestIntent(workload.type(),
                        objective == null || objective.isBlank() ? workload.objective() : objective))
                .orElse(null);

        // The Docker runner cannot reach the host's localhost, so the target has to change. That
        // rewrite is carried into the plan and shown in preflight rather than applied silently — a
        // run whose traffic went somewhere other than the configured address is a run whose results
        // cannot be interpreted.
        PlanResolver.TargetRewrite rewrite = null;
        var provisional = configuration.environmentByName(environmentName);
        // The probe only knows how to ask about an address that already exists. A Docker or Compose
        // target has none yet — its own engine-reachability rewrite is composed later, once
        // ExecutionService has actually resolved a runtime endpoint (see its planForEngine).
        if (provisional.isPresent()
                && provisional.get().target() instanceof dev.vortex.core.target.ExternalEndpointTarget) {
            var probe = new EffectiveTestPlanProbe(provisional.get());
            rewrite = engine.targetRewriteFor(probe.plan())
                    .map(hint -> new PlanResolver.TargetRewrite(hint.newHost(), hint.reason()))
                    .orElse(null);
        }

        return planResolver.resolve(project, configuration, catalog,
                new PlanResolver.ResolutionRequest(workloadName, environmentName, intent,
                        engine.toolVersions().dockerImageIfPresent().isPresent()
                                ? RunnerKind.DOCKER : RunnerKind.LOCAL_BINARY,
                        ScriptSource.GENERATED, decisions, rewrite, serviceVersion));
    }

    /**
     * Registers a run and writes its immutable snapshot — the plan, and the data it reads.
     *
     * <p>The service is looked up again rather than carried on the plan: where a portable dataset
     * lives is a property of the checkout, not of the experiment, and a plan read back on another
     * machine should not claim to know a path on this one.
     */
    public TestExecution create(EffectiveTestPlan plan) {
        var project = projects.find(plan.projectId()).orElseThrow(
                () -> new IllegalArgumentException("No service with id " + plan.projectId()));
        return executions.create(plan, writePlan(plan),
                DatasetHome.of(project.id(), project.workspacePath()));
    }

    /**
     * Runs a prepared execution to completion.
     *
     * <p>Blocking, and intended to be called on a virtual thread. Nothing here holds a platform
     * thread while a fifteen-minute test runs.
     */
    public TestExecution execute(ExecutionId executionId, Consumer<ExecutionProgress> progressSink) {
        AtomicBoolean cancelled = new AtomicBoolean();
        cancellations.put(executionId.value(), cancelled);

        try {
            TestExecution execution = executions.run(executionId,
                    progress -> {
                        latestProgress.put(executionId.value(), progress);
                        progressSink.accept(progress);
                    },
                    cancelled::get);

            recordCapacity(execution);
            writeEvidence(execution);
            return execution;
        } finally {
            cancellations.remove(executionId.value());
            latestProgress.remove(executionId.value());
        }
    }

    /** Asks a running execution to stop. */
    public boolean cancel(ExecutionId executionId) {
        AtomicBoolean flag = cancellations.get(executionId.value());
        if (flag == null) {
            return false;
        }
        flag.set(true);
        return true;
    }

    public Optional<ExecutionProgress> progressFor(ExecutionId executionId) {
        return Optional.ofNullable(latestProgress.get(executionId.value()));
    }

    public boolean isRunning(ExecutionId executionId) {
        return cancellations.containsKey(executionId.value());
    }

    /**
     * Writes the run's evidence into its artifact directory.
     *
     * <p>JSON and Markdown, eagerly. Both are cheap to produce and cost nothing to keep, and writing
     * them here is what makes {@code ~/.vortex/executions/<id>/} self-describing — the verdict, the
     * objective-by-objective results, the tool versions and the timestamps stop living only in the
     * local database.
     *
     * <p>Failure is logged and swallowed, like capacity evidence above. A run that measured a
     * service correctly did not fail because a document could not be written, and reporting it as
     * failed would be a worse lie than the missing file.
     */
    private void writeEvidence(TestExecution execution) {
        if (execution.state() != ExecutionState.COMPLETED) {
            return;
        }
        try {
            var evidence = runEvidence.assemble(execution, null, null,
                    evidenceContext.forExecution(execution),
                    artifacts.directoryFor(execution.id()), artifacts.list(execution.id()));

            String jsonPath = artifacts.writeBytes(execution.id(), ExecutionArtifacts.EVIDENCE,
                    jsonWriter.export(evidence));
            String markdownPath = artifacts.writeBytes(execution.id(), ExecutionArtifacts.REPORT,
                    markdownWriter.export(evidence));

            var updated = execution.artifacts()
                    .with(ExecutionArtifacts.EVIDENCE, jsonPath)
                    .with(ExecutionArtifacts.REPORT, markdownPath);
            executionRepository.save(execution.withArtifacts(updated));
        } catch (RuntimeException e) {
            log.warn("Could not write the evidence documents for execution {}: {}",
                    execution.id().value(), e.getMessage());
        }
    }

    /** Records capacity evidence when the run established any. */
    private void recordCapacity(TestExecution execution) {
        try {
            execution.summaryIfPresent().ifPresent(summary ->
                    capacity.recordFrom(execution,
                            analyzer.deriveStages(execution.plan(), summary.results())));
        } catch (RuntimeException e) {
            log.warn("Could not record capacity evidence: {}", e.getMessage());
        }
    }

    private String writePlan(EffectiveTestPlan plan) {
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(plan);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise the effective test plan", e);
        }
    }

    /**
     * A minimal plan used only to ask the runner whether a target needs rewriting.
     *
     * <p>Slightly awkward, and deliberately so: the rewrite has to be known <em>before</em> the real
     * plan is built, because it becomes part of that plan and therefore part of what a user approves.
     */
    private record EffectiveTestPlanProbe(dev.vortex.core.environment.Environment environment) {

        EffectiveTestPlan plan() {
            // Only ever constructed for an ExternalEndpointTarget — resolve()'s caller guards this;
            // a Docker/Compose target has no pre-run address for this probe to ask about at all.
            dev.vortex.core.environment.TargetUrl endpoint =
                    ((dev.vortex.core.target.ExternalEndpointTarget) environment.target()).endpoint();
            return new EffectiveTestPlan(
                    dev.vortex.core.shared.TestPlanId.generate(),
                    dev.vortex.core.shared.ProjectId.generate(), "", "",
                    TestIntent.defaultFor(dev.vortex.core.workload.TestType.SMOKE), "", "",
                    dev.vortex.core.workload.TestType.SMOKE,
                    dev.vortex.core.workload.WorkloadModel.OPEN,
                    dev.vortex.core.shared.RequestsPerSecond.of(1), List.of(), List.of(), List.of(),
                    dev.vortex.core.workload.WorkloadSource.manual(),
                    dev.vortex.core.threshold.ThresholdSet.empty(), environment.name(),
                    environment.type(), environment.target(), endpoint, endpoint, "",
                    environment.dependencyMode(), environment.classification(), Map.of(), Map.of(),
                    RunnerKind.LOCAL_BINARY, ScriptSource.GENERATED, List.of(), null,
                    dev.vortex.core.validity.ValidityPolicy.defaults(), "");
        }
    }
}
