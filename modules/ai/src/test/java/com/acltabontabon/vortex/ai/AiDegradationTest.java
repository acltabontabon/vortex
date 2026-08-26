package com.acltabontabon.vortex.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.analysis.Analysis;
import com.acltabontabon.vortex.core.analysis.AnalysisState;
import com.acltabontabon.vortex.core.analysis.Confidence;
import com.acltabontabon.vortex.core.analysis.DeterministicSummary;
import com.acltabontabon.vortex.core.analysis.EvidenceStrength;
import com.acltabontabon.vortex.core.analysis.Finding;
import com.acltabontabon.vortex.core.analysis.FindingType;
import com.acltabontabon.vortex.core.analysis.SloBreakpoint;
import com.acltabontabon.vortex.core.application.AnalysisBoundsEnforcer;
import com.acltabontabon.vortex.core.application.AnalysisService;
import com.acltabontabon.vortex.core.application.EpistemicIntegrityValidator;
import com.acltabontabon.vortex.core.application.EvidenceReferenceValidator;
import com.acltabontabon.vortex.core.execution.ExecutionState;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.fixtures.InMemoryExecutions;
import com.acltabontabon.vortex.core.metrics.Aggregation;
import com.acltabontabon.vortex.core.metrics.MeasuredResults;
import com.acltabontabon.vortex.core.metrics.MetricObservation;
import com.acltabontabon.vortex.core.metrics.MetricSource;
import com.acltabontabon.vortex.core.metrics.MetricUnit;
import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.core.port.Repositories;
import com.acltabontabon.vortex.core.shared.AnalysisId;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.threshold.ThresholdEvaluator;
import com.acltabontabon.vortex.core.threshold.Verdict;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The paths that matter most: what Vortex does when the assistant is absent, slow, or wrong.
 *
 * <p>A tool that produces a confident bottleneck diagnosis from telemetry it never collected is
 * worse than one with no AI at all, because the diagnosis looks like evidence. These tests are the
 * guard against that.
 */
class AiDegradationTest {

    private FakePerformanceAssistant assistant;
    private AnalysisService analysisService;
    private InMemoryAnalyses analyses;
    private InMemoryExecutions executions;

    private static final ExecutionId EXECUTION = ExecutionId.of("exec1");

    @BeforeEach
    void setUp() {
        assistant = new FakePerformanceAssistant();
        analyses = new InMemoryAnalyses();
        executions = new InMemoryExecutions();
        analysisService = new AnalysisService(assistant, new EvidenceReferenceValidator(),
                new EpistemicIntegrityValidator(), new AnalysisBoundsEnforcer(), analyses, executions);

        executions.save(completedExecution());
    }

    private static MeasuredResults resultsWithTelemetry() {
        var base = Fixtures.results(281, 0.0008);
        var window = new TimeWindow(Fixtures.NOW, Fixtures.NOW.plus(Duration.ofMinutes(10)));
        return new MeasuredResults(window, base.targetLoad(), base.achievedRate(),
                base.requests(), base.failures(), base.latency(), base.perOperation(),
                base.series(),
                List.of(MetricObservation.of("metric:checkout.pool.utilization",
                        "checkout.pool.utilization", MetricSource.ACTUATOR, MetricUnit.PERCENT,
                        Aggregation.MAX, 41.0, window)));
    }

    private static TestExecution completedExecution() {
        var results = resultsWithTelemetry();
        var evaluation = new ThresholdEvaluator().evaluate(Fixtures.thresholds(), results);
        var summary = new DeterministicSummary(
                "Can the service sustain 20 requests/sec within its objectives?",
                Verdict.PASS, "Yes.", results, evaluation, null, null, List.of());

        return new TestExecution(EXECUTION, ProjectId.of("checkout"), Fixtures.plan(),
                ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW,
                Fixtures.NOW.plusSeconds(600), results, summary, null, null, null, "");
    }

    @Nested
    @DisplayName("when no model is available")
    class Unavailable {

