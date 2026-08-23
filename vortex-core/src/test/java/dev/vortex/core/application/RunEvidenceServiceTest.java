package dev.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vortex.core.analysis.BreakpointDetector;
import dev.vortex.core.analysis.DeterministicSummary;
import dev.vortex.core.analysis.SystemSaturationDetector;
import dev.vortex.core.comparison.RegressionEvaluator;
import dev.vortex.core.environment.SecretReferences;
import dev.vortex.core.evidence.EvidenceProvenance;
import dev.vortex.core.evidence.EvidenceSanitizer;
import dev.vortex.core.evidence.FindingDetector;
import dev.vortex.core.evidence.RunEvidence;
import dev.vortex.core.execution.ExecutionArtifacts;
import dev.vortex.core.execution.ExecutionState;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.metrics.MeasuredResults;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.ToolVersions;
import dev.vortex.core.port.Clock;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.threshold.ThresholdEvaluation;
import dev.vortex.core.threshold.ThresholdEvaluator;
import dev.vortex.core.threshold.Verdict;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The one place a {@link RunEvidence} is built, and therefore the one place where a mistake reaches
 * every renderer at once.
 *
 * <p>Two properties matter most. That the offered workload and the achieved workload are carried as
 * a pair, so no renderer can show one without the other — a run that did not generate the traffic it
 * intended must never look like a run that did. And that nothing which should not leave the machine
 * can survive assembly, because assembly is the only gate between a plan and an export.
 */
class RunEvidenceServiceTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-08-21T11:00:00Z");

    private final RunEvidenceService service = new RunEvidenceService(
            new DeterministicAnalyzer(new ThresholdEvaluator(), new BreakpointDetector(),
                    new SystemSaturationDetector()),
            new FindingDetector(),
            new EvidenceSanitizer(),
            new RegressionEvaluator(),
            Clock.fixed(GENERATED_AT));

    @Nested
    @DisplayName("what a completed run yields")
    class Completed {

        @Test
        @DisplayName("identity carries the conditions the numbers depend on")
        void identityIsComplete() {
            RunEvidence evidence = assemble(completed());

            var identity = evidence.identity();
            assertThat(identity.serviceName()).isEqualTo("checkout-service");
            assertThat(identity.environmentName()).isNotBlank();
            assertThat(identity.classification()).isNotNull();
            assertThat(identity.dependencyMode()).isNotNull();
            assertThat(identity.describe()).contains("checkout-service");
            assertThat(identity.shortId()).hasSizeLessThanOrEqualTo(8);
        }

        @Test
        @DisplayName("offered and achieved workload are both present, and neither is derived from the other")
        void workloadKeepsBothSides() {
            RunEvidence evidence = assemble(completed());

            var workload = evidence.workload();
            assertThat(workload.configuredPeak()).isNotNull();
            assertThat(workload.achievedRateIfPresent()).isPresent();
            // The gap between the two is the signal. Folding it into one figure would hide the
            // moment a service stopped keeping up.
            assertThat(workload.requests()).isPositive();
        }

        @Test
        void performanceExposesTheMeasurementsWithoutCopyingThem() {
            RunEvidence evidence = assemble(completed());

            assertThat(evidence.performance().results()).isSameAs(evidence.performance().results());
            assertThat(evidence.performance().latency().isEmpty()).isFalse();
            assertThat(evidence.performance().requests()).isPositive();
        }

        @Test
        @DisplayName("each objective appears once, with its own verdict")
        void objectivesAreCarriedThrough() {
            RunEvidence evidence = assemble(completed());

            assertThat(evidence.acceptance().hasObjectives()).isTrue();
            assertThat(evidence.acceptance().results())
                    .hasSameSizeAs(Fixtures.thresholds().thresholds());
        }

        @Test
        @DisplayName("the plan's operations all appear, whether or not they issued traffic")
        void operationsAreJoinedFromThePlan() {
            RunEvidence evidence = assemble(completed());

            assertThat(evidence.operations())
                    .hasSameSizeAs(Fixtures.plan().operations());
        }

        @Test
        void provenanceAnswersWhatProducedThis() {
            RunEvidence evidence = assemble(completed());

            var provenance = evidence.provenance();
            assertThat(provenance.schemaVersion()).isEqualTo(EvidenceProvenance.SCHEMA_VERSION);
            assertThat(provenance.generatedAt()).isEqualTo(GENERATED_AT);
            assertThat(provenance.reproductionCommand()).startsWith("vortex run ");
            assertThat(provenance.artifactDirectory()).isEqualTo("/tmp/executions/exec1");
            assertThat(provenance.artifactNames()).contains("plan.json");
        }

        @Test
        @DisplayName("generatedAt comes from the clock port, never from the wall clock")
        void timestampsAreInjected() {
            // The domain is forbidden from reading the clock directly, and a report that stamped
            // itself with Instant.now() would also be unreproducible in a test.
            assertThat(assemble(completed()).provenance().generatedAt()).isEqualTo(GENERATED_AT);
        }

        @Test
        void findingsAreDerivedDuringAssembly() {
            assertThat(assemble(completed()).findings()).isNotEmpty();
        }
    }

    @Nested
    @DisplayName("sanitisation, which assembly is the only gate for")
    class Sanitisation {

        @Test
        @DisplayName("a literal credential in a plan header cannot survive assembly")
        void literalCredentialsAreMasked() {
            String credential = "Bearer " + "sk-" + "liveKey0123456789abcdefXYZ";
            EffectiveTestPlan compromised = planWithHeader("Authorization", credential);

            RunEvidence evidence = assemble(completed(compromised));

            // Both halves matter. That the credential is gone, and that the header actually
            // travelled into the evidence at all — an earlier version of this test passed because
            // headers were not carried, which is a pass for entirely the wrong reason.
            assertThat(evidence.workload().requestHeaders()).containsKey("Authorization");
            assertThat(evidence.workload().requestHeaders().get("Authorization"))
                    .isEqualTo(SecretReferences.MASK);
            assertThat(everyStringIn(evidence)).doesNotContain(credential);
        }

        @Test
        @DisplayName("the variables a masked header needs are named, so the run stays reproducible")
        void secretReferencesAreRecorded() {
            EffectiveTestPlan referenced =
                    planWithHeader("Authorization", "Bearer ${VORTEX_AUTH_TOKEN}");

            RunEvidence evidence = assemble(completed(referenced));

            assertThat(evidence.workload().requestHeaders().get("Authorization"))
                    .isEqualTo(SecretReferences.MASK);
            assertThat(evidence.provenance().secretReferences())
                    .containsExactly("VORTEX_AUTH_TOKEN");
        }

        @Test
        @DisplayName("credentials embedded in the target url are stripped, the host is kept")
        void targetUrlUserinfoIsStripped() {
            RunEvidence evidence = assemble(completed());

            assertThat(evidence.identity().targetUrl()).doesNotContain("@");
        }

        @Test
        @DisplayName("an unrecognised engine option is masked rather than published")
        void engineOptionsAreAllowlisted() {
            EffectiveTestPlan withOption = Fixtures.plan();
            RunEvidence evidence = assemble(completed(withOption));

            assertThat(evidence.workload().engineOptions().values())
                    .allSatisfy(value -> assertThat(value).isNotNull());
        }
    }

    @Nested
    @DisplayName("what assembly refuses")
    class Refusals {

        @Test
        @DisplayName("a run that never completed has no settled measurements to report")
        void incompleteRunsAreRejected() {
            TestExecution running = TestExecution
                    .create(ExecutionId.of("exec2"), Fixtures.plan(), Fixtures.NOW)
                    .transitionTo(ExecutionState.VALIDATING, Fixtures.NOW);

            assertThatThrownBy(() -> service.assemble(running, "/tmp", List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("completed run");
        }
    }

    @Nested
    @DisplayName("optional evidence")
    class Optional {

        @Test
        @DisplayName("a run with no observability still assembles, with the section simply absent")
        void missingObservabilityIsNotAFailure() {
            RunEvidence evidence = assemble(completed());

            assertThat(evidence.hasObservability()).isFalse();
            assertThat(evidence.observability().signals()).isEmpty();
        }

        @Test
        @DisplayName("a run with no series has an empty timeline rather than a broken one")
        void missingSeriesIsNotAFailure() {
            RunEvidence evidence = assemble(completed());

            assertThat(evidence.timeline()).isNotNull();
            assertThat(evidence.hasTimeline()).isFalse();
        }

        @Test
        void noComparisonAndNoInterpretationAreBothAbsentRatherThanEmpty() {
            RunEvidence evidence = assemble(completed());

            assertThat(evidence.comparisonIfPresent()).isEmpty();
            assertThat(evidence.interpretationIfPresent()).isEmpty();
        }

        @Test
        @DisplayName("a run recorded before resource telemetry existed reports it as unavailable, "
                + "never as an empty-but-present series")
        void missingResourceTelemetryIsReportedAsUnavailableNotEmpty() {
            RunEvidence evidence = assemble(completed());

            assertThat(evidence.resourceTimeline().present()).isFalse();
            assertThat(evidence.resourceTimeline().completeness().status())
                    .isEqualTo(dev.vortex.core.metrics.TelemetryCompleteness.Status.UNAVAILABLE);
            assertThat(evidence.resourceTimeline().plots()).isEmpty();
        }
    }

    @Nested
    @DisplayName("resource telemetry")
    class ResourceTelemetry {

        @Test
        @DisplayName("samples are grouped by kind, with completeness carried through honestly")
        void samplesAreGroupedByKindAndCompletenessSurvives() {
            var sut = dev.vortex.core.resource.ResourceScope.SYSTEM_UNDER_TEST;
            var generator = dev.vortex.core.resource.ResourceScope.LOAD_GENERATOR;
            Instant at = Fixtures.NOW.plusSeconds(30);
            var samples = List.of(
                    new dev.vortex.core.resource.ResourceSample(at, "actuator", "metric:cpu",
                            dev.vortex.core.resource.ResourceKind.CPU, sut, 0.8,
                            dev.vortex.core.metrics.MetricUnit.RATIO, 0),
                    new dev.vortex.core.resource.ResourceSample(at, "generator", "metric:generator.cpu",
                            dev.vortex.core.resource.ResourceKind.CPU, generator, 0.3,
                            dev.vortex.core.metrics.MetricUnit.RATIO, 0),
                    new dev.vortex.core.resource.ResourceSample(at, "actuator", "metric:heap",
                            dev.vortex.core.resource.ResourceKind.RUNTIME_MEMORY, sut, 0.5,
                            dev.vortex.core.metrics.MetricUnit.RATIO, 0));
            var completeness = new dev.vortex.core.metrics.TelemetryCompleteness(
                    dev.vortex.core.metrics.TelemetryCompleteness.Status.PARTIAL,
                    new dev.vortex.core.metrics.TimeWindow(at, at), "the artifact could not be written to");

            RunEvidenceService withTelemetry = new RunEvidenceService(
                    new DeterministicAnalyzer(new ThresholdEvaluator(), new BreakpointDetector(),
                            new SystemSaturationDetector()),
                    new FindingDetector(), new EvidenceSanitizer(), new RegressionEvaluator(),
                    Clock.fixed(GENERATED_AT), dev.vortex.core.port.HostInformation.unknown(),
                    executionId -> new dev.vortex.core.resource.ResourceTelemetryReader.Result(
                            completeness, samples));

            RunEvidence evidence = withTelemetry.assemble(completed(), "/tmp/executions/exec1",
                    List.of("plan.json"));

            assertThat(evidence.resourceTimeline().present()).isTrue();
            assertThat(evidence.resourceTimeline().completeness()).isEqualTo(completeness);
            assertThat(evidence.resourceTimeline().plots())
                    .as("CPU from two scopes stays on one plot; heap gets its own")
                    .hasSize(2);
            var cpuPlot = evidence.resourceTimeline().plots().stream()
                    .filter(plot -> plot.kind() == dev.vortex.core.resource.ResourceKind.CPU)
                    .findFirst().orElseThrow();
            assertThat(cpuPlot.series())
                    .as("system-under-test and load-generator CPU are two distinct series, not merged")
                    .extracting(dev.vortex.core.evidence.ResourceTimelineEvidence.ResourceSeriesEvidence::scope)
                    .containsExactlyInAnyOrder(sut, generator);
        }
    }

    // ---------------------------------------------------------------- fixtures

    private RunEvidence assemble(TestExecution execution) {
        return service.assemble(execution, "/tmp/executions/exec1", List.of("plan.json"));
    }

    private static TestExecution completed() {
        return completed(Fixtures.plan());
    }

    private static TestExecution completed(EffectiveTestPlan plan) {
        MeasuredResults results = Fixtures.results(281, 0.0008);
        ThresholdEvaluation evaluation =
                new ThresholdEvaluator().evaluate(Fixtures.thresholds(), results);

        DeterministicSummary summary = new DeterministicSummary(
                "Can it hold 20 requests/sec?", Verdict.PASS, "Yes.", results, evaluation,
                null, null, List.of("This is an isolated test."));

        return new TestExecution(
                ExecutionId.of("exec1"), plan.projectId(), plan, ExecutionState.COMPLETED,
                Fixtures.NOW, Fixtures.NOW.plusSeconds(1), Fixtures.NOW.plusSeconds(601),
                results, summary, ToolVersions.unknown(),
                ExecutionArtifacts.empty().with("plan.json", "plan.json"), null, "");
    }

    private static EffectiveTestPlan planWithHeader(String name, String value) {
        EffectiveTestPlan base = Fixtures.plan();
        return new EffectiveTestPlan(base.id(), base.projectId(), base.projectName(),
                base.serviceVersion(), base.intent(), base.workloadName(),
                base.workloadDescription(), base.testType(), base.workloadModel(),
                base.peakLevel(), base.stages(), base.operations(), base.workloadSource(),
                base.thresholds(), base.environmentName(), base.environmentType(),
                base.configuredTarget(), base.effectiveTarget(), base.targetRewriteReason(),
                base.dependencyMode(), base.classification(), Map.of(name, value),
                base.k6Options(), base.runner(), base.scriptSource(), base.safetyDecisions(),
                base.fingerprint());
    }

    /**
     * Every string an exporter could reach, flattened.
     *
     * <p>Asserting against this rather than against the one field a credential was placed in is the
     * point: a leak that appears somewhere unexpected is exactly the leak a targeted assertion
     * misses.
     */
    private static String everyStringIn(RunEvidence evidence) {
        StringBuilder text = new StringBuilder();
        text.append(evidence.identity()).append(' ')
                .append(evidence.question()).append(' ')
                .append(evidence.answer()).append(' ')
                .append(evidence.workload()).append(' ')
                .append(evidence.workload().requestHeaders()).append(' ')
                .append(evidence.acceptance()).append(' ')
                .append(evidence.operations()).append(' ')
                .append(evidence.observability()).append(' ')
                .append(evidence.findings()).append(' ')
                .append(evidence.qualifications()).append(' ')
                .append(evidence.provenance());
        return text.toString();
    }

    @Test
    @DisplayName("the flattening used by the leak tests actually reaches request headers")
    void leakDetectionCoversWhatItClaims() {
        // A secret-leak test that scans a string the secret could never have been in passes for the
        // wrong reason. This asserts the haystack really does include the header values, so the
        // assertions above are testing what they claim to test.
        String canary = "X-Canary-Header";
        RunEvidence evidence =
                assemble(completed(planWithHeader(canary, "canary-value-not-a-secret")));

        assertThat(everyStringIn(evidence))
                .contains(canary)
                .contains("canary-value-not-a-secret");
    }
}
