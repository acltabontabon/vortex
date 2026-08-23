package dev.vortex.core.evidence;

import dev.vortex.core.validity.RunQualityAssessment;
import dev.vortex.core.analysis.LimitFindings;
import dev.vortex.core.capacity.SustainableCapacity;
import dev.vortex.core.threshold.Verdict;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything a reader needs in order to judge one run, with no presentation decisions taken.
 *
 * <p>This is the single input to every renderer — the result page, the printable report, and the
 * JSON, Markdown and PDF exports. That rule is the whole point of the type. Before it existed, each
 * surface reached into {@code TestExecution} and decided independently what a section meant, and
 * {@code ReportController} carried a comment observing that "two renderers of the same data drift"
 * while itself being the second renderer. One model makes that structural rather than aspirational.
 *
 * <p>It also gives sanitisation a single choke point. A {@code RunEvidence} is sanitised on the way
 * out of assembly, so no exporter — including one written next year by someone who has not read this
 * comment — can reach around it to a plan that still holds a credential.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>No {@code TestExecution}, no {@code EffectiveTestPlan}, no repository, no engine output. An
 * exporter that finds itself needing one of those is asking for something that has not been through
 * the sanitiser.
 *
 * <h2>Tiers</h2>
 *
 * <p>{@code performance}, {@code acceptance}, {@code operations} and {@code observability} are
 * measurement. {@code findings} are deterministic derivations of them. {@code interpretation} is a
 * language model's reading and is the only field that is not reproducible. The evidence model
 * requires a reader to be able to tell them apart at a glance, so renderers keep the last one
 * visually fenced and attributed.
 *
 * @param qualifications caveats that must accompany the result, such as the environment class
 * @param comparison     an earlier run of the same experiment; null when there is none
 * @param interpretation the AI reading; null when no model was consulted or it failed
 */
public record RunEvidence(
        RunIdentity identity,
        String question,
        Verdict verdict,
        String answer,
        WorkloadEvidence workload,
        PerformanceEvidence performance,
        AcceptanceEvidence acceptance,
        List<OperationEvidence> operations,
        TimelineEvidence timeline,
        ObservabilityEvidence observability,
        ResourceTimelineEvidence resourceTimeline,
        List<DeterministicFinding> findings,
        List<String> qualifications,
        ComparisonEvidence comparison,
        Interpretation interpretation,
        EvidenceProvenance provenance,
        RunQualityAssessment quality,
        LimitFindings limits,
        SustainableCapacity sustainableCapacity) {

    public RunEvidence {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(workload, "workload");
        Objects.requireNonNull(performance, "performance");
        Objects.requireNonNull(acceptance, "acceptance");
        Objects.requireNonNull(provenance, "provenance");
        question = question == null ? "" : question;
        answer = answer == null ? "" : answer;
        operations = operations == null ? List.of() : List.copyOf(operations);
        timeline = timeline == null ? TimelineEvidence.empty() : timeline;
        observability = observability == null ? ObservabilityEvidence.empty() : observability;
        resourceTimeline = resourceTimeline == null
                ? ResourceTimelineEvidence.unavailable() : resourceTimeline;
        findings = findings == null ? List.of() : List.copyOf(findings);
        qualifications = qualifications == null ? List.of() : List.copyOf(qualifications);
        quality = quality == null ? RunQualityAssessment.notAssessed() : quality;
        limits = limits == null ? LimitFindings.notEvaluated() : limits;
        sustainableCapacity = sustainableCapacity == null
                ? SustainableCapacity.notEvaluated() : sustainableCapacity;
    }

    /**
     * Evidence from before the four limits and sustainable capacity were computed.
     *
     * <p>Retained at the previous arity. Both default to <em>not evaluated</em> rather than to
     * <em>none found</em>: an older run did not establish that its service had no throughput
     * ceiling, nobody looked for one.
     */
    public RunEvidence(RunIdentity identity, String question, Verdict verdict, String answer,
            WorkloadEvidence workload, PerformanceEvidence performance,
            AcceptanceEvidence acceptance, List<OperationEvidence> operations,
            TimelineEvidence timeline, ObservabilityEvidence observability,
            List<DeterministicFinding> findings, List<String> qualifications,
            ComparisonEvidence comparison, Interpretation interpretation,
            EvidenceProvenance provenance, RunQualityAssessment quality) {
        this(identity, question, verdict, answer, workload, performance, acceptance, operations,
                timeline, observability, ResourceTimelineEvidence.unavailable(), findings,
                qualifications, comparison, interpretation, provenance, quality,
                LimitFindings.notEvaluated(), SustainableCapacity.notEvaluated());
    }

    public boolean passed() {
        return verdict == Verdict.PASS;
    }

    /**
     * Whether this run measured what it claims to.
     *
     * <p>A first-class part of the evidence rather than a footnote in provenance: a reader deciding
     * whether to quote a capacity figure should not have to open a provenance block to learn that
     * the run never generated the load it asked for.
     *
     * <p>Independent of {@link #verdict()} in both directions, and the two must be read together.
     */
    public boolean measuredWhatItClaims() {
        return !quality.isInvalid();
    }

    public Optional<ComparisonEvidence> comparisonIfPresent() {
        return Optional.ofNullable(comparison);
    }

    public Optional<Interpretation> interpretationIfPresent() {
        return Optional.ofNullable(interpretation);
    }

    public boolean hasTimeline() {
        return timeline.isRenderable();
    }

    public boolean hasObservability() {
        return !observability.isEmpty();
    }

    public boolean hasOperationBreakdown() {
        return !operations.isEmpty();
    }

    public boolean hasFindings() {
        return !findings.isEmpty();
    }

    /** Findings at one level, in the order they were derived. */
    public List<DeterministicFinding> findingsAt(FindingLevel level) {
        return findings.stream().filter(finding -> finding.level() == level).toList();
    }

    /** Every level that has at least one finding, most serious first. */
    public List<FindingLevel> findingLevels() {
        return findings.stream()
                .map(DeterministicFinding::level)
                .distinct()
                .sorted(Comparator.comparingInt(FindingLevel::rank))
                .toList();
    }

    /**
     * Operations that issued no traffic at all.
     *
     * <p>Surfaced separately because they are the quietest way for a run to be wrong: a planned
     * operation that never fired makes the mix different from the one configured, and every
     * aggregate below it describes an experiment nobody asked for.
     */
    public List<OperationEvidence> operationsWithoutTraffic() {
        return operations.stream().filter(operation -> !operation.hasTraffic()).toList();
    }
}
