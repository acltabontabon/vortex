package com.acltabontabon.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.analysis.Analysis;
import com.acltabontabon.vortex.core.analysis.AnalysisProvenance;
import com.acltabontabon.vortex.core.analysis.AnalysisState;
import com.acltabontabon.vortex.core.analysis.Confidence;
import com.acltabontabon.vortex.core.analysis.DeterministicSummary;
import com.acltabontabon.vortex.core.analysis.Finding;
import com.acltabontabon.vortex.core.analysis.FindingType;
import com.acltabontabon.vortex.core.capacity.ProductionObservation;
import com.acltabontabon.vortex.core.comparison.ComparisonAnalysis;
import com.acltabontabon.vortex.core.comparison.ExecutionComparison;
import com.acltabontabon.vortex.core.comparison.RegressionEvaluator;
import com.acltabontabon.vortex.core.comparison.RegressionVerdict;
import com.acltabontabon.vortex.core.execution.ExecutionState;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.fixtures.InMemoryExecutions;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.port.PerformanceAssistant;
import com.acltabontabon.vortex.core.shared.AnalysisId;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.workload.ConstantArrivalRateShape;
import com.acltabontabon.vortex.core.workload.ConstantConcurrencyShape;
import com.acltabontabon.vortex.core.workload.OperationMix;
import com.acltabontabon.vortex.core.workload.TestType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the AI comparison layer never spends an inference call on a pair Vortex already knows is
 * not meaningfully comparable, and that its findings are evidence-checked the same way a single
 * run's are.
 */
class ComparisonAnalysisServiceTest {

    private final RegressionEvaluator evaluator = new RegressionEvaluator();
    private final ComparisonService comparisonService =
            new ComparisonService(new InMemoryExecutions(), evaluator);
    private final ComparisonEvidenceAssembler evidenceAssembler =
            new ComparisonEvidenceAssembler(new EvidenceAssembler());
    private final EvidenceReferenceValidator validator = new EvidenceReferenceValidator();

    private static TestExecution run(EffectiveTestPlan plan, long p95Millis) {
        return new TestExecution(ExecutionId.generate(), plan.projectId(), plan,
                ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW,
                Fixtures.NOW.plusSeconds(600), Fixtures.results(p95Millis, 0.001),
                null, null, null, null, "");
    }

    @Test
    @DisplayName("an incomparable pair never reaches the assistant")
    void invalidComparabilitySkipsTheAssistant() {
        var byRate = run(Fixtures.plan(TestType.AVERAGE_LOAD,
                ConstantArrivalRateShape.of(50, Duration.ofMinutes(10)),
                OperationMix.single(Fixtures.GET_ORDER)), 280);
        var byConcurrency = run(Fixtures.plan(TestType.AVERAGE_LOAD,
                ConstantConcurrencyShape.of(50, Duration.ofMinutes(10)),
                OperationMix.single(Fixtures.GET_ORDER)), 405);

        var fake = new FakeAssistant();
        var service = new ComparisonAnalysisService(comparisonService, evidenceAssembler, fake,
                validator, new AnalysisBoundsEnforcer());

        ComparisonAnalysis analysis = service.analyze(byRate, byConcurrency);

        assertThat(fake.compareCalls()).isZero();
        assertThat(analysis.state()).isEqualTo(AnalysisState.COMPLETED);
        assertThat(analysis.conclusion()).contains("not meaningfully comparable");
    }

    @Test
    @DisplayName("a comparable pair reaches the assistant, and its findings are evidence-checked")
    void comparablePairIsInterpreted() {
        var plan = Fixtures.plan();
        var baseline = run(plan, 200);
        var candidate = run(plan, 400);

        var fake = new FakeAssistant();
        var service = new ComparisonAnalysisService(comparisonService, evidenceAssembler, fake,
                validator, new AnalysisBoundsEnforcer());

        ComparisonAnalysis analysis = service.analyze(baseline, candidate);

        assertThat(fake.compareCalls()).isEqualTo(1);
        assertThat(analysis.state()).isEqualTo(AnalysisState.COMPLETED);
        assertThat(analysis.findings()).singleElement().satisfies(finding ->
                assertThat(finding.evidenceIds()).containsExactly("delta:latency.p95"));
    }

    @Test
    @DisplayName("a comparison finding citing a nonexistent delta is discarded")
    void unresolvableDeltaCitationIsDropped() {
        var plan = Fixtures.plan();
        var baseline = run(plan, 200);
        var candidate = run(plan, 400);

        var fake = new FakeAssistant();
        fake.nextResult = () -> new ComparisonAnalysis(AnalysisId.generate(), baseline.id(),
                candidate.id(), AnalysisState.COMPLETED, "Something changed.",
                List.of(new Finding("Throughput fell sharply.", FindingType.OBSERVATION,
                        Confidence.HIGH, List.of("delta:invented.metric"))),
                List.of(), null, "");

        var service = new ComparisonAnalysisService(comparisonService, evidenceAssembler, fake,
                validator, new AnalysisBoundsEnforcer());
        ComparisonAnalysis analysis = service.analyze(baseline, candidate);

        assertThat(analysis.findings()).isEmpty();
        assertThat(analysis.missingTelemetry()).isNotEmpty();
    }

    private static final class FakeAssistant implements PerformanceAssistant {
        private int compareCalls;
        private Supplier<ComparisonAnalysis> nextResult;

        @Override
        public Availability availability() {
            return Availability.ready("fake", "test-model");
        }

        @Override
        public Optional<String> explainWorkload(ProductionObservation observation,
                List<String> calculatedSuggestions) {
            return Optional.empty();
        }

        @Override
        public Analysis analyze(ExecutionId executionId, EffectiveTestPlan plan,
                DeterministicSummary summary) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public ComparisonAnalysis compareExecutions(TestExecution baseline, TestExecution candidate,
                ExecutionComparison comparison, RegressionVerdict verdict) {
            compareCalls++;
            if (nextResult != null) {
                return nextResult.get();
            }
            return new ComparisonAnalysis(AnalysisId.generate(), baseline.id(), candidate.id(),
                    AnalysisState.COMPLETED, "Latency increased.",
                    List.of(new Finding("p95 latency rose beyond the noise threshold.",
                            FindingType.OBSERVATION, Confidence.HIGH, List.of("delta:latency.p95"))),
                    List.of(),
                    new AnalysisProvenance("fake", "test-model", "v1", Instant.now(), 100), "");
        }

        int compareCalls() {
            return compareCalls;
        }
    }
}
