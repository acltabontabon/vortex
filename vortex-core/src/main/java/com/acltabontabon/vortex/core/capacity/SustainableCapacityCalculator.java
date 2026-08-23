package com.acltabontabon.vortex.core.capacity;

import com.acltabontabon.vortex.core.analysis.EvidenceStrength;
import com.acltabontabon.vortex.core.analysis.LimitFindings;
import com.acltabontabon.vortex.core.analysis.ResourcePressure;
import com.acltabontabon.vortex.core.analysis.StageObservation;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.resource.ResourceSignal;
import com.acltabontabon.vortex.core.shared.LoadLevel;
import com.acltabontabon.vortex.core.threshold.Durations;
import com.acltabontabon.vortex.core.validity.RunQualityAssessment;
import com.acltabontabon.vortex.core.workload.Stage;
import com.acltabontabon.vortex.core.workload.TestType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The highest level for which all five sustainability conditions hold.
 *
 * <p>Walks levels downward from the highest offered, because the answer wanted is the largest one
 * that survives every check — and stops at the first that does. Where none does, the refusal names
 * the condition that failed at the highest level tried, which is the one an engineer would act on.
 *
 * <h2>Condition five is conditional, never assumed</h2>
 * Where no resource telemetry exists, "no resource reached its limit" is not something the run
 * established. It is reported as {@code NOT_EVALUATED} and the resulting capacity is a weaker claim,
 * stated as such — rather than being either refused outright or silently upgraded.
 */
public final class SustainableCapacityCalculator {

    public SustainableCapacity calculate(EffectiveTestPlan plan, List<StageObservation> stages,
            RunQualityAssessment quality, LimitFindings limits) {

        if (plan == null || stages == null || stages.isEmpty()) {
            return SustainableCapacity.notEvaluated();
        }

        List<StageObservation> descending = stages.stream()
                .sorted(Comparator.comparingDouble(
                        (StageObservation stage) -> stage.targetLoad().asDouble()).reversed())
                .toList();

        LoadLevel highestPassing = descending.stream()
                .filter(StageObservation::isCompliant)
                .map(StageObservation::targetLoad)
                .findFirst()
                .orElse(null);

        List<ConditionResult> highestAttempt = List.of();

        for (StageObservation stage : descending) {
            List<ConditionResult> conditions = evaluate(plan, stage, quality, limits);
            if (highestAttempt.isEmpty()) {
                highestAttempt = conditions;
            }
            if (conditions.stream().noneMatch(ConditionResult::blocks)) {
                boolean anyUnevaluated = conditions.stream()
                        .anyMatch(result -> result.outcome() == ConditionResult.Outcome.NOT_EVALUATED);
                return SustainableCapacity.established(stage.targetLoad(), conditions,
                        highestPassing,
                        // A capacity established without resource telemetry is a weaker claim than
                        // one established with it, and the difference is stated rather than left to
                        // the reader to infer.
                        anyUnevaluated ? EvidenceStrength.MEDIUM : EvidenceStrength.HIGH);
            }
        }

        ConditionResult blocking = highestAttempt.stream()
                .filter(ConditionResult::blocks)
                .findFirst()
                .orElse(null);

        return SustainableCapacity.notEstablished(highestPassing, highestAttempt,
                blocking == null
                        ? "No sustainable capacity was established by this run."
                        : "No sustainable capacity was established. " + blocking.statement());
    }

    /** All five conditions at one level, always in the same order, always all of them. */
    private List<ConditionResult> evaluate(EffectiveTestPlan plan, StageObservation stage,
            RunQualityAssessment quality, LimitFindings limits) {

        List<ConditionResult> conditions = new ArrayList<>();
        conditions.add(loadWasGenerated(stage, quality));
        conditions.add(objectivesWereMet(stage));
        conditions.add(heldLongEnough(plan, stage));
        conditions.add(throughputTracked(stage, limits));
        conditions.add(noResourceAtItsLimit(stage));
        return conditions;
    }

    private ConditionResult loadWasGenerated(StageObservation stage,
            RunQualityAssessment quality) {

        if (quality == null || quality.permitsCapacityAt(stage.targetLoad())) {
            return ConditionResult.met(SustainabilityCondition.OFFERED_LOAD_WAS_GENERATED,
                    "Nothing in this run's validity prevents a capacity claim at "
                            + stage.targetLoad().displayWithUnit() + ".");
        }
        String why = quality.findings().stream()
                .filter(finding -> finding.withholdsCapacityAt(stage.targetLoad()))
                .map(finding -> finding.statement())
                .findFirst()
                .orElse("This run's validity withholds capacity claims at this level.");
        return ConditionResult.notMet(SustainabilityCondition.OFFERED_LOAD_WAS_GENERATED, why);
    }

    private ConditionResult objectivesWereMet(StageObservation stage) {
        if (stage.isCompliant()) {
            return ConditionResult.met(SustainabilityCondition.OBJECTIVES_WERE_MET,
                    "Every declared objective held at " + stage.targetLoad().displayWithUnit() + ".");
        }
        return ConditionResult.notMet(SustainabilityCondition.OBJECTIVES_WERE_MET,
                "Objectives were violated at " + stage.targetLoad().displayWithUnit() + ": "
                        + String.join(", ", stage.violatedThresholds()) + ".");
    }

