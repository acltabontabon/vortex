package com.acltabontabon.vortex.ai;

import com.acltabontabon.vortex.core.analysis.Analysis;
import com.acltabontabon.vortex.core.analysis.AnalysisProvenance;
import com.acltabontabon.vortex.core.analysis.AnalysisState;
import com.acltabontabon.vortex.core.analysis.Confidence;
import com.acltabontabon.vortex.core.analysis.DeterministicSummary;
import com.acltabontabon.vortex.core.analysis.Finding;
import com.acltabontabon.vortex.core.analysis.FindingType;
import com.acltabontabon.vortex.core.analysis.NextTestSuggestion;
import com.acltabontabon.vortex.core.analysis.Recommendation;
import com.acltabontabon.vortex.core.capacity.ProductionObservation;
import com.acltabontabon.vortex.core.comparison.ComparisonAnalysis;
import com.acltabontabon.vortex.core.comparison.ExecutionComparison;
import com.acltabontabon.vortex.core.comparison.RegressionVerdict;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.port.PerformanceAssistant;
import com.acltabontabon.vortex.core.shared.AnalysisId;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A controllable stand-in for the assistant.
 *
 * <p>Exists so the build never depends on a language model being installed, running, or in a
 * particular mood. Real inference is non-deterministic; a test suite that required it would fail for
 * reasons unrelated to the code and would slowly teach everyone to ignore it.
 *
 * <p>What is tested here is the behaviour Vortex controls: what happens when the assistant is
 * absent, slow, or returns something that does not hold up. Those paths matter far more than the
 * happy one, because they are what stands between a user and a fabricated performance conclusion.
 */
public final class FakePerformanceAssistant implements PerformanceAssistant {

    /** How the fake should behave on the next call. */
    public enum Behaviour {
        /** Returns a well-formed analysis citing evidence that exists. */
        HEALTHY,
        /** Reports itself unavailable, as a stopped Ollama would. */
        UNAVAILABLE,
        /** Throws, as an inference timeout does. */
        TIMEOUT,
        /** Returns an analysis whose findings cite measurements that were never taken. */
        CITES_MISSING_EVIDENCE,
        /** Returns an analysis with no findings and no conclusion. */
        EMPTY,
        /**
         * Returns a failed analysis, as the real assistant does when the model rejects the request
         * — an unknown model name, for instance.
         */
        RETURNS_FAILURE,
        /** Returns a finding whose statement contradicts a PASS verdict. */
        CONTRADICTS_PASS_VERDICT,
        /** Returns a finding whose statement denies an established SLO breakpoint. */
        IGNORES_BREAKPOINT,
        /** Returns a HYPOTHESIS finding carrying HIGH confidence. */
        HIGH_CONFIDENCE_UNSUPPORTED_HYPOTHESIS,
        /** Returns a finding naming an operation but citing only aggregate evidence. */
        OPERATION_CLAIM_FROM_AGGREGATE_EVIDENCE,
        /** Returns a recommendation with no supporting evidence. */
        RECOMMENDS_WITHOUT_EVIDENCE,
        /** Returns a nextTest citing evidence that does not exist. */
        NEXT_TEST_CITES_MISSING_EVIDENCE
    }

    private Behaviour behaviour = Behaviour.HEALTHY;
    private int analyzeCalls;
    private int compareCalls;

    public FakePerformanceAssistant behaving(Behaviour behaviour) {
        this.behaviour = behaviour;
        return this;
    }

    public int analyzeCalls() {
        return analyzeCalls;
    }

    public int compareCalls() {
        return compareCalls;
    }

    @Override
    public Availability availability() {
        return behaviour == Behaviour.UNAVAILABLE
                ? Availability.unavailable("ollama", "Ollama was not detected.",
                "Install it from https://ollama.com, then start it with 'ollama serve'.")
                : Availability.ready("ollama", "test-model");
    }

