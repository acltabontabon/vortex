package com.acltabontabon.vortex.core.evidence;

import com.acltabontabon.vortex.core.analysis.EvidenceIds;
import com.acltabontabon.vortex.core.analysis.EvidenceStrength;
import com.acltabontabon.vortex.core.analysis.ResourcePressure;
import com.acltabontabon.vortex.core.analysis.SloBreakpoint;
import com.acltabontabon.vortex.core.analysis.StageObservation;
import com.acltabontabon.vortex.core.analysis.SystemSaturation;
import com.acltabontabon.vortex.core.metrics.OperationMetrics;
import com.acltabontabon.vortex.core.shared.Percentile;
import com.acltabontabon.vortex.core.threshold.ThresholdResult;
import com.acltabontabon.vortex.core.threshold.Verdict;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Derives what can be said about a run from the measurements alone.
 *
 * <p>A pure function: no clock, no randomness, no I/O, and the same run always produces the same
 * findings in the same order. That is what makes this the third tier of the evidence model rather
 * than a fourth — anything a language model contributes is layered on top of these and never in
 * place of them.
 *
 * <p>Every rule cites {@link EvidenceIds}, which is the same vocabulary the AI citation validator
 * accepts. Sharing one definition is deliberate: it was briefly written twice and the two drifted
 * immediately.
 *
 * <h2>On causation</h2>
 *
 * <p>Rule {@code observation.correlation} is the one that would be easiest to get wrong. It fires
 * when a resource signal peaked and objectives were violated, and it says the two
 * <em>coincided</em>. It does not say one produced the other, because a run observes association and
 * nothing more. The wording is enforced by test against the say/not-say table in
 * {@code docs/02-architecture/execution-and-evidence.adoc} (Evidence model).
 */
public final class FindingDetector {


    /** How much worse than the aggregate an operation must be before it is called out. */
    private static final double LATENCY_DIVERGENCE = 2.0;

    private static final double ERROR_DIVERGENCE = 3.0;

    /** Below this many buckets, a run has not observed enough to support a shape. */
    private static final int THIN_EVIDENCE_BUCKETS = 3;

    public List<DeterministicFinding> detect(RunIdentity identity, WorkloadEvidence workload,
            PerformanceEvidence performance, AcceptanceEvidence acceptance,
            List<OperationEvidence> operations, ObservabilityEvidence observability) {
        return detect(identity, workload, performance, acceptance, operations, observability,
                List.of());
    }

    /**
     * @param stages per-stage evidence, so a resource crossing can be placed at a level of load
     *               rather than only somewhere in the run
     */
    public List<DeterministicFinding> detect(RunIdentity identity, WorkloadEvidence workload,
            PerformanceEvidence performance, AcceptanceEvidence acceptance,
            List<OperationEvidence> operations, ObservabilityEvidence observability,
            List<StageObservation> stages) {

        List<DeterministicFinding> findings = new ArrayList<>();

        objectives(acceptance, findings);
        workload(workload, findings);
        limits(performance, findings);
        operations(performance, operations, findings);
        resources(observability, performance, stages, findings);

        // Most serious first, but stable within a level so the order a reader sees is the order the
        // rules ran — which makes a report diffable against the previous one.
        findings.sort(Comparator.comparingInt(finding -> finding.level().rank()));
        return List.copyOf(findings);
    }

    // ------------------------------------------------------------------ objectives