        @Test
        @DisplayName("the analysis fails with a remedy, and the execution is untouched")
        void analysisFailsWithoutAffectingTheExecution() {
            assistant.behaving(FakePerformanceAssistant.Behaviour.UNAVAILABLE);

            Analysis analysis = analysisService.analyze(EXECUTION);

            assertThat(analysis.state()).isEqualTo(AnalysisState.FAILED);
            assertThat(analysis.failureMessage())
                    .contains("Ollama was not detected")
                    .contains("ollama.com");

            TestExecution execution = executions.findById(EXECUTION).orElseThrow();
            assertThat(execution.state()).isEqualTo(ExecutionState.COMPLETED);
            assertThat(execution.verdict()).isEqualTo(Verdict.PASS);
        }

        @Test
        @DisplayName("the deterministic verdict is unaffected — a missing model is not a failed test")
        void theVerdictStandsWithoutAi() {
            assistant.behaving(FakePerformanceAssistant.Behaviour.UNAVAILABLE);
            analysisService.analyze(EXECUTION);

            var summary = executions.findById(EXECUTION).orElseThrow().summary();

            assertThat(summary.verdict()).isEqualTo(Verdict.PASS);
            assertThat(summary.answer()).isEqualTo("Yes.");
            assertThat(summary.thresholds().results()).hasSize(3);
        }

