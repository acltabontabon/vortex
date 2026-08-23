package dev.vortex.core.application;

import dev.vortex.core.analysis.Analysis;
import dev.vortex.core.analysis.DeterministicSummary;
import dev.vortex.core.analysis.EvidenceIds;
import dev.vortex.core.analysis.Finding;
import dev.vortex.core.analysis.MissingTelemetry;
import dev.vortex.core.analysis.NextTestSuggestion;
import dev.vortex.core.analysis.Recommendation;
import dev.vortex.core.comparison.ComparisonAnalysis;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Discards interpretations that cite measurements which were never taken.
 *
 * <p>This is the mechanism that stops "CPU remained below 58%" appearing in a report for a run
 * where CPU was never measured. Every finding must reference evidence by identifier; those
 * identifiers are resolved against the measurements the run actually produced; anything
 * unresolvable is removed rather than displayed.
 *
 * <p>Requiring structured identifiers rather than free-text citations is what makes the check
 * possible at all. A model that writes "connection pool metrics showed saturation" cannot be
 * verified; one that writes {@code ["metric:hikari.active.percent"]} can.
 *
 * <p>What is dropped is not silently forgotten. Gaps become {@link MissingTelemetry} entries, so the
 * report says what would have made the analysis better — which is far more useful to an engineer
 * than a confident conclusion drawn from data that does not exist.
 */
public final class EvidenceReferenceValidator {

    /** The outcome of validating an analysis. */
    public record Result(Analysis analysis, List<String> droppedStatements,
            List<String> droppedRecommendations) {

        public Result {
            droppedStatements = droppedStatements == null ? List.of() : List.copyOf(droppedStatements);
            droppedRecommendations =
                    droppedRecommendations == null ? List.of() : List.copyOf(droppedRecommendations);
        }

        public boolean droppedAnything() {
            return !droppedStatements.isEmpty() || !droppedRecommendations.isEmpty();
        }
    }

    /** The outcome of validating a comparison interpretation. */
    public record ComparisonResult(ComparisonAnalysis analysis, List<String> droppedStatements) {

        public ComparisonResult {
            droppedStatements = droppedStatements == null ? List.of() : List.copyOf(droppedStatements);
        }
    }

    /** One citation list resolved against what is actually available. */
    private record Resolved(List<String> kept, List<String> unresolvable) {
    }

    private Resolved resolveCitations(List<String> ids, Set<String> available) {
        // Through EvidenceIds.resolve first: a finding stored before an identifier was renamed still
        // cites a real measurement, and discarding it would delete an interpretation rather than
        // reject an unsupported one.
        List<String> kept = ids.stream()
                .map(EvidenceIds::resolve)
                .filter(available::contains)
                .toList();
        List<String> unresolvable = ids.stream()
                .filter(id -> !available.contains(EvidenceIds.resolve(id)))
                .toList();
        return new Resolved(kept, unresolvable);
    }

    public Result validate(Analysis analysis, DeterministicSummary summary) {
        Set<String> available = new LinkedHashSet<>(summary.availableEvidenceIds());
        Set<String> unresolvable = new LinkedHashSet<>();

        List<Finding> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (Finding finding : analysis.findings()) {
            Resolved resolved = resolveCitations(finding.evidenceIds(), available);
            unresolvable.addAll(resolved.unresolvable());
            if (resolved.kept().isEmpty()) {
                dropped.add(finding.statement());
                continue;
            }
            kept.add(new Finding(finding.statement(), finding.type(), finding.confidence(),
                    resolved.kept()));
        }

        // A recommendation with no resolvable evidence is exactly as unsupported as a finding
        // with none: "optimise the application" is discarded not because the words are generic,
        // but because nothing behind it resolves to a measurement Vortex actually took.
        List<Recommendation> keptRecommendations = new ArrayList<>();
        List<String> droppedRecommendations = new ArrayList<>();
        for (Recommendation recommendation : analysis.recommendations()) {
            Resolved resolved = resolveCitations(recommendation.evidenceIds(), available);
            unresolvable.addAll(resolved.unresolvable());
            if (resolved.kept().isEmpty()) {
                droppedRecommendations.add(recommendation.action());
                continue;
            }
            keptRecommendations.add(new Recommendation(recommendation.action(),
                    recommendation.rationale(), resolved.kept()));
        }

        NextTestSuggestion nextTest = analysis.nextTest();
        if (nextTest != null) {
            Resolved resolved = resolveCitations(nextTest.evidenceIds(), available);
            unresolvable.addAll(resolved.unresolvable());
            nextTest = resolved.kept().isEmpty()
                    ? null
                    : new NextTestSuggestion(nextTest.action(), nextTest.rationale(),
                            nextTest.wouldDistinguish(), resolved.kept());
        }

        List<MissingTelemetry> missingTelemetry = new ArrayList<>(analysis.missingTelemetry());
        for (String reference : unresolvable) {
            missingTelemetry.add(new MissingTelemetry(
                    describeReference(reference),
                    "An interpretation referred to this measurement, but it was not collected during "
                            + "this run.",
                    "Collect it and re-run, or re-analyse a run that includes it."));
        }

        String conclusion = kept.isEmpty() && !analysis.findings().isEmpty()
                ? "Vortex could not confidently interpret this run: every proposed explanation relied "
                + "on measurements that were not collected."
                : analysis.conclusion();

        Analysis validated = new Analysis(
                analysis.id(), analysis.executionId(), analysis.state(), conclusion, kept,
                keptRecommendations, missingTelemetry, nextTest,
                analysis.provenance(), analysis.failureMessage());

        return new Result(validated, dropped, droppedRecommendations);
    }

    /**
     * The same citation discipline, applied to a comparison interpretation.
     *
     * <p>A comparison finding may only cite {@code delta:} identifiers — the same rule, the same
     * mechanism, a different available set.
     */
    public ComparisonResult validateComparison(ComparisonAnalysis analysis,
            Set<String> availableDeltaIds) {

        List<Finding> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        Set<String> unresolvable = new LinkedHashSet<>();

        for (Finding finding : analysis.findings()) {
            Resolved resolved = resolveCitations(finding.evidenceIds(), availableDeltaIds);
            unresolvable.addAll(resolved.unresolvable());
            if (resolved.kept().isEmpty()) {
                dropped.add(finding.statement());
                continue;
            }
            kept.add(new Finding(finding.statement(), finding.type(), finding.confidence(),
                    resolved.kept()));
        }

        List<MissingTelemetry> missingTelemetry = new ArrayList<>(analysis.missingTelemetry());
        for (String reference : unresolvable) {
            missingTelemetry.add(new MissingTelemetry(
                    describeReference(reference),
                    "An interpretation referred to this comparison, but it was not among the "
                            + "computed differences.",
                    "Re-run the comparison once both executions have been re-analysed."));
        }

        String conclusion = kept.isEmpty() && !analysis.findings().isEmpty()
                ? "Vortex could not confidently interpret this comparison: every proposed explanation "
                + "relied on a difference that was not computed."
                : analysis.conclusion();

        ComparisonAnalysis validated = new ComparisonAnalysis(
                analysis.id(), analysis.baselineId(), analysis.candidateId(), analysis.state(),
                conclusion, kept, missingTelemetry, analysis.provenance(), analysis.failureMessage());

        return new ComparisonResult(validated, dropped);
    }

    private String describeReference(String reference) {
        return dev.vortex.core.analysis.EvidenceIds.describe(reference);
    }
}