    /**
     * Whether the level was held, rather than passed through by a ramp.
     *
     * <p>Fifteen seconds of a ramp and ten minutes at a plateau are not the same evidence: JIT
     * compilation, connection-pool growth, cache fill and the first collection cycle all land inside
     * the first minute of any level.
     */
    private ConditionResult heldLongEnough(EffectiveTestPlan plan, StageObservation stage) {
        Optional<Duration> required = plan.validityPolicy().sustainDuration(plan.testType());
        if (required.isEmpty()) {
            return ConditionResult.notMet(SustainabilityCondition.HELD_FOR_THE_SUSTAIN_DURATION,
                    typeNeverQuotable(plan.testType()));
        }
        Duration held = heldAt(plan, stage.targetLoad());
        if (held.compareTo(required.get()) >= 0) {
            return ConditionResult.met(SustainabilityCondition.HELD_FOR_THE_SUSTAIN_DURATION,
                    "Held for " + Durations.display(held) + ", against the "
                            + Durations.display(required.get()) + " this test type requires.");
        }
        return ConditionResult.notMet(SustainabilityCondition.HELD_FOR_THE_SUSTAIN_DURATION,
                "Held for " + Durations.display(held) + "; " + plan.testType().asPhrase()
                        + " requires " + Durations.display(required.get())
                        + " before a level is quotable as capacity.");
    }

    private String typeNeverQuotable(TestType type) {
        return switch (type) {
            case SMOKE -> "A smoke test exists to check the workload is valid, and never establishes "
                    + "a capacity.";
            case SPIKE -> "A spike test's subject is abrupt arrival: no level is held, so no capacity "
                    + "is claimed from it.";
            default -> "This test type holds no level long enough to quote as capacity.";
        };
    }

    /**
     * How long the plan <em>held</em> this level, as opposed to passing through it.
     *
     * <p>A stage says "move to this target over this duration", so a stage whose target differs from
     * the one before it is a ramp: the level named is where it arrives, not where it stayed. Only a
     * stage whose target matches its predecessor is a plateau, and only plateaus count here. Summing
     * every stage that mentions a level would credit a ramp's whole climb to the level it happened
     * to end at — which is exactly the fifteen-seconds-versus-ten-minutes confusion this condition
     * exists to catch.
     */
    private Duration heldAt(EffectiveTestPlan plan, LoadLevel level) {
        List<Stage> stages = plan.stages();
        Duration held = Duration.ZERO;

        for (int index = 0; index < stages.size(); index++) {
            Stage stage = stages.get(index);
            if (stage.target() == null || stage.target().asDouble() != level.asDouble()) {
                continue;
            }
            boolean isPlateau = index > 0
                    && stages.get(index - 1).target() != null
                    && stages.get(index - 1).target().asDouble() == level.asDouble();
            if (isPlateau) {
                held = held.plus(stage.duration());
            }
        }
        return held;
    }

    private ConditionResult throughputTracked(StageObservation stage, LimitFindings limits) {
        var ceiling = limits == null ? Optional.<LoadLevel>empty()
                : limits.throughputCeilingIfPresent().flatMap(found -> found.levelIfPresent());

        if (ceiling.isPresent() && stage.targetLoad().asDouble() > ceiling.get().asDouble()) {
            return ConditionResult.notMet(SustainabilityCondition.THROUGHPUT_TRACKED_OFFERED_LOAD,
                    "This level is above the throughput ceiling of "
                            + ceiling.get().displayWithUnit()
                            + ", so offering it produced no more throughput than the level below.");
        }
        return stage.rateShortfall()
                .filter(shortfall -> shortfall > 0.10)
                .map(shortfall -> ConditionResult.notMet(
                        SustainabilityCondition.THROUGHPUT_TRACKED_OFFERED_LOAD,
                        String.format("Achieved throughput fell %.0f%% short of the offered %s.",
                                shortfall * 100, stage.targetLoad().displayWithUnit())))
                .orElseGet(() -> ConditionResult.met(
                        SustainabilityCondition.THROUGHPUT_TRACKED_OFFERED_LOAD,
                        "Achieved throughput tracked the offered "
                                + stage.targetLoad().displayWithUnit() + "."));
    }

    private ConditionResult noResourceAtItsLimit(StageObservation stage) {
        List<ResourceSignal> measured = stage.serviceResourceSignals().stream()
                .filter(signal -> signal.limitIfPresent().isPresent())
                .toList();

        if (measured.isEmpty()) {
            // Not evaluated, and emphatically not met. The run did not establish that no resource
            // ran out; it established nothing about resources at all.
            return ConditionResult.notEvaluated(
                    SustainabilityCondition.NO_RESOURCE_REACHED_ITS_LIMIT,
                    "No resource telemetry with a declared limit covered this level, so whether any "
                            + "resource ran out is unknown rather than ruled out.");
        }
        Optional<ResourceSignal> pressured = measured.stream()
                .filter(ResourcePressure::isUnderPressure)
                .findFirst();

        return pressured
                .map(signal -> ConditionResult.notMet(
                        SustainabilityCondition.NO_RESOURCE_REACHED_ITS_LIMIT,
                        signal.describe() + " while the workload held "
                                + stage.targetLoad().displayWithUnit() + "."))
                .orElseGet(() -> ConditionResult.met(
                        SustainabilityCondition.NO_RESOURCE_REACHED_ITS_LIMIT,
                        measured.size() + " observed resource"
                                + (measured.size() == 1 ? "" : "s")
                                + " stayed clear of their declared limits at this level."));
    }
}