    @Override
    public Analysis analyze(ExecutionId executionId, EffectiveTestPlan plan,
            DeterministicSummary summary) {
        analyzeCalls++;

        return switch (behaviour) {
            case UNAVAILABLE -> Analysis.failed(AnalysisId.generate(), executionId,
                    "Ollama was not detected.");
            case TIMEOUT -> throw new IllegalStateException("Inference timed out after 3 minutes");
            case EMPTY -> completed(executionId, "", List.of(), List.of(), null);
            case RETURNS_FAILURE -> Analysis.failed(AnalysisId.generate(), executionId,
                    "The AI request failed: model 'nonexistent' not found");
            case CITES_MISSING_EVIDENCE -> completed(executionId,
                    "Connection-pool saturation is the strongest hypothesis.",
                    List.of(new Finding(
                            "CPU remained below 58% while latency rose.",
                            Confidence.MEDIUM,
                            List.of("metric:cpu.max", "metric:hikari.active.percent"))),
                    List.of(), null);
            case CONTRADICTS_PASS_VERDICT -> {
                String citable = firstCitable(summary);
                yield completed(executionId,
                        "The service failed to meet its objectives despite the recorded pass.",
                        List.of(new Finding("Objectives were violated throughout this run.",
                                FindingType.OBSERVATION, Confidence.HIGH, List.of(citable))),
                        List.of(), null);
            }
            case IGNORES_BREAKPOINT -> {
                String citable = firstCitable(summary);
                yield completed(executionId, "No clear limit was found for this service.",
                        List.of(new Finding("No breakpoint was reached during this run.",
                                FindingType.OBSERVATION, Confidence.HIGH, List.of(citable))),
                        List.of(), null);
            }
            case HIGH_CONFIDENCE_UNSUPPORTED_HYPOTHESIS -> {
                String citable = firstCitable(summary);
                yield completed(executionId, "The strongest hypothesis is pool exhaustion.",
                        List.of(new Finding("Pool exhaustion is the cause of the slowdown.",
                                FindingType.HYPOTHESIS, Confidence.HIGH, List.of(citable))),
                        List.of(), null);
            }
            case OPERATION_CLAIM_FROM_AGGREGATE_EVIDENCE -> {
                String operationName = summary.results().perOperation().values().stream()
                        .findFirst().map(op -> op.name()).orElse("GET /accounts/{id}");
                String citable = firstCitable(summary);
                yield completed(executionId,
                        operationName + " degraded sharply during this run.",
                        List.of(new Finding(operationName + " degraded sharply during this run.",
                                FindingType.OBSERVATION, Confidence.HIGH, List.of(citable))),
                        List.of(), null);
            }
            case RECOMMENDS_WITHOUT_EVIDENCE -> completed(executionId,
                    "The service met its objectives throughout this run.",
                    List.of(), List.of(new Recommendation("Optimise the application", "General advice",
                            List.of())), null);
            case NEXT_TEST_CITES_MISSING_EVIDENCE -> completed(executionId,
                    "The service met its objectives throughout this run.",
                    List.of(), List.of(),
                    new NextTestSuggestion("Repeat the stage with CPU sampling enabled",
                            "CPU telemetry was unavailable", "Whether CPU or the pool was saturating",
                            List.of("metric:cpu.max")));
            case HEALTHY -> {
                String citable = firstCitable(summary);
                yield completed(executionId,
                        "The service met its objectives throughout this run.",
                        List.of(new Finding(
                                "Latency remained well within the configured objective.",
                                FindingType.OBSERVATION, Confidence.HIGH, List.of(citable))),
                        List.of(), null);
            }
        };
    }

    private String firstCitable(DeterministicSummary summary) {
        return summary.availableEvidenceIds().isEmpty()
                ? "metric:http.errorRate"
                : summary.availableEvidenceIds().getFirst();
    }

    private Analysis completed(ExecutionId executionId, String conclusion, List<Finding> findings,
            List<Recommendation> recommendations, NextTestSuggestion nextTest) {
        return new Analysis(AnalysisId.generate(), executionId, AnalysisState.COMPLETED, conclusion,
                findings, recommendations, List.of(), nextTest,
                new AnalysisProvenance("ollama", "test-model", PromptLibrary.VERSION,
                        Instant.parse("2026-08-21T10:00:00Z"), 1200),
                "");
    }

    @Override
    public Optional<String> explainWorkload(ProductionObservation observation,
            List<String> calculatedSuggestions) {
        return behaviour == Behaviour.UNAVAILABLE
                ? Optional.empty()
                : Optional.of("The forecast profile provides roughly 1.5× headroom over the "
                + "observed production peak.");
    }

    @Override
    public ComparisonAnalysis compareExecutions(TestExecution baseline, TestExecution candidate,
            ExecutionComparison comparison, RegressionVerdict verdict) {
        compareCalls++;
        return new ComparisonAnalysis(AnalysisId.generate(), baseline.id(), candidate.id(),
                AnalysisState.COMPLETED, "Latency increased beyond the noise threshold.",
                List.of(), List.of(),
                new AnalysisProvenance("ollama", "test-model", PromptLibrary.VERSION,
                        Instant.parse("2026-08-21T10:00:00Z"), 900),
                "");
    }
}
