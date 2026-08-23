package com.acltabontabon.vortex.core.application;

import java.util.List;
import java.util.Map;

/**
 * The bounded evidence package handed to the AI assistant for a comparison.
 *
 * <p>Sibling of {@link AnalysisContext}, same discipline: every value here is a fact Vortex already
 * computed — the deltas, the verdict, whether the two runs are even comparable — and the model's
 * job is to explain what those facts mean, never to compute its own percentages.
 *
 * @param baselineLabel   how the earlier run identifies itself
 * @param candidateLabel  how the later run identifies itself
 * @param comparability   {@code HIGH}, {@code PARTIAL} or {@code INVALID} — see {@link
 *                        com.acltabontabon.vortex.core.comparison.Comparability}
 * @param differences     what differed between the two experiments, when they differed
 * @param regressionVerdict the deterministic regression outcome — already decided, not up for debate
 * @param deltas          each computed difference, keyed by its {@code delta:} identifier
 * @param availableEvidenceIds every identifier a finding is permitted to cite
 * @param missingOnEitherSide telemetry absent from the baseline or the candidate that limits the
 *                        comparison
 */
public record ComparisonContext(
        String baselineLabel,
        String candidateLabel,
        String comparability,
        List<String> differences,
        String regressionVerdict,
        Map<String, String> deltas,
        List<String> availableEvidenceIds,
        List<String> missingOnEitherSide) {

    public ComparisonContext {
        baselineLabel = baselineLabel == null ? "" : baselineLabel;
        candidateLabel = candidateLabel == null ? "" : candidateLabel;
        comparability = comparability == null ? "" : comparability;
        differences = differences == null ? List.of() : List.copyOf(differences);
        regressionVerdict = regressionVerdict == null ? "" : regressionVerdict;
        deltas = deltas == null ? Map.of() : Map.copyOf(deltas);
        availableEvidenceIds =
                availableEvidenceIds == null ? List.of() : List.copyOf(availableEvidenceIds);
        missingOnEitherSide = missingOnEitherSide == null ? List.of() : List.copyOf(missingOnEitherSide);
    }
}
