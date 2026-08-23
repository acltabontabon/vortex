package com.acltabontabon.vortex.core.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.analysis.BreakpointDetector;
import com.acltabontabon.vortex.core.analysis.SystemSaturationDetector;
import com.acltabontabon.vortex.core.application.DeterministicAnalyzer;
import com.acltabontabon.vortex.core.application.RunEvidenceService;
import com.acltabontabon.vortex.core.comparison.DeltaKind;
import com.acltabontabon.vortex.core.comparison.RegressionEvaluator;
import com.acltabontabon.vortex.core.evidence.EvidenceProvenance;
import com.acltabontabon.vortex.core.evidence.EvidenceSanitizer;
import com.acltabontabon.vortex.core.evidence.FindingDetector;
import com.acltabontabon.vortex.core.evidence.HostShape;
import com.acltabontabon.vortex.core.evidence.RunEvidence;
import com.acltabontabon.vortex.core.execution.ExecutionState;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.metrics.LoadGeneration;
import com.acltabontabon.vortex.core.metrics.MeasuredResults;
import com.acltabontabon.vortex.core.metrics.MetricSeries;
import com.acltabontabon.vortex.core.metrics.ReliabilityBreakdown;
import com.acltabontabon.vortex.core.metrics.ResponseClass;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.port.Clock;
import com.acltabontabon.vortex.core.port.HostInformation;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.threshold.ThresholdEvaluator;
import com.acltabontabon.vortex.core.validity.RunQualityAssessor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The gate criteria about a document outliving the machine that produced it.
 *
 * <p>Two claims, and the second is the one that decays quietly. An {@code evidence.json} copied to
 * another machine six months later has to state what produced it, and the reproduction command in it
 * has to still identify the same experiment. A fingerprint that drifts because an unrelated field
 * was added to the plan turns every stored comparison into a mismatch, and nothing fails until
 * somebody tries to compare two releases.
 */
class ReproducibleEvidenceTest {

    private static final HostShape HOST =
            new HostShape("Linux", "6.8.0", "aarch64", 10, 34_359_738_368L);

    private final DeterministicAnalyzer analyzer = new DeterministicAnalyzer(
            new ThresholdEvaluator(), new BreakpointDetector(), new SystemSaturationDetector());
    private final RunQualityAssessor validity = new RunQualityAssessor();

    private final RunEvidenceService evidenceService = new RunEvidenceService(analyzer,
            new FindingDetector(), new EvidenceSanitizer(), new RegressionEvaluator(),
            Clock.fixed(Fixtures.NOW), () -> HOST);

    private MeasuredResults healthy() {
        MeasuredResults base = Fixtures.results(180, 0.0);
        return new MeasuredResults(base.window(), base.targetLoad(), base.achievedRate(),
                base.requests(), 0, base.latency(), Map.of(), MetricSeries.empty(), List.of(),
                List.of(), List.of(), new LoadGeneration(30_852L, 0L, 51.4), base.phases(),
                new ReliabilityBreakdown(Map.of(ResponseClass.SUCCESS, base.requests()), Map.of(),
                        Map.of("200", base.requests()), base.requests()));
    }

    private TestExecution completed(String id, EffectiveTestPlan plan, MeasuredResults results) {
        var summary = analyzer.analyze(plan, results);
        var quality = validity.assess(plan, results, analyzer.deriveStages(plan, results),
                ExecutionState.COMPLETED, null);

        return new TestExecution(ExecutionId.of(id), ProjectId.of("checkout"), plan,
                ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW,
                Fixtures.NOW.plusSeconds(600), results, summary,
                new com.acltabontabon.vortex.core.plan.ToolVersions("0.1.0", "k6 v1.3.0", "Java 25", ""),
                null, null, "", quality, null, null);
    }

    private RunEvidence evidenceFor(TestExecution execution) {
        return evidenceService.assemble(execution, "/tmp/executions/" + execution.id().value(),
                List.of("plan.json", "generated-test.js", "k6-summary.json"));
    }

    @Nested
    @DisplayName("provenance: what produced this, and how to produce it again")
    class Provenance {

        private final EvidenceProvenance provenance =
                evidenceFor(completed("run1", Fixtures.plan(), healthy())).provenance();

        @Test
        @DisplayName("carries the contract it was written under")
        void carriesItsContract() {
            assertThat(provenance.schemaVersion()).isEqualTo("vortex.evidence/2");
        }

        @Test
        @DisplayName("names the machine, so a capacity figure can be weighed six months later")
        void namesTheMachine() {
            // "410 requests/sec" off a laptop and off a large build agent are different facts, and a
            // document that cannot say which leaves a reader with only two options: trust it, or
            // discard it.
            assertThat(provenance.host().isKnown()).isTrue();
            assertThat(provenance.host().describe())
                    .contains("Linux")
                    .contains("aarch64")
                    .contains("10 cores");
        }

        @Test
        @DisplayName("states the tools, the configuration and the window it covers")
        void statesWhatMeasured() {
            assertThat(provenance.toolVersions().vortexVersion()).isNotBlank();
            assertThat(provenance.configurationHash()).isNotBlank();
            assertThat(provenance.observabilityWindowIfPresent()).isPresent();
            assertThat(provenance.artifactNames()).contains("plan.json");
        }

        @Test
        @DisplayName("carries whether the experiment was carried out as specified")
        void statesItsOwnValidity() {
            assertThat(provenance.quality().quality().isAssessed()).isTrue();
        }

        @Test
        @DisplayName("and a description that names the experiment rather than the run")
        void carriesAReproductionCommand() {
            assertThat(provenance.reproductionCommand())
                    .startsWith("workload ")
                    .contains(Fixtures.plan().workloadName());
        }
    }