    private void objectives(AcceptanceEvidence acceptance, List<DeterministicFinding> findings) {
        if (!acceptance.hasObjectives()) {
            findings.add(new DeterministicFinding(
                    "finding:objectives.absent", FindingLevel.WARNING,
                    "This run had no objectives, so it neither passed nor failed.",
                    "Measurements were collected and are reported below, but nothing was asserted "
                            + "about them. Add objectives to the workload for a verdict.",
                    EvidenceStrength.HIGH, List.of(EvidenceIds.REQUEST_COUNT)));
            return;
        }

        for (ThresholdResult result : acceptance.results()) {
            String id = "finding:threshold." + result.thresholdId();
            List<String> citations = List.of(EvidenceIds.threshold(result.thresholdId()));

            switch (result.verdict()) {
                case FAIL -> findings.add(new DeterministicFinding(id, FindingLevel.FAIL,
                        result.threshold().describe() + " was not met, at " + result.observed() + ".",
                        "", EvidenceStrength.HIGH, citations));

                // Never a pass. An objective that was never checked has not been met, and the
                // difference between "failed" and "could not be checked" has a different remedy.
                case NOT_EVALUATED -> findings.add(new DeterministicFinding(id, FindingLevel.WARNING,
                        result.threshold().describe() + " was not evaluated.",
                        result.note(), EvidenceStrength.INSUFFICIENT, citations));

                case PASS -> findings.add(new DeterministicFinding(id, FindingLevel.PASS,
                        result.threshold().describe() + " was met, at " + result.observed() + ".",
                        "", EvidenceStrength.HIGH, citations));

                default -> throw new IllegalStateException("unhandled verdict " + result.verdict());
            }
        }
    }

    // ------------------------------------------------------------------ workload

    private void workload(WorkloadEvidence workload, List<DeterministicFinding> findings) {
        if (!workload.isOpen()) {
            findings.add(new DeterministicFinding(
                    "finding:workload.closed", FindingLevel.OBSERVATION,
                    "Throughput here is an outcome, not a target.",
                    "A concurrency workload holds a fixed population of virtual users, each issuing "
                            + "its next request only once the previous one returns. The rate the "
                            + "service achieved is therefore a result of its own latency, and there "
                            + "is no shortfall to report.",
                    EvidenceStrength.HIGH, List.of(EvidenceIds.THROUGHPUT_ACHIEVED)));
            return;
        }

        workload.deliveredFractionIfPresent().ifPresent(fraction -> {
            List<String> citations =
                    List.of(EvidenceIds.THROUGHPUT_TARGET, EvidenceIds.THROUGHPUT_ACHIEVED);
            String percent = workload.deliveredPercent().orElse("");

            if (fraction >= WorkloadEvidence.SUSTAINED) {
                findings.add(new DeterministicFinding(
                        "finding:throughput.sustained", FindingLevel.PASS,
                        "The configured workload of " + workload.configuredPeak().displayWithUnit()
                                + " was sustained for the whole run.",
                        "", EvidenceStrength.HIGH, citations));
                return;
            }

            boolean severe = fraction < WorkloadEvidence.SHORTFALL - 0.15;
            findings.add(new DeterministicFinding(
                    "finding:throughput.shortfall",
                    severe ? FindingLevel.FAIL : FindingLevel.WARNING,
                    "The run delivered " + percent + " of the offered "
                            + workload.configuredPeak().displayWithUnit() + ".",
                    "Either the service could not absorb the offered traffic or the load generator "
                            + "could not sustain it. The two are distinguishable in the raw engine "
                            + "output, and until they are distinguished this run did not test what "
                            + "it was configured to test.",
                    EvidenceStrength.MEDIUM, citations));
        });
    }

    // ------------------------------------------------------------------ limits

    private void limits(PerformanceEvidence performance, List<DeterministicFinding> findings) {
        performance.sloBreakpointIfPresent().ifPresent(breakpoint -> findings.add(
                new DeterministicFinding(
                        "finding:slo.breakpoint", FindingLevel.WARNING,
                        "Objectives were first violated at " + breakpoint.level().displayWithUnit()
                                + ".",
                        breakpoint.describe(),
                        breakpoint.strength(),
                        breakpointCitations(breakpoint))));

        performance.systemSaturationIfPresent().ifPresent(saturation -> {
            if (saturation.wasObserved()) {
                findings.add(new DeterministicFinding(
                        "finding:saturation.observed", FindingLevel.WARNING,
                        "The service stopped keeping up with the offered load.",
                        saturation.describe(), saturation.strength(),
                        List.of(EvidenceIds.THROUGHPUT_ACHIEVED, EvidenceIds.THROUGHPUT_TARGET)));
            } else {
                findings.add(new DeterministicFinding(
                        "finding:saturation.notEstablished", FindingLevel.OBSERVATION,
                        "This run did not establish where the service stops coping.",
                        saturation.explanation(), EvidenceStrength.INSUFFICIENT,
                        List.of(EvidenceIds.THROUGHPUT_ACHIEVED)));
            }
        });

        performance.headroomIfPresent().ifPresent(headroom -> findings.add(
                new DeterministicFinding(
                        "finding:capacity.headroom", FindingLevel.OBSERVATION,
                        "Tested SLO-compliant capacity is " + headroom.display()
                                + " observed production traffic.",
                        "This is the highest level at which every objective still held, under the "
                                + "conditions this run was carried out in. It is not a maximum "
                                + "throughput.",
                        EvidenceStrength.MEDIUM, List.of(EvidenceIds.THROUGHPUT_ACHIEVED))));
    }