        @Test
        void availabilityCarriesInstructionsRatherThanJustAStatus() {
            assistant.behaving(FakePerformanceAssistant.Behaviour.UNAVAILABLE);

            var availability = analysisService.availability();

            assertThat(availability.available()).isFalse();
            assertThat(availability.problem()).isNotBlank();
            assertThat(availability.remedy()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("when the model misbehaves")
    class Misbehaviour {

        @Test
        @DisplayName("a timeout is contained: the analysis fails, the measurements do not")
        void timeoutIsContained() {
            assistant.behaving(FakePerformanceAssistant.Behaviour.TIMEOUT);

            Analysis analysis = analysisService.analyze(EXECUTION);

            assertThat(analysis.state()).isEqualTo(AnalysisState.FAILED);
            assertThat(analysis.failureMessage())
                    .contains("timed out")
                    .contains("measurements for this run are unaffected");
            assertThat(executions.findById(EXECUTION).orElseThrow().state())
                    .isEqualTo(ExecutionState.COMPLETED);
        }

        @Test
        @DisplayName("a finding citing telemetry that was never collected is discarded, not displayed")
        void unsupportedFindingsAreDropped() {
            assistant.behaving(FakePerformanceAssistant.Behaviour.CITES_MISSING_EVIDENCE);

            Analysis analysis = analysisService.analyze(EXECUTION);

            // The fake cites metric:cpu.max, which this run never measured.
            assertThat(analysis.findings())
                    .as("a claim about unmeasured CPU must not survive")
                    .noneMatch(finding -> finding.statement().contains("CPU"));
            assertThat(analysis.missingTelemetry())
                    .anyMatch(missing -> missing.what().contains("cpu"));
        }

        @Test
        @DisplayName("when every finding is unsupported, the conclusion says so instead of guessing")
        void anEntirelyUnsupportedAnalysisSaysSo() {
            assistant.behaving(FakePerformanceAssistant.Behaviour.CITES_MISSING_EVIDENCE);

            Analysis analysis = analysisService.analyze(EXECUTION);

            assertThat(analysis.findings()).isEmpty();
            assertThat(analysis.conclusion())
                    .contains("could not confidently interpret")
                    .contains("measurements that were not collected");
        }

        @Test
        @DisplayName("an assistant that failed is recorded as failed, never as a blank success")
        void aFailedAnalysisStaysFailed() {
            assistant.behaving(FakePerformanceAssistant.Behaviour.RETURNS_FAILURE);

            Analysis analysis = analysisService.analyze(EXECUTION);

            // Recording this as COMPLETED with an empty conclusion would present "the model
            // rejected the request" as "the model had nothing to say", which are very different
            // things for a user trying to decide whether to trust the tool.
            assertThat(analysis.state()).isEqualTo(AnalysisState.FAILED);
            assertThat(analysis.failureMessage()).contains("not found");
            assertThat(analysisService.latest(EXECUTION)).isEmpty();
        }

        @Test
        void anEmptyAnalysisIsNotOfferedAsUsable() {
            assistant.behaving(FakePerformanceAssistant.Behaviour.EMPTY);

            analysisService.analyze(EXECUTION);

            assertThat(analysisService.latest(EXECUTION)).isEmpty();
        }
    }

    @Nested
    @DisplayName("when the model tries to weaken Vortex's evidence discipline")
    class Epistemics {

        private static final ExecutionId WITH_BREAKPOINT = ExecutionId.of("exec-breakpoint");

        private TestExecution executionWithBreakpoint() {
            var results = resultsWithTelemetry();
            var evaluation = new ThresholdEvaluator().evaluate(Fixtures.thresholds(), results);
            var breakpoint = new SloBreakpoint(RequestsPerSecond.of(150), RequestsPerSecond.of(100),
                    List.of("latency-p95"), EvidenceStrength.HIGH, 4);
            var summary = new DeterministicSummary(
                    "Can the service sustain 150 requests/sec within its objectives?",
                    Verdict.FAIL, "No. Objectives were first violated at 150 requests/sec.",
                    results, evaluation, breakpoint, null, List.of());

            return new TestExecution(WITH_BREAKPOINT, ProjectId.of("checkout"), Fixtures.plan(),
                    ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW,
                    Fixtures.NOW.plusSeconds(600), results, summary, null, null, null, "");
        }

        @Test
        @DisplayName("a finding contradicting a PASS verdict is dropped, and the conclusion falls "
                + "back to Vortex's own answer")
        void contradictsPassVerdictIsDropped() {
            assistant.behaving(FakePerformanceAssistant.Behaviour.CONTRADICTS_PASS_VERDICT);

            Analysis analysis = analysisService.analyze(EXECUTION);

            assertThat(analysis.findings())
                    .as("a finding that disagrees with a PASS verdict must not survive")
                    .noneMatch(finding -> finding.statement().contains("violated throughout"));
            assertThat(analysis.conclusion()).isEqualTo("Yes.");
        }

        @Test
        @DisplayName("a finding denying an established breakpoint is dropped")
        void ignoresBreakpointIsDropped() {
            executions.save(executionWithBreakpoint());
            assistant.behaving(FakePerformanceAssistant.Behaviour.IGNORES_BREAKPOINT);

            Analysis analysis = analysisService.analyze(WITH_BREAKPOINT);

            assertThat(analysis.findings())
                    .as("a finding that denies an established breakpoint must not survive")
                    .noneMatch(finding -> finding.statement().contains("No breakpoint was reached"));
        }

        @Test
        @DisplayName("a hypothesis carrying HIGH confidence is downgraded, not trusted at face value")
        void highConfidenceHypothesisIsDowngraded() {
            assistant.behaving(FakePerformanceAssistant.Behaviour.HIGH_CONFIDENCE_UNSUPPORTED_HYPOTHESIS);

            Analysis analysis = analysisService.analyze(EXECUTION);

            assertThat(analysis.findings()).singleElement().satisfies(finding -> {
                assertThat(finding.type()).isEqualTo(FindingType.HYPOTHESIS);
                assertThat(finding.confidence())
                        .as("a hypothesis may never carry more confidence than its type permits")
                        .isEqualTo(Confidence.LOW);
            });
        }

        @Test
        @DisplayName("a finding naming an operation but citing only aggregate evidence is downgraded "
                + "to a hypothesis")
        void operationClaimFromAggregateEvidenceIsDowngraded() {
            assistant.behaving(FakePerformanceAssistant.Behaviour.OPERATION_CLAIM_FROM_AGGREGATE_EVIDENCE);

            Analysis analysis = analysisService.analyze(EXECUTION);

            assertThat(analysis.findings()).singleElement().satisfies(finding -> {
                assertThat(finding.type()).isEqualTo(FindingType.HYPOTHESIS);
                assertThat(finding.confidence()).isEqualTo(Confidence.LOW);
            });
        }

        @Test
        @DisplayName("a recommendation with no supporting evidence is discarded, not shown as advice")
        void recommendationWithoutEvidenceIsDropped() {
            assistant.behaving(FakePerformanceAssistant.Behaviour.RECOMMENDS_WITHOUT_EVIDENCE);

            Analysis analysis = analysisService.analyze(EXECUTION);

            assertThat(analysis.recommendations())
                    .as("\"optimise the application\" is not a recommendation without evidence "
                            + "behind it")
                    .isEmpty();
        }

        @Test
        @DisplayName("a nextTest citing evidence that was never collected is nulled out")
        void nextTestCitingMissingEvidenceIsNulled() {
            assistant.behaving(FakePerformanceAssistant.Behaviour.NEXT_TEST_CITES_MISSING_EVIDENCE);

            Analysis analysis = analysisService.analyze(EXECUTION);

            assertThat(analysis.nextTestIfPresent()).isEmpty();
            assertThat(analysis.missingTelemetry())
                    .anyMatch(missing -> missing.what().contains("cpu"));
        }
    }

    @Nested
    @DisplayName("when the model behaves")
    class Healthy {

        @Test
        void aSupportedFindingSurvivesWithItsEvidence() {
            Analysis analysis = analysisService.analyze(EXECUTION);

            assertThat(analysis.state()).isEqualTo(AnalysisState.COMPLETED);
            assertThat(analysis.findings()).singleElement().satisfies(finding -> {
                assertThat(finding.isSupported()).isTrue();
                assertThat(finding.evidenceIds()).isNotEmpty();
            });
        }

        @Test
        @DisplayName("which model and prompt produced the interpretation is recorded")
        void provenanceIsRecorded() {
            Analysis analysis = analysisService.analyze(EXECUTION);

            assertThat(analysis.provenanceIfPresent()).hasValueSatisfying(provenance -> {
                assertThat(provenance.provider()).isEqualTo("ollama");
                assertThat(provenance.model()).isEqualTo("test-model");
                assertThat(provenance.promptVersion()).isEqualTo(PromptLibrary.VERSION);
            });
        }

        @Test
        @DisplayName("re-analysing adds an interpretation rather than replacing the previous one")
        void reAnalysisIsAdditive() {
            analysisService.analyze(EXECUTION);
            analysisService.analyze(EXECUTION);

            assertThat(analysisService.history(EXECUTION)).hasSize(2);
            assertThat(assistant.analyzeCalls()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("an execution that never completed has nothing to interpret")
    void incompleteExecutionsAreNotAnalysed() {
        executions.save(TestExecution.create(ExecutionId.of("pending"), Fixtures.plan(), Fixtures.NOW));

        Analysis analysis = analysisService.analyze(ExecutionId.of("pending"));

        assertThat(analysis.state()).isEqualTo(AnalysisState.FAILED);
        assertThat(analysis.failureMessage()).contains("no completed measurements to interpret");
        assertThat(assistant.analyzeCalls()).isZero();
    }

    @Test
    @DisplayName("the evidence validator keeps only references that resolve")
    void validatorKeepsResolvableReferencesOnly() {
        var summary = executions.findById(EXECUTION).orElseThrow().summary();
        var mixed = new Analysis(AnalysisId.of("a1"), EXECUTION, AnalysisState.COMPLETED,
                "Something happened.",
                List.of(new Finding("Pool utilisation rose alongside latency.", Confidence.MEDIUM,
                        List.of("metric:checkout.pool.utilization", "metric:invented.thing"))),
                List.of(), List.of(), null, null, "");

        var validated = new EvidenceReferenceValidator().validate(mixed, summary).analysis();

        assertThat(validated.findings()).singleElement().satisfies(finding ->
                assertThat(finding.evidenceIds())
                        .containsExactly("metric:checkout.pool.utilization"));
        assertThat(validated.missingTelemetry())
                .anyMatch(missing -> missing.what().contains("invented.thing"));
    }

    // ---------------------------------------------------------------- in-memory ports

    private static final class InMemoryAnalyses implements Repositories.AnalysisRepository {

        private final List<Analysis> stored = new ArrayList<>();

        @Override
        public Analysis save(Analysis analysis) {
            stored.removeIf(existing -> existing.id().equals(analysis.id()));
            stored.add(analysis);
            return analysis;
        }

        @Override
        public Optional<Analysis> findById(AnalysisId id) {
            return stored.stream().filter(analysis -> analysis.id().equals(id)).findFirst();
        }

        @Override
        public List<Analysis> findByExecution(ExecutionId executionId) {
            return stored.stream()
                    .filter(analysis -> analysis.executionId().equals(executionId))
                    .toList();
        }

        @Override
        public Optional<Analysis> findLatest(ExecutionId executionId) {
            return findByExecution(executionId).stream()
                    .filter(Analysis::isUsable)
                    .reduce((first, second) -> second);
        }
    }

}