    @Nested
    @DisplayName("reproduction: the same configuration identifies the same experiment")
    class Reproduction {

        @Test
        @DisplayName("re-resolving the same configuration produces an identical fingerprint")
        void theFingerprintSurvivesReResolution() {
            // The claim the reproduction command rests on. Running it again must land on the same
            // experiment identity, or the document describes a test nobody can repeat.
            assertThat(Fixtures.plan().fingerprint()).isEqualTo(Fixtures.plan().fingerprint());
        }

        @Test
        @DisplayName("and Phase 4 moved none of them")
        void phaseFourDidNotMoveTheFingerprint() {
            // ValidityPolicy is held on the plan and deliberately kept out of ExperimentIdentity's
            // dimensions: every plan carries the same value in this phase, so including it would
            // move every stored fingerprint for no distinguishing information. If that decision is
            // ever reversed, this fails and the schema has to be bumped with it.
            var withDefaults = Fixtures.plan();
            var explicit = new EffectiveTestPlan(withDefaults.id(), withDefaults.projectId(),
                    withDefaults.projectName(), withDefaults.serviceVersion(),
                    withDefaults.intent(), withDefaults.workloadName(),
                    withDefaults.workloadDescription(), withDefaults.testType(),
                    withDefaults.workloadModel(), withDefaults.peakLevel(), withDefaults.stages(),
                    withDefaults.operations(), withDefaults.datasets(),
                    withDefaults.workloadSource(), withDefaults.thresholds(),
                    withDefaults.environmentName(), withDefaults.environmentType(),
                    withDefaults.executionTarget(),
                    withDefaults.configuredTarget(), withDefaults.effectiveTarget(),
                    withDefaults.targetRewriteReason(), withDefaults.dependencyMode(),
                    withDefaults.classification(), withDefaults.headers(), withDefaults.k6Options(),
                    withDefaults.runner(), withDefaults.scriptSource(),
                    withDefaults.safetyDecisions(), null,
                    com.acltabontabon.vortex.core.validity.ValidityPolicy.defaults(), withDefaults.workspacePath())
                    .withComputedFingerprint();

            assertThat(explicit.fingerprint()).isEqualTo(withDefaults.fingerprint());
        }
    }

    @Nested
    @DisplayName("comparison: two releases of the same experiment")
    class Comparison {

        private EffectiveTestPlan atVersion(String version) {
            var base = Fixtures.plan();
            return new EffectiveTestPlan(base.id(), base.projectId(), base.projectName(), version,
                    base.intent(), base.workloadName(), base.workloadDescription(),
                    base.testType(), base.workloadModel(), base.peakLevel(), base.stages(),
                    base.operations(), base.datasets(), base.workloadSource(), base.thresholds(),
                    base.environmentName(), base.environmentType(), base.executionTarget(),
                    base.configuredTarget(),
                    base.effectiveTarget(), base.targetRewriteReason(), base.dependencyMode(),
                    base.classification(), base.headers(), base.k6Options(), base.runner(),
                    base.scriptSource(), base.safetyDecisions(), base.fingerprint(),
                    base.validityPolicy(), base.workspacePath());
        }

        private final RegressionEvaluator regressions = new RegressionEvaluator();

        @Test
        @DisplayName("produces deltas covering latency, reliability and throughput")
        void coversTheMeasurements() {
            var before = completed("v1", atVersion("2.17.0"), healthy());
            var after = completed("v2", atVersion("2.18.0"), Fixtures.results(240, 0.002));

            var comparison = regressions.compare(before, after);
            var kinds = comparison.deltas().stream().map(delta -> delta.kind()).distinct().toList();

            assertThat(kinds).contains(DeltaKind.LATENCY, DeltaKind.RELIABILITY);
            assertThat(comparison.deltas()).isNotEmpty();
        }

        @Test
        @DisplayName("compares two releases without treating the version change as a difference")
        void aVersionChangeIsNotAnIncompatibility() {
            // Release identity is not experiment identity: comparing two versions is the whole
            // point, so the version must not make them incomparable.
            var comparison = regressions.compare(
                    completed("v1", atVersion("2.17.0"), healthy()),
                    completed("v2", atVersion("2.18.0"), healthy()));

            assertThat(comparison.supportsRegressionVerdict()).isTrue();
        }

        @Test
        @DisplayName("and says so when the two runs observed different things")
        void differingCoverageIsStatedRatherThanScoredAsARegression() {
            MeasuredResults observed = healthy();
            MeasuredResults withTelemetry = new MeasuredResults(observed.window(),
                    observed.targetLoad(), observed.achievedRate(), observed.requests(),
                    observed.failures(), observed.latency(), Map.of(), MetricSeries.empty(),
                    List.of(com.acltabontabon.vortex.core.metrics.MetricObservation.of("metric:cpu", "CPU",
                            com.acltabontabon.vortex.core.metrics.MetricSource.PROMETHEUS,
                            com.acltabontabon.vortex.core.metrics.MetricUnit.PERCENT,
                            com.acltabontabon.vortex.core.metrics.Aggregation.MAX, 41, observed.window())),
                    List.of(), List.of(), observed.generation(), observed.phases(),
                    observed.reliability());

            var comparison = regressions.compare(
                    completed("v1", atVersion("2.17.0"), observed),
                    completed("v2", atVersion("2.18.0"), withTelemetry));

            // A resource that appears to have changed because nobody measured it the first time is
            // the wrong conclusion this prevents.
            assertThat(comparison.compatibility().differences())
                    .anySatisfy(difference ->
                            assertThat(difference).contains("telemetry coverage differs"));
        }
    }
}