    private List<String> breakpointCitations(SloBreakpoint breakpoint) {
        List<String> citations = new ArrayList<>();
        breakpoint.violatedThresholdIds().forEach(id -> citations.add(EvidenceIds.threshold(id)));
        if (citations.isEmpty()) {
            citations.add(EvidenceIds.THROUGHPUT_TARGET);
        }
        return citations;
    }

    // ------------------------------------------------------------------ operations

    private void operations(PerformanceEvidence performance, List<OperationEvidence> operations,
            List<DeterministicFinding> findings) {

        for (OperationEvidence operation : operations) {
            if (!operation.hasTraffic()) {
                findings.add(new DeterministicFinding(
                        "finding:operation." + operation.operationId().value() + ".noTraffic",
                        FindingLevel.WARNING,
                        operation.name() + " issued no requests.",
                        "It was part of the configured mix, so the traffic this run generated was "
                                + "not the traffic it was asked to generate. Every aggregate below "
                                + "describes a different mix from the one configured.",
                        EvidenceStrength.HIGH,
                        List.of(EvidenceIds.operationRate(operation.operationId()))));
                continue;
            }
            divergence(performance, operation, findings);
        }
    }

    /** An operation far worse than the aggregate is the thing an aggregate is best at hiding. */
    private void divergence(PerformanceEvidence performance, OperationEvidence operation,
            List<DeterministicFinding> findings) {

        OperationMetrics metrics = operation.metrics();
        var aggregate = performance.latency().at(Percentile.P95);
        var operationP95 = metrics.latency().at(Percentile.P95);

        if (aggregate.isPresent() && operationP95.isPresent()) {
            Duration overall = aggregate.get();
            Duration scoped = operationP95.get();
            if (overall.toNanos() > 0
                    && scoped.toNanos() >= overall.toNanos() * LATENCY_DIVERGENCE) {
                findings.add(new DeterministicFinding(
                        "finding:operation." + operation.operationId().value() + ".latency",
                        FindingLevel.WARNING,
                        operation.name() + " was substantially slower than the run as a whole, at p95 "
                                + com.acltabontabon.vortex.core.threshold.Durations.display(scoped)
                                + " against " + com.acltabontabon.vortex.core.threshold.Durations.display(overall)
                                + " overall.",
                        "An aggregate percentile is a weighted blend of every operation in the mix, "
                                + "so one slow operation can sit inside a healthy-looking total.",
                        EvidenceStrength.MEDIUM,
                        List.of(EvidenceIds.operationLatency(operation.operationId(), Percentile.P95),
                                EvidenceIds.latency(Percentile.P95))));
            }
        }

        double overallErrors = performance.errorRate().asPercent();
        double scopedErrors = metrics.errorRate().asPercent();
        if (scopedErrors > 0 && scopedErrors >= Math.max(overallErrors * ERROR_DIVERGENCE, 1.0)) {
            findings.add(new DeterministicFinding(
                    "finding:operation." + operation.operationId().value() + ".errors",
                    FindingLevel.WARNING,
                    operation.name() + " failed far more often than the run as a whole, at "
                            + metrics.errorRate().display() + " against "
                            + performance.errorRate().display() + " overall.",
                    "", EvidenceStrength.MEDIUM,
                    List.of(EvidenceIds.operationErrorRate(operation.operationId()),
                            EvidenceIds.REQUEST_ERROR_RATE)));
        }
    }

    // ------------------------------------------------------------------ resources

