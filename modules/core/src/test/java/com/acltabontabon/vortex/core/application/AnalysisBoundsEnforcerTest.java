package com.acltabontabon.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.analysis.Analysis;
import com.acltabontabon.vortex.core.analysis.AnalysisState;
import com.acltabontabon.vortex.core.analysis.Confidence;
import com.acltabontabon.vortex.core.analysis.Finding;
import com.acltabontabon.vortex.core.analysis.FindingType;
import com.acltabontabon.vortex.core.analysis.Recommendation;
import com.acltabontabon.vortex.core.comparison.ComparisonAnalysis;
import com.acltabontabon.vortex.core.shared.AnalysisId;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A model returning more findings or recommendations than a report can usefully hold has not
 * thereby made all of them less trustworthy — but an unbounded list is a readability and, in the
 * worst case, a resource problem regardless of trustworthiness. These prove the cap is applied by
 * count, keeps the highest-confidence findings, and leaves a small analysis untouched.
 */
class AnalysisBoundsEnforcerTest {

    private static final ExecutionId EXECUTION = ExecutionId.of("exec1");
    private final AnalysisBoundsEnforcer enforcer = new AnalysisBoundsEnforcer();

    private Finding finding(String statement, Confidence confidence) {
        return new Finding(statement, FindingType.OBSERVATION, confidence, List.of("metric:x"));
    }

    private Analysis analysisWith(List<Finding> findings, List<Recommendation> recommendations) {
        return new Analysis(AnalysisId.of("a1"), EXECUTION, AnalysisState.COMPLETED,
                "Conclusion.", findings, recommendations, List.of(), null, null, "");
    }

    @Test
    void aSmallAnalysisIsReturnedUnchanged() {
        Analysis analysis = analysisWith(
                List.of(finding("One.", Confidence.HIGH)),
                List.of(new Recommendation("Do a thing", "Because.", List.of("metric:x"))));

        Analysis enforced = enforcer.enforce(analysis);

        assertThat(enforced).isSameAs(analysis);
    }

    @Test
    void findingsBeyondTheCapAreDropped() {
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < AnalysisBoundsEnforcer.MAX_FINDINGS + 5; i++) {
            findings.add(finding("Finding " + i, Confidence.LOW));
        }

        Analysis enforced = enforcer.enforce(analysisWith(findings, List.of()));

        assertThat(enforced.findings()).hasSize(AnalysisBoundsEnforcer.MAX_FINDINGS);
    }

    @Test
    void theHighestConfidenceFindingsSurviveTheCap() {
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < AnalysisBoundsEnforcer.MAX_FINDINGS + 2; i++) {
            findings.add(finding("Low " + i, Confidence.LOW));
        }
        Finding mustSurvive = finding("The one that must survive.", Confidence.HIGH);
        findings.add(findings.size() - 1, mustSurvive);

        Analysis enforced = enforcer.enforce(analysisWith(findings, List.of()));

        assertThat(enforced.findings())
                .as("the sole HIGH-confidence finding must survive a cap keyed on confidence, even "
                        + "though it was near the end of the original list")
                .contains(mustSurvive);
    }

    @Test
    void recommendationsBeyondTheCapAreDropped() {
        List<Recommendation> recommendations = new ArrayList<>();
        for (int i = 0; i < AnalysisBoundsEnforcer.MAX_RECOMMENDATIONS + 3; i++) {
            recommendations.add(new Recommendation("Action " + i, "Because.", List.of("metric:x")));
        }

        Analysis enforced = enforcer.enforce(analysisWith(List.of(), recommendations));

        assertThat(enforced.recommendations()).hasSize(AnalysisBoundsEnforcer.MAX_RECOMMENDATIONS);
    }

    @Test
    void aSmallComparisonIsReturnedUnchanged() {
        ComparisonAnalysis analysis = new ComparisonAnalysis(AnalysisId.of("a1"), EXECUTION,
                ExecutionId.of("exec2"), AnalysisState.COMPLETED, "Conclusion.",
                List.of(finding("One.", Confidence.HIGH)), List.of(), null, "");

        ComparisonAnalysis enforced = enforcer.enforceComparison(analysis);

        assertThat(enforced).isSameAs(analysis);
    }

    @Test
    void comparisonFindingsBeyondTheCapAreDropped() {
        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < AnalysisBoundsEnforcer.MAX_FINDINGS + 4; i++) {
            findings.add(finding("Finding " + i, Confidence.MEDIUM));
        }
        ComparisonAnalysis analysis = new ComparisonAnalysis(AnalysisId.of("a1"), EXECUTION,
                ExecutionId.of("exec2"), AnalysisState.COMPLETED, "Conclusion.", findings, List.of(),
                null, "");

        ComparisonAnalysis enforced = enforcer.enforceComparison(analysis);

        assertThat(enforced.findings()).hasSize(AnalysisBoundsEnforcer.MAX_FINDINGS);
    }
}
