package dev.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.analysis.BreakpointDetector;
import dev.vortex.core.analysis.SystemSaturationDetector;
import dev.vortex.core.data.DatasetHome;
import dev.vortex.core.execution.ExecutionState;
import dev.vortex.core.execution.FailureReason;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.fixtures.FakeDatasetStore;
import dev.vortex.core.fixtures.FakePerformanceEngine;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.fixtures.InMemoryExecutions;
import dev.vortex.core.port.ArtifactStore;
import dev.vortex.core.port.Clock;
import dev.vortex.core.port.PerformanceEngine;
import dev.vortex.core.port.PerformanceEngine.EngineOutcome;
import dev.vortex.core.port.PerformanceEngine.ValidationResult;
import dev.vortex.core.port.TelemetryCollector;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.threshold.ThresholdEvaluator;
import dev.vortex.core.validity.ValidityReason;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
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
        service = new ExecutionService(
                engine,
                new DeterministicAnalyzer(new ThresholdEvaluator(), new BreakpointDetector(),
                        new SystemSaturationDetector()),
                executions,
                new NoArtifacts(),
                new FakeDatasetStore(),
                TelemetryCollector.none(),
                Clock.fixed(Fixtures.NOW));
    }

    private TestExecution created() {
        var plan = Fixtures.plan();
        return service.create(plan, "{}", DatasetHome.of(plan.projectId(), ""));
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
}
