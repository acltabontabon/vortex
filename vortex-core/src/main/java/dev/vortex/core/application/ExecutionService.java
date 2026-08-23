package dev.vortex.core.application;

import dev.vortex.core.data.DatasetHome;
import dev.vortex.core.execution.ExecutionArtifacts;
import dev.vortex.core.execution.ExecutionProgress;
import dev.vortex.core.execution.ExecutionState;
import dev.vortex.core.execution.FailureReason;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.ExperimentIdentity;
import dev.vortex.core.plan.PlannedDataset;
import dev.vortex.core.port.ArtifactStore;
import dev.vortex.core.port.Clock;
import dev.vortex.core.port.DatasetStore;
import dev.vortex.core.port.PerformanceEngine;
import dev.vortex.core.port.Repositories.ExecutionRepository;
import dev.vortex.core.port.TelemetryCollector;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.validity.RunQualityAssessor;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Runs a test from request through to verdict.
 *
 * <p>The whole method completes deterministically. It generates load, collects results, evaluates
 * objectives and reaches {@link ExecutionState#COMPLETED} without ever consulting a language model.
 * AI interpretation happens afterwards through {@link AnalysisService}, as a separate resource that
 * can fail, be retried, or never be requested at all, with no effect on the verdict.
 *
 * <p>This is what makes {@code vortex run peak --headless} usable in a pipeline: continuous
 * integration gets a deterministic exit code, and an inference service being down is not a build
 * failure.
 */
public final class ExecutionService {

    private final PerformanceEngine engine;
    private final DeterministicAnalyzer analyzer;
    private final ExecutionRepository executions;
    private final ArtifactStore artifacts;
    private final DatasetStore datasets;
    private final TelemetryCollector telemetry;
    private final Clock clock;

    /**
     * Grades whether the experiment was carried out as specified.
     *
     * <p>A pure calculator rather than a collaborator with state, constructed here rather than
     * injected because there is exactly one implementation and no reason for a second: a validity
     * rule that is not the same for everybody is not a validity rule.
     */
    private final RunQualityAssessor validity = new RunQualityAssessor();

    public ExecutionService(PerformanceEngine engine, DeterministicAnalyzer analyzer,
            ExecutionRepository executions, ArtifactStore artifacts, DatasetStore datasets,
            TelemetryCollector telemetry, Clock clock) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.datasets = Objects.requireNonNull(datasets, "datasets");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Registers a run, snapshots its plan and stages the data it reads, before anything else happens.
     *
     * @param home which service's datasets to stage; only consulted when the plan reads one
     */
    public TestExecution create(EffectiveTestPlan plan, String planJson, DatasetHome home) {
        ExecutionId id = ExecutionId.generate();
        TestExecution execution = TestExecution.create(id, plan, clock.now());

        ExecutionArtifacts written = ExecutionArtifacts.empty()
                .with(ExecutionArtifacts.PLAN, artifacts.write(id, ExecutionArtifacts.PLAN, planJson));

        // Beside the script, in the directory the engine runs from, so `k6 run generated-test.js`
        // in that directory reproduces the run with Vortex uninstalled — which is the claim the
        // generated script makes in its own header, and this is what keeps it true. Recorded as
        // artifacts too, because a run's evidence should be able to say what data produced it and
        // let somebody go and look.
        for (PlannedDataset dataset : plan.datasets()) {
            written = written.with(dataset.stagedFile(),
                    artifacts.write(id, dataset.stagedFile(), datasets.stagedJson(home, dataset.ref())));
        }

        return executions.save(execution.withArtifacts(written));
    }

    /**
     * Runs a prepared execution to completion.
     *
     * <p>Blocking; callers run it on a virtual thread so the UI stays responsive while traffic is
     * being generated.
     */
    public TestExecution run(ExecutionId id, Consumer<ExecutionProgress> progressSink,
            PerformanceEngine.Cancellation cancellation) {

        TestExecution execution = executions.findById(id).orElseThrow(
                () -> new IllegalArgumentException("No execution with id " + id));

        // Session.empty() until sampling actually starts, so the finally below has something safe
        // to close even if the run fails before RUNNING is reached.
        TelemetryCollector.Session telemetrySession = TelemetryCollector.Session.empty();
        try {
            execution = advance(execution, ExecutionState.VALIDATING);
            var validation = engine.validate(execution.plan());
            if (!validation.valid()) {
                return fail(execution, FailureReason.PREFLIGHT_FAILED,
                        String.join("\n", validation.problems()));
            }

            execution = advance(execution, ExecutionState.READY);
            execution = advance(execution, ExecutionState.STARTING);
            execution = execution.withToolVersions(engine.toolVersions());
            executions.save(execution);

            execution = advance(execution, ExecutionState.RUNNING);

            // Sampling starts before traffic and runs alongside it. The measurements that explain a
            // bottleneck — pool utilisation, queue depth — are instantaneous gauges, and reading
            // them after the load stops would report a service sitting idle.
            telemetrySession = startTelemetry(execution.plan(), id);

            PerformanceEngine.EngineOutcome outcome =
                    engine.execute(id, execution.plan(), progressSink, cancellation);

            if (cancellation.isCancelled()) {
                // A cancelled run keeps whatever it measured, and is graded rather than discarded.
                // Evidence assembly still refuses it — a report of an incomplete run is a document
                // about nothing — but the CLI and the run API read its validity from here, which is
                // the only way EXECUTION_INTERRUPTED can fire at all.
                TestExecution cancelled = execution
                        .withResults(outcome.resultsIfPresent().orElse(null))
                        .cancelled(clock.now());
                cancelled = cancelled.withQuality(validity.assess(cancelled.plan(),
                        cancelled.results(), stagesOf(cancelled), ExecutionState.CANCELLED, null));
                return executions.save(cancelled);
            }

            execution = advance(execution, ExecutionState.COLLECTING);
            execution = execution.withArtifacts(recordArtifacts(execution, outcome.artifactNames()));

            if (!outcome.producedResults()) {
                return fail(execution,
                        outcome.exitCode() == 0
                                ? FailureReason.RESULTS_UNREADABLE
                                : FailureReason.ENGINE_FAILED,
                        outcome.failureDetail());
            }

            // Telemetry from the service itself is gathered here, while the run is still being
            // collected. After EVALUATING the measurements are settled, and enriching them later
            // would mean a completed execution whose evidence changed after the fact.
            var results = withTelemetry(telemetrySession, outcome.results());
            execution = execution.withResults(results);
            execution = advance(execution, ExecutionState.EVALUATING);

            var summary = analyzer.analyze(execution.plan(), results);
            execution = execution.withSummary(summary);

            // The fourth axis, computed from the same evidence tier as the verdict and deliberately
            // not derived from it. A run can meet every objective and still not have measured what
            // it claims to.
            execution = execution.withQuality(validity.assess(execution.plan(), results,
                    analyzer.deriveStages(execution.plan(), results), ExecutionState.COMPLETED,
                    null));

            execution = advance(execution, ExecutionState.COMPLETED);
            return executions.save(execution);

        } catch (RuntimeException e) {
            return fail(execution, FailureReason.INTERNAL_ERROR, describe(e));
        } finally {
            // Guarantees the sampling session stops on every exit from this method — success,
            // cancellation, or any exception, including one raised while recording artifacts or
            // advancing state after RUNNING, neither of which used to be guarded. Before this, such
            // an exception left the sampler polling the service for up to its own safety ceiling
            // after the execution had already been marked failed. Session.finish() is idempotent, so
            // this is a harmless no-op on the path above that already finished it explicitly via
            // withTelemetry(), and the only call that matters on every other path.
            stopTelemetry(telemetrySession);
        }
    }

    /**
     * Marks runs left in flight by a previous process as failed.
     *
     * <p>An interrupted run is graded as one: {@code EXECUTION_INTERRUPTED} withholds its capacity
     * claims while keeping every measurement it took, so a run somebody killed does not silently
     * become a baseline.
     *
     * <p>Vortex does not adopt orphaned engine processes on restart. Reporting a run as interrupted
     * is honest; leaving it apparently still going is not, and silently resuming would risk a second
     * load generator against the same target.
     */
    public int reconcileUnfinished() {
        List<TestExecution> unfinished = executions.findUnfinished();
        if (unfinished.isEmpty()) {
            return 0;
        }
        List<TestExecution> failed = unfinished.stream()
                .map(execution -> new TestExecution(
                        execution.id(), execution.projectId(), execution.plan(),
                        ExecutionState.FAILED, execution.requestedAt(), execution.startedAt(),
                        clock.now(), execution.results(), execution.summary(),
                        execution.toolVersions(), execution.artifacts(), FailureReason.INTERRUPTED,
                        FailureReason.INTERRUPTED.guidance(),
                        validity.assess(execution.plan(), execution.results(), stagesOf(execution),
                                ExecutionState.FAILED, FailureReason.INTERRUPTED)))
                .toList();
        executions.saveAll(failed);
        return failed.size();
    }

    /**
     * Re-indexes stored experiment fingerprints against the current identity contract.
     *
     * <p>Experiment identity is a contract, and a contract can change: this build no longer folds
     * the service version or the workload name into it, so every fingerprint written by an earlier
     * build indexes a run under a hash that nothing will ever look up again. Left alone, a team's
     * entire comparison history would silently disappear on upgrade — the failure mode being that
     * "no previous compatible run exists" is also what Vortex says when it is simply the first run.
     *
     * <p>Only the lookup column is rewritten. The stored plan and the {@code plan.json} beside it
     * are the historical record of what ran, and the fingerprint they carry is the value that was
     * true at the time; comparison derives identity fresh from the plan rather than trusting either.
     *
     * <p>Self-detecting, deliberately. An earlier design only re-indexed rows a migration had
     * blanked, which meant a change to the contract silently orphaned every run unless somebody
     * also remembered to write the migration. That is a footgun this pass tripped over in testing,
     * and the failure it produces is invisible: comparison simply reports that no baseline exists.
     * Recomputing every row and writing back only what actually changed costs one projection query
     * at start-up and needs nothing remembered.
     *
     * @return how many executions were re-indexed
     */
    public int reconcileExperimentIdentity() {
        java.util.Map<ExecutionId, String> changed = new java.util.LinkedHashMap<>();
        for (ExecutionRepository.ExperimentIndex indexed : executions.findExperimentIndexes()) {
            String current = ExperimentIdentity.fingerprintOf(indexed.plan()).hash();
            if (!current.equals(indexed.indexedFingerprint())) {
                changed.put(indexed.id(), current);
            }
        }
        return executions.reindexExperimentFingerprints(changed);
    }

    /** Begins telemetry sampling, tolerating a collector that cannot start at all. */
    private TelemetryCollector.Session startTelemetry(dev.vortex.core.plan.EffectiveTestPlan plan,
            ExecutionId id) {
        try {
            return telemetry.start(plan, id);
        } catch (RuntimeException e) {
            return TelemetryCollector.Session.empty();
        }
    }

    /** Stops telemetry sampling unconditionally, tolerating a session that fails to finish cleanly.
     *  Resource telemetry must never be the reason a performance test's own result is lost. */
    private void stopTelemetry(TelemetryCollector.Session session) {
        try {
            session.finish(new dev.vortex.core.metrics.TimeWindow(clock.now(), clock.now()));
        } catch (RuntimeException e) {
            // Best-effort cleanup only; the run's own outcome does not depend on this.
        }
    }

    /**
     * Adds measurements taken from the service under test, when any were available.
     *
     * <p>A collector that fails or finds nothing is not an error: the run still produced valid
     * client-side measurements, and the missing server-side view is reported as missing rather than
     * failing the test.
     *
     * <p>This rebuilds the record component by component, which makes it the one place a newly added
     * measurement can be silently dropped on its way to being stored. Everything the engine
     * established — what the generator managed, the request phases, the outcome distribution — is
     * forwarded explicitly below. Losing {@code generation} here in particular would leave every run
     * reporting that nobody measured whether its offered load was generated, which is the single
     * failure this phase exists to detect.
     */
    private dev.vortex.core.metrics.MeasuredResults withTelemetry(
            TelemetryCollector.Session session,
            dev.vortex.core.metrics.MeasuredResults results) {
        try {
            var telemetry = session.finish(results.window());
            if (telemetry.isEmpty() && telemetry.gaps().isEmpty()) {
                return results;
            }
            return new dev.vortex.core.metrics.MeasuredResults(
                    results.window(), results.targetLoad(), results.achievedRate(),
                    results.requests(), results.failures(), results.latency(),
                    results.perOperation(), results.series(), telemetry.run(),
                    telemetry.byStage(), telemetry.gaps(),
                    results.generation(), results.phases(), results.reliability(),
                    telemetry.resourceSignals());
        } catch (RuntimeException e) {
            return results;
        }
    }

    /**
     * The run cut by level, where there is anything to cut.
     *
     * <p>Empty for a cancelled run that produced no series, which is the common case when somebody
     * stops a run in its first seconds. The assessor treats an empty list as "no stage evidence"
     * rather than as "no stage had a problem".
     */
    private List<dev.vortex.core.analysis.StageObservation> stagesOf(TestExecution execution) {
        return execution.resultsIfPresent()
                .map(results -> analyzer.deriveStages(execution.plan(), results))
                .orElse(List.of());
    }

    private TestExecution advance(TestExecution execution, ExecutionState next) {
        TestExecution moved = execution.transitionTo(next, clock.now());
        return executions.save(moved);
    }

    private TestExecution fail(TestExecution execution, FailureReason reason, String detail) {
        TestExecution failed = execution.state().canTransitionTo(ExecutionState.FAILED)
                ? execution.failed(reason, detail, clock.now())
                : execution;
        return executions.save(failed);
    }

    private ExecutionArtifacts recordArtifacts(TestExecution execution, List<String> names) {
        ExecutionArtifacts result = execution.artifacts();
        for (String name : names) {
            result = result.with(name, name);
        }
        return result;
    }

    private String describe(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return message + " (" + e.getClass().getName() + ")";
    }
}