    private void resources(ObservabilityEvidence observability, PerformanceEvidence performance,
            List<StageObservation> stages, List<DeterministicFinding> findings) {

        for (ObservedSignal signal : observability.signals()) {
            // Typed, scoped to the service, and against a limit somebody declared. An unclassified
            // measurement at 92% is still shown in the observability section — it is simply not
            // evidence that a resource reached a limit, because nothing said what its limit was.
            if (!signal.canEstablishAServiceLimit()
                    || !ResourcePressure.isUnderPressure(signal.resource())) {
                continue;
            }

            String value = signal.display();
            String movement = signal.movement().map(text -> " It moved " + text + ".").orElse("");

            // Correlation, stated as correlation. The moment this sentence says "caused", it becomes
            // a claim the run cannot support and that will be quoted as though it could.
            boolean alongsideAViolation = performance.sloBreakpointIfPresent().isPresent();

            // Where the stage evidence can place the crossing, saying *at what level of load* it
            // happened is what turns "these coincided in time" into "these coincided in load" —
            // which is a stronger observation without being a different kind of claim.
            StageObservation crossing = stageWhereItCrossed(signal, stages);
            boolean crossedAtTheLimit = crossing != null && alongsideAViolation
                    && performance.sloBreakpointIfPresent()
                    .map(breakpoint -> breakpoint.level().asDouble()
                            == crossing.targetLoad().asDouble())
                    .orElse(false);

            String where = crossing == null ? ""
                    : " It was already at " + crossing.signal(signal.id())
                            .map(observation -> observation.display())
                            .orElse(value)
                            + " while the workload held "
                            + crossing.targetLoad().displayWithUnit() + ".";

            String headline = alongsideAViolation
                    ? signal.name() + " reached " + value
                            + " in the same run in which objectives were first violated."
                    : signal.name() + " reached " + value + " during the run.";

            String detail = alongsideAViolation
                    ? "These two observations coincided. This run establishes that they moved "
                            + "together, not that either produced the other — determining that "
                            + "needs context a load test does not contain." + movement + where
                    : ("A resource close to its limit is worth knowing about even when every "
                            + "objective held." + movement + where).trim();

            // A stage boundary Vortex computed from planned durations cannot raise this. Those
            // timestamps are its own arithmetic; treating them as corroboration would manufacture
            // confidence rather than establish it.
            var basis = crossing == null ? null : crossing.basis();
            String alignment = crossing != null && !crossing.supportsStrongerEvidence()
                    ? " Stage boundaries here were " + basis.label()
                            + ", so this is a correspondence in load rather than a measured "
                            + "coincidence."
                    : "";

            findings.add(new DeterministicFinding(
                    "finding:observation." + shortId(signal.id())
                            + (alongsideAViolation ? ".correlation" : ".high"),
                    FindingLevel.OBSERVATION, headline, detail + alignment,
                    ResourcePressure.strength(signal.trace().isPresent(), crossedAtTheLimit, basis),
                    citationsFor(signal, alongsideAViolation, performance)));
        }
    }

    /**
     * The first stage in which this signal was already under pressure.
     *
     * <p>Null when no stage evidence carries it, which is the ordinary case for a steady workload or
     * a run where no provider answered.
     */
    private StageObservation stageWhereItCrossed(ObservedSignal signal,
            List<StageObservation> stages) {
        if (stages == null) {
            return null;
        }
        for (StageObservation stage : stages) {
            boolean crossed = stage.serviceResourceSignals().stream()
                    .filter(typed -> typed.signalId().equals(signal.id()))
                    .anyMatch(ResourcePressure::isUnderPressure);
            if (crossed) {
                return stage;
            }
        }
        return null;
    }

    private List<String> citationsFor(ObservedSignal signal, boolean alongsideAViolation,
            PerformanceEvidence performance) {
        List<String> citations = new ArrayList<>();
        citations.add(signal.id());
        if (alongsideAViolation) {
            performance.sloBreakpointIfPresent().ifPresent(breakpoint ->
                    breakpoint.violatedThresholdIds()
                            .forEach(id -> citations.add(EvidenceIds.threshold(id))));
        }
        return citations;
    }

    private String shortId(String evidenceId) {
        String withoutPrefix = evidenceId.startsWith(EvidenceIds.METRIC)
                ? evidenceId.substring(EvidenceIds.METRIC.length())
                : evidenceId;
        return withoutPrefix.toLowerCase(Locale.ROOT);
    }
}
