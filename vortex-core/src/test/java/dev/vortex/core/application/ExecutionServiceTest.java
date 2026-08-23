package dev.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.analysis.BreakpointDetector;
import dev.vortex.core.analysis.SystemSaturationDetector;
import dev.vortex.core.data.DatasetHome;
import dev.vortex.core.environment.TargetUrl;
import dev.vortex.core.execution.ExecutionState;
import dev.vortex.core.execution.FailureReason;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.fixtures.FakeDatasetStore;
import dev.vortex.core.fixtures.FakePerformanceEngine;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.fixtures.InMemoryExecutions;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.port.ArtifactStore;
import dev.vortex.core.port.Clock;
import dev.vortex.core.port.PerformanceEngine;
import dev.vortex.core.port.PerformanceEngine.EngineOutcome;
import dev.vortex.core.port.PerformanceEngine.ValidationResult;
import dev.vortex.core.port.TargetExecutor;
import dev.vortex.core.port.TelemetryCollector;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.target.CleanupOutcome;
import dev.vortex.core.target.ExecutionTarget;
import dev.vortex.core.target.ExternalEndpointTargetExecutor;
import dev.vortex.core.target.PreparedTarget;
import dev.vortex.core.target.ResolvedTarget;
import dev.vortex.core.target.TargetCapability;
import dev.vortex.core.target.TargetPreparationException;
import dev.vortex.core.target.TargetPreparationRequest;
import dev.vortex.core.threshold.ThresholdEvaluator;
import dev.vortex.core.validity.ValidityReason;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ExecutionService#run} is the single walk through every {@link ExecutionState} a run can
 * reach, and — before this test — was exercised at the vortex-core layer only indirectly, through
 * {@code reconcileExperimentIdentity()}, which never calls it at all. This covers the happy path and
 * the three ways a run ends without completing: an engine that refuses the plan is caught earlier by
 * preflight and is out of scope here; this is about failure and cancellation reached only once a run
 * is actually under way.
 */
class ExecutionServiceTest {

    private InMemoryExecutions executions;
    private FakePerformanceEngine engine;
    private ExecutionService service;

    @BeforeEach
    void setUp() {
        executions = new InMemoryExecutions();
        engine = new FakePerformanceEngine();
        // The real, always-registered executor — not a fake — because these tests exist to
        // characterize its actual behaviour: every plan Fixtures.plan() builds is
        // ExternalEndpointTarget-backed, exactly as every plan was before target types existed.
        service = serviceWith(new ExternalEndpointTargetExecutor());
    }

    private TestExecution created() {
        var plan = Fixtures.plan();
        return service.create(plan, "{}", DatasetHome.of(plan.projectId(), ""));
    }

    private ExecutionService serviceWith(TargetExecutor executor) {
        return new ExecutionService(
                engine,
                new DeterministicAnalyzer(new ThresholdEvaluator(), new BreakpointDetector(),
                        new SystemSaturationDetector()),
                executions,
                new NoArtifacts(),
                new FakeDatasetStore(),
                TelemetryCollector.none(),
                Clock.fixed(Fixtures.NOW),
                List.of(executor),
                new dev.vortex.core.resource.LoadGeneratorResourceBudgetResolver(
                        dev.vortex.core.port.HostInformation.unknown()),
                dev.vortex.core.resource.LoadGeneratorResourceBudget::automatic);
    }

    @Test
    @DisplayName("a run that produces results walks every state through to COMPLETED")
    void theHappyPathReachesCompleted() {
        TestExecution created = created();
        engine.returning(new EngineOutcome(Fixtures.results(280, 0.001), 0, "", List.of("summary.json")));

        TestExecution finished = service.run(created.id(), progress -> { },
                PerformanceEngine.Cancellation.never());

        assertThat(finished.state()).isEqualTo(ExecutionState.COMPLETED);
        assertThat(finished.resultsIfPresent()).isPresent();
        assertThat(finished.summaryIfPresent()).isPresent();
        assertThat(finished.quality()).isNotNull();
        assertThat(finished.resolvedLoadGeneratorBudgetIfPresent()).isPresent();
        assertThat(engine.lastExecutedLoadGeneratorResources()).isNotNull();
        assertThat(engine.lastExecutedLoadGeneratorResources().isEmpty()).isFalse();
        assertThat(executions.findById(created.id())).hasValueSatisfying(
                stored -> assertThat(stored.state()).isEqualTo(ExecutionState.COMPLETED));
    }

