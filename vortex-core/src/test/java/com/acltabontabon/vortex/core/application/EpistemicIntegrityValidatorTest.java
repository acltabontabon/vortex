package com.acltabontabon.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.analysis.Analysis;
import com.acltabontabon.vortex.core.analysis.AnalysisState;
import com.acltabontabon.vortex.core.analysis.Confidence;
import com.acltabontabon.vortex.core.analysis.DeterministicSummary;
import com.acltabontabon.vortex.core.analysis.EvidenceStrength;
import com.acltabontabon.vortex.core.analysis.Finding;
import com.acltabontabon.vortex.core.analysis.FindingType;
import com.acltabontabon.vortex.core.analysis.SloBreakpoint;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.shared.AnalysisId;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.threshold.ThresholdEvaluator;
import com.acltabontabon.vortex.core.threshold.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the model cannot silently weaken Vortex's evidence discipline by dressing an unconfirmed
 * claim up as a settled one, or by disagreeing with a verdict or breakpoint Vortex already decided.
 */
class EpistemicIntegrityValidatorTest {

    private static final ExecutionId EXECUTION = ExecutionId.of("exec1");
    private final EpistemicIntegrityValidator validator = new EpistemicIntegrityValidator();
    private final EffectiveTestPlan plan = Fixtures.plan();

    private DeterministicSummary summary(Verdict verdict, String answer, SloBreakpoint breakpoint) {
        var results = Fixtures.results(120, 0.001);
        var evaluation = new ThresholdEvaluator().evaluate(Fixtures.thresholds(), results);
        return new DeterministicSummary("Can it sustain this load?", verdict, answer, results,
                evaluation, breakpoint, null, List.of());
    }

    private Analysis analysisWith(Finding finding) {
        return new Analysis(AnalysisId.of("a1"), EXECUTION, AnalysisState.COMPLETED,
                "The service met its objectives throughout this run.", List.of(finding),
                List.of(), List.of(), null, null, "");
    }

    @Test
    void hypothesisWithHighConfidenceIsCappedToLow() {
        var summary = summary(Verdict.PASS, "Yes.", null);
        var finding = new Finding("Pool exhaustion caused the slowdown.", FindingType.HYPOTHESIS,
                Confidence.HIGH, List.of("metric:http.errorRate"));

        var result = validator.validate(analysisWith(finding), plan, summary);

        assertThat(result.analysis().findings()).singleElement().satisfies(kept -> {
            assertThat(kept.confidence()).isEqualTo(Confidence.LOW);
            assertThat(kept.type()).isEqualTo(FindingType.HYPOTHESIS);
        });
    }

    @Test
    void correlationWithHighConfidenceIsCappedToMedium() {
        var summary = summary(Verdict.PASS, "Yes.", null);
        var finding = new Finding("Latency tracked pool utilisation closely.", FindingType.CORRELATION,
                Confidence.HIGH, List.of("metric:http.errorRate"));

        var result = validator.validate(analysisWith(finding), plan, summary);

        assertThat(result.analysis().findings().getFirst().confidence()).isEqualTo(Confidence.MEDIUM);
    }

    @Test
    void observationMayKeepHighConfidence() {
        var summary = summary(Verdict.PASS, "Yes.", null);
        var finding = new Finding("p95 latency was 120 ms.", FindingType.OBSERVATION,
                Confidence.HIGH, List.of("metric:http.errorRate"));

        var result = validator.validate(analysisWith(finding), plan, summary);

        assertThat(result.analysis().findings().getFirst().confidence()).isEqualTo(Confidence.HIGH);
    }

    @Test
    void findingContradictingAPassVerdictIsDropped() {
        var summary = summary(Verdict.PASS, "Yes.", null);
        var finding = new Finding("The service failed to meet its objectives.", FindingType.OBSERVATION,
                Confidence.HIGH, List.of("metric:http.errorRate"));

        var result = validator.validate(analysisWith(finding), plan, summary);

        assertThat(result.analysis().findings()).isEmpty();
        assertThat(result.contradictionsDropped()).hasSize(1);
    }

    @Test
    void findingContradictingAFailVerdictIsDropped() {
        var summary = summary(Verdict.FAIL, "No.", null);
        var finding = new Finding("The service met every objective.", FindingType.OBSERVATION,
                Confidence.HIGH, List.of("metric:http.errorRate"));

        var result = validator.validate(analysisWith(finding), plan, summary);

        assertThat(result.analysis().findings()).isEmpty();
    }

    @Test
    void conclusionContradictingTheVerdictFallsBackToTheDeterministicAnswer() {
        var summary = summary(Verdict.PASS, "Yes.", null);
        Analysis analysis = new Analysis(AnalysisId.of("a1"), EXECUTION, AnalysisState.COMPLETED,
                "The service failed to meet its objectives.", List.of(), List.of(), List.of(), null,
                null, "");

        var result = validator.validate(analysis, plan, summary);

        assertThat(result.analysis().conclusion()).isEqualTo("Yes.");
    }

    @Test
    void findingDenyingAnEstablishedBreakpointIsDropped() {
        var breakpoint = new SloBreakpoint(RequestsPerSecond.of(150), RequestsPerSecond.of(100),
                List.of("latency-p95"), EvidenceStrength.HIGH, 4);
        var summary = summary(Verdict.FAIL, "No.", breakpoint);
        var finding = new Finding("No breakpoint was reached during this run.", FindingType.OBSERVATION,
                Confidence.HIGH, List.of("metric:http.errorRate"));

        var result = validator.validate(analysisWith(finding), plan, summary);

        assertThat(result.analysis().findings()).isEmpty();
    }

    @Test
    void breakpointDenialPhraseIsIgnoredWhenNoBreakpointWasEstablished() {
        var summary = summary(Verdict.PASS, "Yes.", null);
        var finding = new Finding("No breakpoint was reached during this run.", FindingType.OBSERVATION,
                Confidence.HIGH, List.of("metric:http.errorRate"));

        var result = validator.validate(analysisWith(finding), plan, summary);

        assertThat(result.analysis().findings()).hasSize(1);
    }

    @Test
    void operationNamedWithoutOperationScopedEvidenceIsDowngraded() {
        var summary = summary(Verdict.PASS, "Yes.", null);
        String operationName = plan.operations().getFirst().name();
        var finding = new Finding(operationName + " degraded sharply.", FindingType.OBSERVATION,
                Confidence.HIGH, List.of("metric:http.errorRate"));

        var result = validator.validate(analysisWith(finding), plan, summary);

        assertThat(result.analysis().findings()).singleElement().satisfies(kept -> {
            assertThat(kept.type()).isEqualTo(FindingType.HYPOTHESIS);
            assertThat(kept.confidence()).isEqualTo(Confidence.LOW);
        });
    }

    @Test
    void operationNamedWithOperationScopedEvidenceIsUnaffected() {
        var summary = summary(Verdict.PASS, "Yes.", null);
        String operationName = plan.operations().getFirst().name();
        var operationId = plan.operations().getFirst().operationId();
        var finding = new Finding(operationName + " degraded sharply.", FindingType.OBSERVATION,
                Confidence.HIGH,
                List.of(com.acltabontabon.vortex.core.analysis.EvidenceIds.operationErrorRate(operationId)));

        var result = validator.validate(analysisWith(finding), plan, summary);

        assertThat(result.analysis().findings()).singleElement().satisfies(kept -> {
            assertThat(kept.type()).isEqualTo(FindingType.OBSERVATION);
            assertThat(kept.confidence()).isEqualTo(Confidence.HIGH);
        });
    }
}
