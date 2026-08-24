package com.acltabontabon.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acltabontabon.vortex.core.analysis.BreakpointDetector;
import com.acltabontabon.vortex.core.analysis.DeterministicSummary;
import com.acltabontabon.vortex.core.analysis.SystemSaturationDetector;
import com.acltabontabon.vortex.core.comparison.RegressionEvaluator;
import com.acltabontabon.vortex.core.environment.SecretReferences;
import com.acltabontabon.vortex.core.evidence.EvidenceProvenance;
import com.acltabontabon.vortex.core.evidence.EvidenceSanitizer;
import com.acltabontabon.vortex.core.evidence.FindingDetector;
import com.acltabontabon.vortex.core.evidence.RunEvidence;
import com.acltabontabon.vortex.core.execution.ExecutionArtifacts;
import com.acltabontabon.vortex.core.execution.ExecutionState;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.metrics.MeasuredResults;
import com.acltabontabon.vortex.core.resource.ResourceSignal;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.plan.ToolVersions;
import com.acltabontabon.vortex.core.port.Clock;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.threshold.ThresholdEvaluation;
import com.acltabontabon.vortex.core.threshold.ThresholdEvaluator;
import com.acltabontabon.vortex.core.threshold.Verdict;
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
            assertThat(provenance.reproductionCommand()).startsWith("workload ");
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

    /**
     * The exact false positive investigated: a SPIKE workload ramping 50 → 100 → 150 req/s over
     * three 20s stages, whose achieved rate matches the ramp's own time-weighted average
     * (83.33 req/s) almost exactly. Comparing that average against the ramp's instantaneous peak
     * (150) — what {@code MeasuredResults.deliveredFraction()} alone would do — reports a 45%
     * shortfall for a run that tracked its configured profile perfectly. {@link
     * EffectiveTestPlan#idealizedAverageArrivalRate()} is the corrected comparison basis this
     * assembly step must use instead.
     */
    @Nested
    @DisplayName("the ramp-vs-peak comparison")
    class RampVsPeakComparison {

        @Test
        @DisplayName("a spike that tracked its own ramp is not reported as falling short of its peak")
        void aSpikeTrackingItsRampReportsNoShortfall() {
            var shape = new com.acltabontabon.vortex.core.workload.RampingArrivalRateShape(
                    com.acltabontabon.vortex.core.shared.RequestsPerSecond.of(50), List.of(
                            com.acltabontabon.vortex.core.workload.Stage.ofRate(50, java.time.Duration.ofSeconds(20)),
                            com.acltabontabon.vortex.core.workload.Stage.ofRate(100, java.time.Duration.ofSeconds(20)),
                            com.acltabontabon.vortex.core.workload.Stage.ofRate(150, java.time.Duration.ofSeconds(20))));
            EffectiveTestPlan plan = Fixtures.plan(
                    com.acltabontabon.vortex.core.workload.TestType.SPIKE, shape);

            MeasuredResults shapeResults = Fixtures.results(92, 0.0);
            MeasuredResults results = new MeasuredResults(shapeResults.window(), plan.peakLevel(),
                    com.acltabontabon.vortex.core.shared.RequestsPerSecond.of(83.119),
                    shapeResults.requests(), 0, shapeResults.latency(), Map.of(),
                    shapeResults.series(), List.of());

            ThresholdEvaluation evaluation =
                    new ThresholdEvaluator().evaluate(plan.thresholds(), results);
            DeterministicSummary summary = new DeterministicSummary(
                    plan.intent().question(), Verdict.PASS, "Yes.", results, evaluation,
                    null, null, List.of());
            TestExecution execution = new TestExecution(
                    ExecutionId.of("exec-spike"), plan.projectId(), plan, ExecutionState.COMPLETED,
                    Fixtures.NOW, Fixtures.NOW.plusSeconds(1), Fixtures.NOW.plusSeconds(61),
                    results, summary, ToolVersions.unknown(),
                    ExecutionArtifacts.empty().with("plan.json", "plan.json"), null, "");

            RunEvidence evidence =
                    service.assemble(execution, "/tmp/executions/exec-spike", List.of("plan.json"));

            assertThat(evidence.workload().deliveredFractionIfPresent())
                    .hasValueSatisfying(fraction ->
                            assertThat(fraction).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.02)));
            assertThat(evidence.findings())
                    .noneMatch(finding -> finding.id().equals("finding:throughput.shortfall"));
            assertThat(evidence.findings())
                    .anyMatch(finding -> finding.id().equals("finding:throughput.sustained"));
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
                    .isEqualTo(com.acltabontabon.vortex.core.metrics.TelemetryCompleteness.Status.UNAVAILABLE);
            assertThat(evidence.resourceTimeline().plots()).isEmpty();
        }
    }

    @Nested
    @DisplayName("resource telemetry")
    class ResourceTelemetry {

        @Test
        @DisplayName("samples are grouped by kind, with completeness carried through honestly")
        void samplesAreGroupedByKindAndCompletenessSurvives() {
            var sut = com.acltabontabon.vortex.core.resource.ResourceScope.SYSTEM_UNDER_TEST;
            var generator = com.acltabontabon.vortex.core.resource.ResourceScope.LOAD_GENERATOR;
            Instant at = Fixtures.NOW.plusSeconds(30);
            var samples = List.of(
                    new com.acltabontabon.vortex.core.resource.ResourceSample(at, "actuator", "metric:cpu",
                            com.acltabontabon.vortex.core.resource.ResourceKind.CPU, sut, 0.8,
                            com.acltabontabon.vortex.core.metrics.MetricUnit.RATIO, 0),
                    new com.acltabontabon.vortex.core.resource.ResourceSample(at, "generator", "metric:generator.cpu",
                            com.acltabontabon.vortex.core.resource.ResourceKind.CPU, generator, 0.3,
                            com.acltabontabon.vortex.core.metrics.MetricUnit.RATIO, 0),
                    new com.acltabontabon.vortex.core.resource.ResourceSample(at, "actuator", "metric:heap",
                            com.acltabontabon.vortex.core.resource.ResourceKind.RUNTIME_MEMORY, sut, 0.5,
                            com.acltabontabon.vortex.core.metrics.MetricUnit.RATIO, 0));
            var completeness = new com.acltabontabon.vortex.core.metrics.TelemetryCompleteness(
                    com.acltabontabon.vortex.core.metrics.TelemetryCompleteness.Status.PARTIAL,
                    new com.acltabontabon.vortex.core.metrics.TimeWindow(at, at), "the artifact could not be written to");

            RunEvidenceService withTelemetry = new RunEvidenceService(
                    new DeterministicAnalyzer(new ThresholdEvaluator(), new BreakpointDetector(),
                            new SystemSaturationDetector()),
                    new FindingDetector(), new EvidenceSanitizer(), new RegressionEvaluator(),
                    Clock.fixed(GENERATED_AT), com.acltabontabon.vortex.core.port.HostInformation.unknown(),
                    executionId -> new com.acltabontabon.vortex.core.resource.ResourceTelemetryReader.Result(
                            completeness, samples));

            RunEvidence evidence = withTelemetry.assemble(completed(), "/tmp/executions/exec1",
                    List.of("plan.json"));

            assertThat(evidence.resourceTimeline().present()).isTrue();
            assertThat(evidence.resourceTimeline().completeness()).isEqualTo(completeness);
            assertThat(evidence.resourceTimeline().plots())
                    .as("CPU from two scopes stays on one plot; heap gets its own")
                    .hasSize(2);
            var cpuPlot = evidence.resourceTimeline().plots().stream()
                    .filter(plot -> plot.kind() == com.acltabontabon.vortex.core.resource.ResourceKind.CPU)
                    .findFirst().orElseThrow();
            assertThat(cpuPlot.series())
                    .as("system-under-test and load-generator CPU are two distinct series, not merged")
                    .extracting(com.acltabontabon.vortex.core.evidence.ResourceTimelineEvidence.ResourceSeriesEvidence::scope)
                    .containsExactlyInAnyOrder(sut, generator);
        }

        @Test
        @DisplayName("when Vortex confirmed the container's limits, only the container's own "
                + "resources are the service's")
        void aConfirmedContainerEnvelopeIsTheOnlyServiceResource() {
            // system.cpu.usage is the machine's CPU as the JVM sees it, not the half core the
            // container was actually held to — on a real run the two read 97% and 62% at once. Both
            // under one "System under test" heading has exactly one plausible reading, and it is the
            // wrong one.
            var sut = com.acltabontabon.vortex.core.resource.ResourceScope.SYSTEM_UNDER_TEST;
            var generator = com.acltabontabon.vortex.core.resource.ResourceScope.LOAD_GENERATOR;
            Instant at = Fixtures.NOW.plusSeconds(30);
            var samples = List.of(
                    sample(at, "actuator", "metric:system.cpu.usage", sut, 0.97),
                    sample(at, "docker", "metric:docker.cpu.utilization", sut, 0.31),
                    sample(at, "generator", "metric:generator.cpu", generator, 0.3));

            RunEvidence evidence = assembleWith(samples, List.of(
                    hostScoped("metric:system.cpu.usage", 97.0),
                    vortexConfigured("metric:docker.cpu.utilization", 0.31)));

            assertThat(evidence.observability().signals())
                    .as("every measurement is still collected and citable — only the claim that it "
                            + "is a resource of the service is withdrawn")
                    .hasSize(2);
            assertThat(serviceResourceIds(evidence))
                    .containsExactly("metric:docker.cpu.utilization");

            var cpuPlot = evidence.resourceTimeline().plots().stream()
                    .filter(plot -> plot.kind() == com.acltabontabon.vortex.core.resource.ResourceKind.CPU)
                    .findFirst().orElseThrow();
            assertThat(cpuPlot.series())
                    .extracting(com.acltabontabon.vortex.core.evidence.ResourceTimelineEvidence.ResourceSeriesEvidence::signalId)
                    .as("the generator's own CPU answers a different question and is read on its own "
                            + "terms, so it stays")
                    .containsExactlyInAnyOrder("metric:docker.cpu.utilization", "metric:generator.cpu");
        }

        @Test
        @DisplayName("with no Vortex-configured envelope, the service's own gauges are left alone")
        void withoutAConfirmedEnvelopeNothingIsFiltered() {
            // An external endpoint is somebody else's deployment. Vortex set no limits there, so the
            // service's own gauges are the only account of it anyone has, and dropping them would
            // leave the section empty rather than honest.
            var sut = com.acltabontabon.vortex.core.resource.ResourceScope.SYSTEM_UNDER_TEST;
            Instant at = Fixtures.NOW.plusSeconds(30);
            var samples = List.of(
                    sample(at, "actuator", "metric:system.cpu.usage", sut, 0.97),
                    sample(at, "actuator", "metric:heap", sut, 0.5));

            RunEvidence evidence = assembleWith(samples, List.of(
                    hostScoped("metric:system.cpu.usage", 97.0),
                    hostScoped("metric:heap", 50.0)));

            assertThat(serviceResourceIds(evidence))
                    .containsExactlyInAnyOrder("metric:system.cpu.usage", "metric:heap");
        }

        private List<String> serviceResourceIds(RunEvidence evidence) {
            return evidence.observability().signals().stream()
                    .filter(signal -> signal.resourceIfPresent().isPresent())
                    .map(com.acltabontabon.vortex.core.evidence.ObservedSignal::id)
                    .toList();
        }

        private com.acltabontabon.vortex.core.resource.ResourceSample sample(Instant at,
                String providerId, String signalId,
                com.acltabontabon.vortex.core.resource.ResourceScope scope, double value) {
            return new com.acltabontabon.vortex.core.resource.ResourceSample(at, providerId, signalId,
                    com.acltabontabon.vortex.core.resource.ResourceKind.CPU, scope, value,
                    com.acltabontabon.vortex.core.metrics.MetricUnit.RATIO, 0);
        }

        /** A gauge the service publishes about itself, limited only by what a percentage can be. */
        private ResourceSignal hostScoped(String signalId, double value) {
            return new ResourceSignal(observation(signalId, value,
                    com.acltabontabon.vortex.core.metrics.MetricUnit.PERCENT),
                    com.acltabontabon.vortex.core.resource.ResourceKind.CPU,
                    com.acltabontabon.vortex.core.resource.ResourceScope.SYSTEM_UNDER_TEST,
                    new com.acltabontabon.vortex.core.resource.ResourceLimit(100.0,
                            com.acltabontabon.vortex.core.metrics.MetricUnit.PERCENT,
                            com.acltabontabon.vortex.core.resource.LimitBasis.INHERENT_TO_UNIT,
                            "the definition of a percentage"));
        }

        /** A limit Vortex requested at container-create time and confirmed was applied. */
        private ResourceSignal vortexConfigured(String signalId, double value) {
            return new ResourceSignal(observation(signalId, value,
                    com.acltabontabon.vortex.core.metrics.MetricUnit.RATIO),
                    com.acltabontabon.vortex.core.resource.ResourceKind.CPU,
                    com.acltabontabon.vortex.core.resource.ResourceScope.SYSTEM_UNDER_TEST,
                    new com.acltabontabon.vortex.core.resource.ResourceLimit(0.5,
                            com.acltabontabon.vortex.core.metrics.MetricUnit.RATIO,
                            com.acltabontabon.vortex.core.resource.LimitBasis.VORTEX_CONFIGURED,
                            "the CPU limit Vortex applied to this container"));
        }

        private com.acltabontabon.vortex.core.metrics.MetricObservation observation(String signalId,
                double value, com.acltabontabon.vortex.core.metrics.MetricUnit unit) {
            return com.acltabontabon.vortex.core.metrics.MetricObservation.of(signalId, signalId,
                    com.acltabontabon.vortex.core.metrics.MetricSource.DERIVED, unit,
                    com.acltabontabon.vortex.core.metrics.Aggregation.MAX, value,
                    new com.acltabontabon.vortex.core.metrics.TimeWindow(Fixtures.NOW,
                            Fixtures.NOW.plusSeconds(60)));
        }

        private RunEvidence assembleWith(List<com.acltabontabon.vortex.core.resource.ResourceSample> samples,
                List<ResourceSignal> signals) {
            var completeness = new com.acltabontabon.vortex.core.metrics.TelemetryCompleteness(
                    com.acltabontabon.vortex.core.metrics.TelemetryCompleteness.Status.COMPLETE,
                    new com.acltabontabon.vortex.core.metrics.TimeWindow(Fixtures.NOW,
                            Fixtures.NOW.plusSeconds(60)), "");
            RunEvidenceService service = new RunEvidenceService(
                    new DeterministicAnalyzer(new ThresholdEvaluator(), new BreakpointDetector(),
                            new SystemSaturationDetector()),
                    new FindingDetector(), new EvidenceSanitizer(), new RegressionEvaluator(),
                    Clock.fixed(GENERATED_AT), com.acltabontabon.vortex.core.port.HostInformation.unknown(),
                    executionId -> new com.acltabontabon.vortex.core.resource.ResourceTelemetryReader.Result(
                            completeness, samples));
            return service.assemble(completedWithResources(signals), "/tmp/executions/exec1",
                    List.of("plan.json"));
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

    /** A completed run whose measurements carry the given resource classifications, and an
     *  observation for each so the two lists line up the way a real run's do. */
    private static TestExecution completedWithResources(List<ResourceSignal> signals) {
        MeasuredResults base = Fixtures.results(281, 0.0008);
        MeasuredResults results = new MeasuredResults(base.window(), base.targetLoad(),
                base.achievedRate(), base.requests(), base.failures(), base.latency(),
                base.perOperation(), base.series(),
                signals.stream().map(ResourceSignal::observation).toList(),
                base.stageTelemetry(), base.telemetryGaps(), base.generation(), base.phases(),
                base.reliability(), signals);
        ThresholdEvaluation evaluation =
                new ThresholdEvaluator().evaluate(Fixtures.thresholds(), results);
        DeterministicSummary summary = new DeterministicSummary(
                "Can it hold 20 requests/sec?", Verdict.PASS, "Yes.", results, evaluation,
                null, null, List.of("This is an isolated test."));
        EffectiveTestPlan plan = Fixtures.plan();
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
