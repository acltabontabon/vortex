package com.acltabontabon.vortex.core.application;

import com.acltabontabon.vortex.core.analysis.Analysis;
import com.acltabontabon.vortex.core.analysis.Finding;
import com.acltabontabon.vortex.core.analysis.Recommendation;
import com.acltabontabon.vortex.core.comparison.ComparisonAnalysis;
import java.util.Comparator;
import java.util.List;

/**
 * Caps how many findings and recommendations an analysis may carry.
 *
 * <p>Field length is bounded on the records themselves ({@link Finding#MAX_STATEMENT_LENGTH} and
 * its siblings on {@link Analysis}, {@link Recommendation} and {@code NextTestSuggestion}) because
 * every construction site benefits automatically. Count is different: nothing about constructing
 * one {@link Finding} knows how many siblings it has, so the cap is applied to the whole list, after
 * the evidence and epistemic passes have already decided which findings survive. A model returning
 * far more findings than are useful has not thereby made all of them less trustworthy — the highest
 * -confidence ones are kept, the same preference {@link Analysis#primaryFinding()} already uses —
 * this exists to keep a report readable, not to punish verbosity.
 */
public final class AnalysisBoundsEnforcer {

    public static final int MAX_FINDINGS = 8;
    public static final int MAX_RECOMMENDATIONS = 5;

    public Analysis enforce(Analysis analysis) {
        List<Finding> findings = capFindings(analysis.findings());
        List<Recommendation> recommendations = capRecommendations(analysis.recommendations());
        if (findings.size() == analysis.findings().size()
                && recommendations.size() == analysis.recommendations().size()) {
            return analysis;
        }
        return new Analysis(analysis.id(), analysis.executionId(), analysis.state(),
                analysis.conclusion(), findings, recommendations, analysis.missingTelemetry(),
                analysis.nextTest(), analysis.provenance(), analysis.failureMessage());
    }

    public ComparisonAnalysis enforceComparison(ComparisonAnalysis analysis) {
        List<Finding> findings = capFindings(analysis.findings());
        if (findings.size() == analysis.findings().size()) {
            return analysis;
        }
        return new ComparisonAnalysis(analysis.id(), analysis.baselineId(), analysis.candidateId(),
                analysis.state(), analysis.conclusion(), findings, analysis.missingTelemetry(),
                analysis.provenance(), analysis.failureMessage());
    }

    private List<Finding> capFindings(List<Finding> findings) {
        if (findings.size() <= MAX_FINDINGS) {
            return findings;
        }
        // A stable sort, so findings tied on confidence keep their original relative order rather
        // than being reshuffled by the cap.
        return findings.stream()
                .sorted(Comparator.comparingInt(finding -> finding.confidence().ordinal()))
                .limit(MAX_FINDINGS)
                .toList();
    }

    private List<Recommendation> capRecommendations(List<Recommendation> recommendations) {
        if (recommendations.size() <= MAX_RECOMMENDATIONS) {
            return recommendations;
        }
        return recommendations.subList(0, MAX_RECOMMENDATIONS);
    }
}
