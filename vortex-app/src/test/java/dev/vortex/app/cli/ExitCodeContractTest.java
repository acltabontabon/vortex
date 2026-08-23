package dev.vortex.app.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vortex.app.service.TestRunner;
import dev.vortex.core.analysis.DeterministicSummary;
import dev.vortex.core.application.CalibrationService;
import dev.vortex.core.application.ComparisonService;
import dev.vortex.core.calibration.CalibrationPolicy;
import dev.vortex.core.port.Clock;
import dev.vortex.core.application.ProjectService;
import dev.vortex.core.comparison.RegressionEvaluator;
import dev.vortex.core.fixtures.InMemoryExecutions;
import dev.vortex.core.execution.ExecutionState;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.validity.RunQualityAssessment;
import dev.vortex.core.validity.ValidityEffect;
import dev.vortex.core.validity.ValidityFinding;
import dev.vortex.core.validity.ValidityReason;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.metrics.LatencyPercentiles;
import dev.vortex.core.metrics.MeasuredResults;
import dev.vortex.core.metrics.MetricSeries;
import dev.vortex.core.metrics.TimeWindow;
import dev.vortex.core.port.ConfigurationStore;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.shared.ProjectId;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.threshold.ThresholdEvaluator;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Exit codes are Vortex's contract with continuous integration.
 *
 * <p>The distinction that matters most is between {@code 2} and {@code 1}: a pipeline must be able
 * to tell "the service did not meet its objectives" apart from "Vortex could not run the test".
 * Collapse both into 1 and a broken k6 installation looks exactly like a performance regression,
 * which is how a performance gate stops being taken seriously.
 *
 * <p>Changing any value here is a breaking change.
 */
class ExitCodeContractTest {

    private DoctorReport doctor;
    private ProjectService projects;
    private ConfigurationStore configurationStore;
    private TestRunner testRunner;
    private ComparisonService comparisons;
    private CalibrationService calibration;

    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private VortexCommandRunner runner;

