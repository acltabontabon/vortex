package dev.vortex.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vortex.core.application.CapacityService;
import dev.vortex.core.application.DeterministicAnalyzer;
import dev.vortex.core.application.ExecutionService;
import dev.vortex.core.application.PlanResolver;
import dev.vortex.core.application.PreflightCheck;
import dev.vortex.core.application.PreflightReport;
import dev.vortex.core.application.PreflightService;
import dev.vortex.core.application.ProjectService;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.port.ArtifactStore;
import dev.vortex.core.port.Clock;
import dev.vortex.core.safety.SafetyAssessment;
import dev.vortex.core.safety.SafetyFinding;
import dev.vortex.k6.K6PerformanceEngine;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gate that stops a pipeline generating load somewhere nobody agreed to.
 *
 * <p>The web interface makes a person type the environment name before traffic reaches a shared or
 * production-like target. The command line used to treat the act of running the command as that
 * agreement — defensible for an operator at a keyboard, and wrong in continuous integration, where
 * the "operator" is a YAML file somebody merged three months ago.
 *
 * <p>So the challenge has to be answered explicitly, and matching is exact: a challenge exists to
 * make the caller name the thing they are about to do, and accepting a near-miss would turn it back
 * into "Are you sure?".
 */
class CliSafetyConfirmationTest {

    private ProjectService projects;
    private PreflightService preflight;
    private ExecutionService executions;
    private TestRunner runner;

    private static final String CHALLENGE = "performance";

    @BeforeEach
    void setUp() {
        projects = mock(ProjectService.class);
        PlanResolver planResolver = mock(PlanResolver.class);
        preflight = mock(PreflightService.class);
        executions = mock(ExecutionService.class);

        K6PerformanceEngine engine = mock(K6PerformanceEngine.class);
        when(engine.toolVersions()).thenReturn(dev.vortex.core.plan.ToolVersions.unknown());
        when(engine.targetRewriteFor(any())).thenReturn(java.util.Optional.empty());

        runner = new TestRunner(projects, planResolver, preflight, executions,
                mock(CapacityService.class), mock(DeterministicAnalyzer.class), engine,
                mock(ArtifactStore.class), Clock.fixed(Fixtures.NOW),
                mock(dev.vortex.core.application.RunEvidenceService.class),
                mock(dev.vortex.app.report.ExportRegistry.class),
                mock(dev.vortex.core.port.Repositories.ExecutionRepository.class),
                mock(EvidenceContextFactory.class));

        when(projects.find(any())).thenReturn(java.util.Optional.of(Fixtures.project()));
        when(projects.configuration(any())).thenReturn(Fixtures.configuration());
        when(projects.catalog(any())).thenReturn(java.util.Optional.of(Fixtures.catalog()));
        when(planResolver.resolve(any(), any(), any(), any())).thenReturn(challengedPlan());
        when(preflight.check(any())).thenReturn(challengedReport());
    }

    private static EffectiveTestPlan challengedPlan() {
        return Fixtures.plan();
    }

    /** A report that passes every check but demands the environment name be typed. */
    private static PreflightReport challengedReport() {
        return new PreflightReport(
                challengedPlan(),
                List.of(PreflightCheck.pass("Execution engine", "k6 v1.3.0")),
                new SafetyAssessment(List.of(SafetyFinding.challenge("target.non-local",
                        "This test targets performance",
                        "Traffic will be sent to a shared environment.", CHALLENGE))));
    }

    private TestRunner.Outcome runWith(List<String> confirmations) {
        return runner.runToCompletion(
                new TestRunner.RunRequest(Fixtures.project().id(), "average-load", "performance",
                        "", confirmations),
                report -> { },
                progress -> { });
    }

    @Test
    @DisplayName("no confirmation means no traffic, and says which flag would have allowed it")
    void aChallengedRunWithoutConfirmationDoesNotExecute() {
        var outcome = runWith(List.of());

        assertThat(outcome.preflightFailed()).isTrue();
        assertThat(outcome.execution()).isNull();
        assertThat(outcome.problems())
                .anyMatch(problem -> problem.contains("--confirm " + CHALLENGE));
        verify(executions, never()).create(any(), any(), any());
    }

    @Test
    @DisplayName("the wrong value is not a confirmation")
    void aWrongConfirmationDoesNotExecute() {
        var outcome = runWith(List.of("staging"));

        assertThat(outcome.preflightFailed()).isTrue();
        verify(executions, never()).create(any(), any(), any());
    }

    @Test
    @DisplayName("a near-miss is still wrong — the gate is not a formality")
    void aCaseMismatchDoesNotExecute() {
        var outcome = runWith(List.of("Performance"));

        assertThat(outcome.preflightFailed()).isTrue();
        verify(executions, never()).create(any(), any(), any());
    }

    @Test
    @DisplayName("the matching value lets the run proceed")
    void aMatchingConfirmationExecutes() {
        var created = mock(TestExecution.class);
        when(created.id()).thenReturn(dev.vortex.core.shared.ExecutionId.of("e1"));
        when(executions.create(any(), any(), any())).thenReturn(created);
        when(executions.run(any(), any(), any())).thenReturn(created);
        when(created.state()).thenReturn(dev.vortex.core.execution.ExecutionState.COMPLETED);

        var outcome = runWith(List.of(CHALLENGE));

        assertThat(outcome.preflightFailed()).isFalse();
        verify(executions).create(any(), any(), any());
    }

    @Test
    @DisplayName("behaviour does not depend on a terminal being attached")
    void isDeterministicHeadless() {
        // Called twice with identical input, in a process with no TTY. A gate that consulted the
        // environment would be a gate that behaved differently in CI than on a developer's machine,
        // which is the one place it must not.
        assertThat(runWith(List.of()).problems())
                .isEqualTo(runWith(List.of()).problems());
    }
}