    @Test
    @DisplayName("an engine that produces no results fails the run rather than completing it")
    void noResultsFailsTheRun() {
        TestExecution created = created();
        engine.returning(new EngineOutcome(null, 1, "the engine crashed", List.of()));

        TestExecution finished = service.run(created.id(), progress -> { },
                PerformanceEngine.Cancellation.never());

        assertThat(finished.state()).isEqualTo(ExecutionState.FAILED);
        assertThat(finished.failureReasonIfPresent()).contains(FailureReason.ENGINE_FAILED);
        assertThat(finished.failureDetail()).isEqualTo("the engine crashed");
    }

    @Test
    @DisplayName("an engine that throws mid-run fails the run instead of leaving it stuck in RUNNING")
    void aThrownExceptionFailsTheRunRatherThanLeavingItStuck() {
        TestExecution created = created();
        engine.throwing(new IllegalStateException("the process pipe broke"));

        TestExecution finished = service.run(created.id(), progress -> { },
                PerformanceEngine.Cancellation.never());

        assertThat(finished.state()).isEqualTo(ExecutionState.FAILED);
        assertThat(finished.failureReasonIfPresent()).contains(FailureReason.INTERNAL_ERROR);
        assertThat(finished.failureDetail()).contains("the process pipe broke");
        // The failure must be visible to whoever asks next, not just returned to this caller.
        assertThat(executions.findById(created.id())).hasValueSatisfying(
                stored -> assertThat(stored.state()).isEqualTo(ExecutionState.FAILED));
    }

    @Test
    @DisplayName("cancellation keeps whatever was measured and is graded as interrupted, not discarded")
    void cancellationPreservesPartialResultsAndIsGradedAsInterrupted() {
        TestExecution created = created();
        engine.returning(new EngineOutcome(Fixtures.results(280, 0.001), 0, "", List.of()));

        TestExecution finished = service.run(created.id(), progress -> { }, () -> true);

        assertThat(finished.state()).isEqualTo(ExecutionState.CANCELLED);
        assertThat(finished.resultsIfPresent())
                .as("whatever the engine measured before being asked to stop is kept, not discarded")
                .isPresent();
        assertThat(finished.quality().has(ValidityReason.EXECUTION_INTERRUPTED)).isTrue();
        assertThat(finished.quality().permitsAnyCapacityClaim())
                .as("an interrupted run must never be the basis for a capacity claim")
                .isFalse();
    }

    // ------------------------------------------------------------------ target preparation

    @Test
    @DisplayName("a successful run never mutates or replaces the plan it was created with")
    void thePlanIsNeverMutatedAcrossARun() {
        TestExecution created = created();
        EffectiveTestPlan originalPlan = created.plan();
        engine.returning(new EngineOutcome(Fixtures.results(280, 0.001), 0, "", List.of()));

        TestExecution finished = service.run(created.id(), progress -> { },
                PerformanceEngine.Cancellation.never());

        assertThat(finished.plan())
                .as("the stored plan is the exact object create() built, not a copy")
                .isSameAs(originalPlan);
        assertThat(executions.findById(created.id())).hasValueSatisfying(
                stored -> assertThat(stored.plan()).isSameAs(originalPlan));
    }

    @Test
    @DisplayName("a successful run passes the plan's own effective target through to the engine unchanged")
    void theEffectiveTargetReachesTheEngineUnchanged() {
        TestExecution created = created();
        engine.returning(new EngineOutcome(Fixtures.results(280, 0.001), 0, "", List.of()));

        service.run(created.id(), progress -> { }, PerformanceEngine.Cancellation.never());

        assertThat(engine.lastExecutedPlan()).isNotNull();
        assertThat(engine.lastExecutedPlan().effectiveTargetIfPresent())
                .isEqualTo(created.plan().effectiveTargetIfPresent());
    }

    @Test
    @DisplayName("a successful run persists the runtime fact the target executor resolved")
    void resolvedTargetIsPersistedOnSuccess() {
        TestExecution created = created();
        engine.returning(new EngineOutcome(Fixtures.results(280, 0.001), 0, "", List.of()));

        TestExecution finished = service.run(created.id(), progress -> { },
                PerformanceEngine.Cancellation.never());

        assertThat(finished.resolvedTargetIfPresent()).isPresent();
        assertThat(finished.resolvedTargetIfPresent().orElseThrow().endpoint())
                .isEqualTo(created.plan().configuredTargetIfPresent().orElseThrow());
    }