    @BeforeEach
    void setUp() {
        doctor = mock(DoctorReport.class);
        projects = mock(ProjectService.class);
        configurationStore = mock(ConfigurationStore.class);
        testRunner = mock(TestRunner.class);
        comparisons = new ComparisonService(new InMemoryExecutions(), new RegressionEvaluator());
        // No observation sources registered: calibrate should then refuse with a remedy rather
        // than fail, which is itself part of the exit-code contract.
        calibration = new CalibrationService(List.of(), Clock.systemUtc());

        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        runner = new VortexCommandRunner(doctor, projects, configurationStore, testRunner,
                comparisons, mock(dev.vortex.app.report.RunExporter.class), calibration,
                new CalibrationPolicy(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("the documented values")
    class DocumentedValues {

        @Test
        void areStable() {
            assertThat(ExitCode.SUCCESS.value()).isZero();
            assertThat(ExitCode.ERROR.value()).isEqualTo(1);
            assertThat(ExitCode.THRESHOLDS_VIOLATED.value()).isEqualTo(2);
            assertThat(ExitCode.VALIDATION_FAILED.value()).isEqualTo(3);
            assertThat(ExitCode.CANCELLED.value()).isEqualTo(4);
            assertThat(ExitCode.EVIDENCE_NOT_VALID.value()).isEqualTo(5);
        }

        @Test
        @DisplayName("a threshold violation is distinguishable from a Vortex failure")
        void violationIsNotAnError() {
            assertThat(ExitCode.THRESHOLDS_VIOLATED.value()).isNotEqualTo(ExitCode.ERROR.value());
        }
    }

    @Nested
    @DisplayName("doctor")
    class Doctor {

        @Test
        void reportsSuccessWhenEverythingRequiredIsPresent() {
            var checks = List.of(new DoctorReport.Check(
                    "Load generator", DoctorReport.Check.Status.OK, "k6 v2.2.0", "", true));
            when(doctor.run()).thenReturn(checks);
            when(doctor.isReady(checks)).thenReturn(true);

            assertThat(runner.run(new String[] {"doctor"})).isZero();
            assertThat(output()).contains("Vortex is ready to run tests");
        }

        @Test
        @DisplayName("a missing requirement is a validation failure, not a Vortex error")
        void reportsValidationFailureWhenSomethingRequiredIsMissing() {
            var checks = List.of(new DoctorReport.Check(
                    "Load generator", DoctorReport.Check.Status.PROBLEM,
                    "k6 was not available", "Install k6, then try again.", true));
            when(doctor.run()).thenReturn(checks);
            when(doctor.isReady(checks)).thenReturn(false);

            assertThat(runner.run(new String[] {"doctor"}))
                    .isEqualTo(ExitCode.VALIDATION_FAILED.value());
            assertThat(output()).contains("Install k6");
        }
    }

    @Nested
    @DisplayName("calibrate")
    class Calibrate {

        @Test
        @DisplayName("nothing configured to ask is a validation failure, not a Vortex error")
        void refusesWithAValidationFailureWhenNoSourceIsConfigured() {
            when(projects.all()).thenReturn(List.of(Fixtures.project()));
            when(projects.configuration(any())).thenReturn(Fixtures.configuration());
            when(projects.catalog(any())).thenReturn(Optional.of(Fixtures.catalog()));

            assertThat(runner.run(new String[] {"calibrate"}))
                    .isEqualTo(ExitCode.VALIDATION_FAILED.value());
        }

        @Test
        @DisplayName("a refusal says what happened, why, and what to do next")
        void refusalCarriesARemedy() {
            when(projects.all()).thenReturn(List.of(Fixtures.project()));
            when(projects.configuration(any())).thenReturn(Fixtures.configuration());
            when(projects.catalog(any())).thenReturn(Optional.of(Fixtures.catalog()));

            runner.run(new String[] {"calibrate"});

            assertThat(output())
                    .contains("Why")
                    .contains("Try")
                    .contains("observation:");
        }

        @Test
        @DisplayName("without --write it writes nothing at all")
        void writesNothingWithoutBeingAsked() {
            when(projects.all()).thenReturn(List.of(Fixtures.project()));
            when(projects.configuration(any())).thenReturn(Fixtures.configuration());
            when(projects.catalog(any())).thenReturn(Optional.of(Fixtures.catalog()));

            runner.run(new String[] {"calibrate"});

            // The whole reason vortex.yaml is in version control is that somebody can review a
            // change to it. A calibration that edited it on its own would be a change nobody saw.
            verify(projects, never()).saveConfiguration(any(), any());
        }

        @Test
        void anUnreadableWindowIsRejectedBeforeAnythingIsAsked() {
            assertThat(runner.run(new String[] {"calibrate", "--window", "a while"}))
                    .isEqualTo(ExitCode.VALIDATION_FAILED.value());
            assertThat(output()).contains("--window");
        }
    }

    @Nested
    @DisplayName("validate")
    class Validate {

        @Test
        void succeedsForAValidConfiguration() {
            when(configurationStore.load(any())).thenReturn(
                    ConfigurationStore.LoadResult.loaded(Fixtures.configuration(), "vortex.yaml"));

            assertThat(runner.run(new String[] {"validate", "."})).isZero();
            assertThat(output()).contains("is valid");
        }

        @Test
        void reportsValidationFailureForAnInvalidConfiguration() {
            when(configurationStore.load(any())).thenReturn(
                    ConfigurationStore.LoadResult.invalid(
                            List.of("workloads.peak.arrivalRate must be greater than 0"),
                            "vortex.yaml"));

            assertThat(runner.run(new String[] {"validate", "."}))
                    .isEqualTo(ExitCode.VALIDATION_FAILED.value());
            assertThat(output()).contains("must be greater than 0");
        }

        @Test
        @DisplayName("a missing file explains where Vortex looked")
        void reportsValidationFailureWhenThereIsNoConfiguration() {
            when(configurationStore.load(any())).thenReturn(
                    ConfigurationStore.LoadResult.missing("/tmp/.vortex/vortex.yaml"));

            assertThat(runner.run(new String[] {"validate", "/tmp"}))
                    .isEqualTo(ExitCode.VALIDATION_FAILED.value());
            assertThat(output()).contains("/tmp/.vortex/vortex.yaml");
        }
    }

    @Nested
    @DisplayName("run")
    class Run {

        @BeforeEach
        void aProjectExists() {
            when(projects.all()).thenReturn(List.of(Fixtures.project()));
            when(projects.configuration(any(ProjectId.class))).thenReturn(Fixtures.configuration());
        }

        private TestExecution executionWith(long p95Millis, double errorFraction) {
            var results = Fixtures.results(p95Millis, errorFraction);
            var evaluation = new ThresholdEvaluator().evaluate(Fixtures.thresholds(), results);
            var summary = new DeterministicSummary("Can it?", evaluation.overall(),
                    evaluation.passed() ? "Yes." : "No.", results, evaluation, null, null, List.of());

            return new TestExecution(ExecutionId.of("e1"), ProjectId.of("checkout"), Fixtures.plan(),
                    ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW,
                    Fixtures.NOW.plusSeconds(600), results, summary, null, null, null, "");
        }

        private void outcome(TestRunner.Outcome outcome) {
            when(testRunner.runToCompletion(any(), any(), any())).thenReturn(outcome);
        }

        @Test
        @DisplayName("0 when every objective was met")
        void successWhenObjectivesAreMet() {
            outcome(new TestRunner.Outcome(executionWith(281, 0.0008), false, false, List.of(), "/tmp"));

            assertThat(runner.run(new String[] {"run", "baseline"})).isZero();
            assertThat(output()).contains("Pass");
        }

        @Test
        @DisplayName("2 when an objective was violated — the test worked, the service did not")
        void thresholdsViolated() {
            outcome(new TestRunner.Outcome(executionWith(900, 0.0008), false, false, List.of(), "/tmp"));

            assertThat(runner.run(new String[] {"run", "stress"}))
                    .isEqualTo(ExitCode.THRESHOLDS_VIOLATED.value());
            assertThat(output()).contains("Fail");
        }

        @Test
        @DisplayName("2 when an objective could not be evaluated — unmeasured is not passed")
        void unevaluatedObjectivesDoNotPass() {
            var results = new MeasuredResults(
                    new TimeWindow(Fixtures.NOW, Fixtures.NOW.plusSeconds(60)),
                    RequestsPerSecond.of(20), RequestsPerSecond.of(20), 200, 0,
                    // p99 was never measured, so one objective cannot be checked.
                    LatencyPercentiles.builder().atMillis(95, 120).build(),
                    Map.of(), MetricSeries.empty(), List.of());
            var evaluation = new ThresholdEvaluator().evaluate(Fixtures.thresholds(), results);
            var summary = new DeterministicSummary("Can it?", evaluation.overall(),
                    "Undetermined.", results, evaluation, null, null, List.of());
            var execution = new TestExecution(ExecutionId.of("e1"), ProjectId.of("checkout"),
                    Fixtures.plan(), ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW,
                    Fixtures.NOW.plusSeconds(60), results, summary, null, null, null, "");

            outcome(new TestRunner.Outcome(execution, false, false, List.of(), "/tmp"));

            assertThat(runner.run(new String[] {"run", "baseline"}))
                    .isEqualTo(ExitCode.THRESHOLDS_VIOLATED.value());
            assertThat(output()).contains("does not constitute a pass");
        }

        @Test
        @DisplayName("3 when preflight failed — no traffic was generated, so nothing is known")
        void preflightFailure() {
            outcome(new TestRunner.Outcome(null, true, false,
                    List.of("Execution engine: k6 was not available"), ""));

            assertThat(runner.run(new String[] {"run", "baseline"}))
                    .isEqualTo(ExitCode.VALIDATION_FAILED.value());
            assertThat(output()).contains("no traffic was generated");
        }

        @Test
        @DisplayName("4 when the run was cancelled")
        void cancelled() {
            outcome(new TestRunner.Outcome(null, false, true, List.of(), ""));

            assertThat(runner.run(new String[] {"run", "baseline"}))
                    .isEqualTo(ExitCode.CANCELLED.value());
        }

        @Test
        void validationFailureWhenNoProjectExists() {
            when(projects.all()).thenReturn(List.of());

            assertThat(runner.run(new String[] {"run", "baseline"}))
                    .isEqualTo(ExitCode.VALIDATION_FAILED.value());
        }

        @Test
        @DisplayName("5 when the run did not measure what it claims to — even with every objective met")
        void invalidEvidenceFailsClosed() {
            // The case the whole phase exists for. Objectives met, verdict PASS, and the load was
            // never generated: returning 0 here is what green-lights a deploy on a run that
            // measured Vortex's own hardware.
            outcome(new TestRunner.Outcome(
                    executionWith(281, 0.0008).withQuality(invalid()), false, false, List.of(),
                    "/tmp"));

            assertThat(runner.run(new String[] {"run", "baseline"}))
                    .isEqualTo(ExitCode.EVIDENCE_NOT_VALID.value());
            assertThat(output()).contains("did not measure what it claims to");
        }

        @Test
        @DisplayName("a degraded run exits on its verdict, with the qualification printed beside it")
        void degradationDoesNotChangeTheExitCode() {
            // Failing a build because telemetry was incomplete would train teams to stop collecting
            // telemetry, which is the opposite of the intent.
            outcome(new TestRunner.Outcome(
                    executionWith(281, 0.0008).withQuality(degraded()), false, false, List.of(),
                    "/tmp"));

            assertThat(runner.run(new String[] {"run", "baseline"})).isZero();
            assertThat(output()).contains("held for 2m; an average-load test requires 5m");
        }

        @Test
        @DisplayName("and a degraded run that also violated an objective still exits 2")
        void degradationDoesNotMaskAViolation() {
            outcome(new TestRunner.Outcome(
                    executionWith(900, 0.0008).withQuality(degraded()), false, false, List.of(),
                    "/tmp"));

            assertThat(runner.run(new String[] {"run", "stress"}))
                    .isEqualTo(ExitCode.THRESHOLDS_VIOLATED.value());
        }

        private RunQualityAssessment invalid() {
            return RunQualityAssessment.of(List.of(new ValidityFinding(
                    ValidityReason.OFFERED_LOAD_NOT_GENERATED, ValidityEffect.WITHHOLDS_CAPACITY,
                    "The load generator could not start 4812 units of work it was asked to start.",
                    List.of())));
        }

        private RunQualityAssessment degraded() {
            return RunQualityAssessment.of(List.of(new ValidityFinding(
                    ValidityReason.RUN_TOO_SHORT, ValidityEffect.QUALIFIES,
                    "held for 2m; an average-load test requires 5m before its conclusions stand "
                            + "unqualified.",
                    List.of())));
        }
    }

    @Nested
    @DisplayName("argument handling")
    class Arguments {

        @Test
        void unknownCommandIsAnError() {
            assertThat(runner.run(new String[] {"nonsense"})).isEqualTo(ExitCode.ERROR.value());
            assertThat(output()).contains("Unknown command");
        }

        @Test
        void noArgumentsPrintsUsage() {
            assertThat(runner.run(new String[] {})).isZero();
            assertThat(output()).contains("vortex doctor").contains("Exit codes");
        }

    }
}
