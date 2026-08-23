package com.acltabontabon.vortex.core.capacity;

import com.acltabontabon.vortex.core.analysis.EvidenceStrength;
import com.acltabontabon.vortex.core.shared.LoadLevel;
import java.util.List;
import java.util.Optional;

/**
 * The highest level demonstrated to be sustainable, or the refusal and which condition failed.
 *
 * <p>Frequently lower than the highest level that passed, and sometimes absent on a run that
 * produces one. That is the point. The existing figure keeps its name and meaning — it is the
 * highest compliant edge, and remains the honest answer to "what level passed?" — while this answers
 * a stricter question and says so.
 *
 * <h2>Weaker when condition five could not be evaluated</h2>
 * A capacity established without resource telemetry is a weaker claim than one established with it.
 * The difference is stated rather than left to the reader, which is why {@link #strength()} drops
 * when any condition is {@code NOT_EVALUATED} rather than the capacity simply being refused.
 *
 * @param level                   the sustainable level, or null when none was established
 * @param conditions              all five, individually evaluated, in a fixed order
 * @param highestLevelThatPassed  today's tested SLO-compliant capacity, shown beneath the headline
 * @param refusal                 why there is no figure, when there is not
 */
public record SustainableCapacity(LoadLevel level, List<ConditionResult> conditions,
                                  LoadLevel highestLevelThatPassed, EvidenceStrength strength,
                                  String refusal) {

    public SustainableCapacity {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        strength = strength == null ? EvidenceStrength.INSUFFICIENT : strength;
        refusal = refusal == null ? "" : refusal;
    }

    /** A run whose sustainable capacity was never computed — an older observation. */
    public static SustainableCapacity notEvaluated() {
        return new SustainableCapacity(null, List.of(), null, EvidenceStrength.INSUFFICIENT, "");
    }

    public static SustainableCapacity notEstablished(LoadLevel highestPassing,
            List<ConditionResult> conditions, String refusal) {
        return new SustainableCapacity(null, conditions, highestPassing,
                EvidenceStrength.INSUFFICIENT, refusal);
    }

    public static SustainableCapacity established(LoadLevel level, List<ConditionResult> conditions,
            LoadLevel highestPassing, EvidenceStrength strength) {
        return new SustainableCapacity(level, conditions, highestPassing, strength, "");
    }

    public boolean isEstablished() {
        return level != null;
    }

    public Optional<LoadLevel> levelIfPresent() {
        return Optional.ofNullable(level);
    }

    public Optional<LoadLevel> highestLevelThatPassedIfPresent() {
        return Optional.ofNullable(highestLevelThatPassed);
    }

    /** The conditions that blocked the claim. Empty when it was established. */
    public List<ConditionResult> unmet() {
        return conditions.stream().filter(ConditionResult::blocks).toList();
    }

    /** Conditions nothing could answer — the run is weaker for them without being refused. */
    public List<ConditionResult> notEvaluatedConditions() {
        return conditions.stream()
                .filter(result -> result.outcome() == ConditionResult.Outcome.NOT_EVALUATED)
                .toList();
    }

    /** What a page shows in the largest type, whether or not there is a number. */
    public String headline() {
        if (isEstablished()) {
            return level.displayWithUnit();
        }
        return refusal.isBlank() ? "No sustainable capacity was established." : refusal;
    }
}