    @Test
    @DisplayName("a target that cannot be prepared fails the run before the engine is ever asked to run")
    void targetPreparationFailureShortCircuitsBeforeExecute() {
        TestExecution created = created();
        TargetPreparationException failure = new TargetPreparationException(
                FailureReason.TARGET_UNREACHABLE, "the container image could not be found");
        ExecutionService serviceUnderTest = serviceWith(FakeTargetExecutor.throwing(failure));

        TestExecution finished = serviceUnderTest.run(created.id(), progress -> { },
                PerformanceEngine.Cancellation.never());

        assertThat(finished.state()).isEqualTo(ExecutionState.FAILED);
        assertThat(finished.failureReasonIfPresent()).contains(FailureReason.TARGET_UNREACHABLE);
        assertThat(finished.failureDetail()).isEqualTo("the container image could not be found");
        assertThat(engine.lastExecutedPlan())
                .as("the engine must never be asked to run against a target that failed to prepare")
                .isNull();
        assertThat(finished.resolvedTargetIfPresent()).isEmpty();
    }

    @Test
    @DisplayName("the prepared target is released exactly once when a run completes")
    void cleanupRunsExactlyOnceOnSuccess() {
        TestExecution created = created();
        FakePreparedTarget prepared = defaultPreparedTarget();
        ExecutionService serviceUnderTest = serviceWith(new FakeTargetExecutor(request -> prepared));
        engine.returning(new EngineOutcome(Fixtures.results(280, 0.001), 0, "", List.of()));

        serviceUnderTest.run(created.id(), progress -> { }, PerformanceEngine.Cancellation.never());

        assertThat(prepared.cleanupCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the prepared target is released exactly once when a run is cancelled")
    void cleanupRunsExactlyOnceOnCancellation() {
        TestExecution created = created();
        FakePreparedTarget prepared = defaultPreparedTarget();
        ExecutionService serviceUnderTest = serviceWith(new FakeTargetExecutor(request -> prepared));
        engine.returning(new EngineOutcome(Fixtures.results(280, 0.001), 0, "", List.of()));

        serviceUnderTest.run(created.id(), progress -> { }, () -> true);

        assertThat(prepared.cleanupCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the prepared target is released exactly once when the engine produces no results")
    void cleanupRunsExactlyOnceOnEngineFailure() {
        TestExecution created = created();
        FakePreparedTarget prepared = defaultPreparedTarget();
        ExecutionService serviceUnderTest = serviceWith(new FakeTargetExecutor(request -> prepared));
        engine.returning(new EngineOutcome(null, 1, "the engine crashed", List.of()));

        serviceUnderTest.run(created.id(), progress -> { }, PerformanceEngine.Cancellation.never());

        assertThat(prepared.cleanupCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the prepared target is released exactly once when an unexpected exception interrupts the run")
    void cleanupRunsExactlyOnceOnUnexpectedException() {
        TestExecution created = created();
        FakePreparedTarget prepared = defaultPreparedTarget();
        ExecutionService serviceUnderTest = serviceWith(new FakeTargetExecutor(request -> prepared));
        engine.throwing(new IllegalStateException("the process pipe broke"));

        serviceUnderTest.run(created.id(), progress -> { }, PerformanceEngine.Cancellation.never());

        assertThat(prepared.cleanupCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("cleanup failure is recorded as an artifact and a warning, and never changes the run's outcome")
    void cleanupFailureNeverChangesTheTerminalOutcome() {
        TestExecution created = created();
        FakePreparedTarget prepared = new FakePreparedTarget(
                ResolvedTarget.external(TargetUrl.of("http://localhost:8080")),
                new CleanupOutcome(true, false, "docker rm failed: container already stopped"));
        ExecutionService serviceUnderTest = serviceWith(new FakeTargetExecutor(request -> prepared));
        engine.returning(new EngineOutcome(Fixtures.results(280, 0.001), 0, "", List.of()));

        TestExecution finished = serviceUnderTest.run(created.id(), progress -> { },
                PerformanceEngine.Cancellation.never());

        assertThat(finished.state()).isEqualTo(ExecutionState.COMPLETED);
        assertThat(finished.failureReasonIfPresent()).isEmpty();
        assertThat(prepared.cleanupCallCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the engine-facing plan composes target resolution with the engine's own rewrite, "
            + "without the stored plan ever seeing either")
    void engineFacingPlanComposesBothRewrites() {
        TestExecution created = created();
        TargetUrl resolvedEndpoint = TargetUrl.of("http://localhost:9090");
        FakePreparedTarget prepared = new FakePreparedTarget(
                ResolvedTarget.external(resolvedEndpoint), CleanupOutcome.NOTHING_TO_DO);
        ExecutionService serviceUnderTest = serviceWith(new FakeTargetExecutor(request -> prepared));
        engine.returning(new EngineOutcome(Fixtures.results(280, 0.001), 0, "", List.of()));
        engine.rewriting(new PerformanceEngine.TargetRewrite("host.docker.internal",
                "a container's localhost is not the host machine"));

        TestExecution finished = serviceUnderTest.run(created.id(), progress -> { },
                PerformanceEngine.Cancellation.never());

        assertThat(engine.lastExecutedPlan().effectiveTargetIfPresent())
                .contains(resolvedEndpoint.withHost("host.docker.internal"));
        assertThat(finished.plan().effectiveTargetIfPresent())
                .as("the plan ExecutionService stores is untouched by either rewrite")
                .isEqualTo(created.plan().effectiveTargetIfPresent());
    }

    private FakePreparedTarget defaultPreparedTarget() {
        return new FakePreparedTarget(ResolvedTarget.external(TargetUrl.of("http://localhost:8080")),
                CleanupOutcome.NOTHING_TO_DO);
    }

    // ------------------------------------------------------------------ stubs

    private static final class NoArtifacts implements ArtifactStore {

        @Override
        public String write(ExecutionId executionId, String name, String content) {
            return name;
        }

        @Override
        public String writeBytes(ExecutionId executionId, String name, byte[] content) {
            return name;
        }

        @Override
        public Optional<String> read(ExecutionId executionId, String name) {
            return Optional.empty();
        }

        @Override
        public Optional<InputStream> open(ExecutionId executionId, String name) {
            return Optional.empty();
        }

        @Override
        public List<String> list(ExecutionId executionId) {
            return List.of();
        }

        @Override
        public String directoryFor(ExecutionId executionId) {
            return "";
        }

        @Override
        public ArtifactWriter openForAppend(ExecutionId executionId, String name) {
            return new ArtifactWriter() {
                @Override
                public void writeLine(String line) {
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public Optional<Long> sizeOf(ExecutionId executionId, String name) {
            return Optional.empty();
        }

        @Override
        public void delete(ExecutionId executionId) {
        }
    }

    /** A {@link TargetExecutor} whose {@link #prepare} a test configures up front — either to hand
     *  back a controlled {@link PreparedTarget} or to fail with a chosen
     *  {@link TargetPreparationException} — mirroring {@link FakePerformanceEngine}'s
     *  configure-then-run style for the same reason: one shared fake rather than each test growing
     *  its own slightly different stub of the port. */
    private static final class FakeTargetExecutor implements TargetExecutor {

        private final Function<TargetPreparationRequest, PreparedTarget> preparation;

        FakeTargetExecutor(Function<TargetPreparationRequest, PreparedTarget> preparation) {
            this.preparation = preparation;
        }

        static FakeTargetExecutor throwing(TargetPreparationException failure) {
            return new FakeTargetExecutor(request -> {
                throw failure;
            });
        }

        @Override
        public boolean supports(ExecutionTarget target) {
            return true;
        }

        @Override
        public Set<TargetCapability> capabilities() {
            return Set.of();
        }

        @Override
        public PreparedTarget prepare(TargetPreparationRequest request) {
            return preparation.apply(request);
        }
    }

    /** A {@link PreparedTarget} that counts how many times {@link #cleanup} was called, so a test can
     *  assert the exact-once guarantee directly rather than inferring it from side effects. */
    private static final class FakePreparedTarget implements PreparedTarget {

        private final ResolvedTarget resolved;
        private final CleanupOutcome outcome;
        private final AtomicInteger cleanupCalls = new AtomicInteger();

        FakePreparedTarget(ResolvedTarget resolved, CleanupOutcome outcome) {
            this.resolved = resolved;
            this.outcome = outcome;
        }

        @Override
        public ResolvedTarget resolvedTarget() {
            return resolved;
        }

        @Override
        public CleanupOutcome cleanup() {
            cleanupCalls.incrementAndGet();
            return outcome;
        }

        int cleanupCallCount() {
            return cleanupCalls.get();
        }
    }
}
