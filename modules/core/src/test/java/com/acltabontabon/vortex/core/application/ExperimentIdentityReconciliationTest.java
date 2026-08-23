package com.acltabontabon.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.analysis.BreakpointDetector;
import com.acltabontabon.vortex.core.analysis.SystemSaturationDetector;
import com.acltabontabon.vortex.core.execution.ExecutionState;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.fixtures.InMemoryExecutions;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.plan.ExperimentIdentity;
import com.acltabontabon.vortex.core.port.ArtifactStore;
import com.acltabontabon.vortex.core.port.Clock;
import com.acltabontabon.vortex.core.port.PerformanceEngine;
import com.acltabontabon.vortex.core.port.TelemetryCollector;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.threshold.ThresholdEvaluator;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeping a team's comparison history alive across a change to the identity contract.
 *
 * <p>The failure this prevents is silent, which is what makes it worth a test: when every stored
 * fingerprint indexes its run under a hash nothing will look up again, Vortex reports "no previous
 * compatible run exists" — the same sentence it uses when a run genuinely is the first. Nobody
 * would notice their history had gone.
 */
class ExperimentIdentityReconciliationTest {

    private InMemoryExecutions executions;
    private ExecutionService service;

    @BeforeEach
    void setUp() {
        executions = new InMemoryExecutions();
        service = new ExecutionService(
                new NoEngine(),
                new DeterministicAnalyzer(new ThresholdEvaluator(), new BreakpointDetector(),
                        new SystemSaturationDetector()),
                executions,
                new NoArtifacts(),
                new com.acltabontabon.vortex.core.fixtures.FakeDatasetStore(),
                TelemetryCollector.none(),
                Clock.fixed(Fixtures.NOW),
                List.of(),
                new com.acltabontabon.vortex.core.resource.LoadGeneratorResourceBudgetResolver(
                        com.acltabontabon.vortex.core.port.HostInformation.unknown()),
                com.acltabontabon.vortex.core.resource.LoadGeneratorResourceBudget::automatic);
    }

    private TestExecution stored(String id, EffectiveTestPlan plan) {
        return new TestExecution(ExecutionId.of(id), plan.projectId(), plan,
                ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW, Fixtures.NOW.plusSeconds(600),
                Fixtures.results(280, 0.001), null, null, null, null, "");
    }

    @Test
    @DisplayName("a run left without an index is re-indexed from its stored plan")
    void unindexedRunsAreRecovered() {
        var plan = Fixtures.plan();
        executions.saveUnindexed(stored("older", plan));

        assertThat(service.reconcileExperimentIdentity()).isEqualTo(1);
        assertThat(executions.indexedFingerprint(ExecutionId.of("older")))
                .isEqualTo(ExperimentIdentity.fingerprintOf(plan).hash());
    }

    @Test
    @DisplayName("and is then findable again as a baseline")
    void aRecoveredRunBecomesComparableAgain() {
        var plan = Fixtures.plan();
        var older = executions.saveUnindexed(stored("older", plan));
        var newer = executions.save(new TestExecution(ExecutionId.of("newer"), plan.projectId(),
                plan, ExecutionState.COMPLETED, Fixtures.NOW.plusSeconds(3600),
                Fixtures.NOW.plusSeconds(3600), Fixtures.NOW.plusSeconds(4200),
                Fixtures.results(280, 0.001), null, null, null, null, ""));

        var comparisons = new ComparisonService(executions,
                new com.acltabontabon.vortex.core.comparison.RegressionEvaluator());
        assertThat(comparisons.previousCompatible(newer))
                .as("before reconciliation the older run is invisible")
                .isEmpty();

        service.reconcileExperimentIdentity();

        assertThat(comparisons.previousCompatible(newer))
                .hasValueSatisfying(found -> assertThat(found.id()).isEqualTo(older.id()));
    }

    @Test
    @DisplayName("an ordinary boot re-indexes nothing and says so")
    void anUpToDateWorkspaceIsUntouched() {
        executions.save(stored("current", Fixtures.plan()));

        assertThat(service.reconcileExperimentIdentity()).isZero();
    }

    @Test
    @DisplayName("the stored plan is evidence and is never rewritten to match a later opinion")
    void theStoredPlanIsNotEdited() {
        var plan = Fixtures.plan();
        executions.saveUnindexed(stored("older", plan));
        var before = executions.findById(ExecutionId.of("older")).orElseThrow().plan();

        service.reconcileExperimentIdentity();

        assertThat(executions.findById(ExecutionId.of("older")).orElseThrow().plan())
                .isEqualTo(before);
    }

    // ------------------------------------------------------------------ stubs

    /** Never used: reconciliation touches no engine. */
    private static final class NoEngine implements PerformanceEngine {

        @Override
        public EngineAvailability availability() {
            return new EngineAvailability(false, "", "", "");
        }

        @Override
        public com.acltabontabon.vortex.core.plan.ToolVersions toolVersions() {
            return com.acltabontabon.vortex.core.plan.ToolVersions.unknown();
        }

        @Override
        public ValidationResult validate(EffectiveTestPlan plan) {
            return ValidationResult.ok();
        }

        @Override
        public EngineOutcome execute(ExecutionId executionId, EffectiveTestPlan plan,
                com.acltabontabon.vortex.core.target.ResourceEnvelopeRequest loadGeneratorResources,
                java.util.function.Consumer<com.acltabontabon.vortex.core.execution.ExecutionProgress> progress,
                Cancellation cancellation) {
            throw new UnsupportedOperationException("not used");
        }
    }

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
