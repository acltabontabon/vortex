package dev.vortex.core.application;

import dev.vortex.core.comparison.Comparability;
import dev.vortex.core.comparison.ExecutionComparison;
import dev.vortex.core.comparison.MetricDelta;
import dev.vortex.core.comparison.RegressionVerdict;
import dev.vortex.core.execution.TestExecution;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Assembles the small, structured evidence package the AI assistant reasons over when comparing
 * two executions.
 *
 * <p>Sibling of {@link EvidenceAssembler}. Reuses it directly for absent-telemetry reporting on
 * each side, rather than re-deriving what "missing" means for a comparison — a gap that limits a
 * single-run analysis limits a comparison exactly the same way.
 */
public final class ComparisonEvidenceAssembler {

    private final EvidenceAssembler evidenceAssembler;

    public ComparisonEvidenceAssembler(EvidenceAssembler evidenceAssembler) {
        this.evidenceAssembler = Objects.requireNonNull(evidenceAssembler, "evidenceAssembler");
    }

    /**
     * Whether this pair is worth interpreting at all — computed before any inference is spent,
     * so an incomparable pair (different workload models, nothing measured) never reaches a model.
     */
    public Comparability classify(TestExecution baseline, TestExecution candidate,
            ExecutionComparison comparison) {
        return Comparability.classify(comparison,
                !absentTelemetryFor(baseline).isEmpty(), !absentTelemetryFor(candidate).isEmpty());
    }

    public ComparisonContext assemble(TestExecution baseline, TestExecution candidate,
            ExecutionComparison comparison, RegressionVerdict verdict) {

        Map<String, String> deltas = new LinkedHashMap<>();
        List<String> evidenceIds = new ArrayList<>();
        for (MetricDelta delta : comparison.deltas()) {
            deltas.put(delta.evidenceId(), delta.display() + " (" + delta.percentChangeDisplay() + ")");
            evidenceIds.add(delta.evidenceId());
        }

        List<String> baselineMissing = absentTelemetryFor(baseline);
        List<String> candidateMissing = absentTelemetryFor(candidate);

        List<String> missingOnEitherSide = new ArrayList<>();
        baselineMissing.forEach(line -> missingOnEitherSide.add("Baseline: " + line));
        candidateMissing.forEach(line -> missingOnEitherSide.add("Candidate: " + line));

        Comparability comparability = Comparability.classify(comparison,
                !baselineMissing.isEmpty(), !candidateMissing.isEmpty());

        return new ComparisonContext(
                label(baseline),
                label(candidate),
                comparability.name(),
                comparison.differences(),
                verdict.name(),
                deltas,
                evidenceIds,
                missingOnEitherSide);
    }

    private List<String> absentTelemetryFor(TestExecution execution) {
        return execution.summaryIfPresent()
                .map(evidenceAssembler::getAbsentTelemetry)
                .orElse(List.of("no measurements were produced for this run"));
    }

    private String label(TestExecution execution) {
        String version = execution.plan().serviceVersion();
        return (version == null || version.isBlank()) ? execution.plan().workloadName() : version;
    }
}
