package dev.vortex.core.capacity;

import java.util.Objects;

/**
 * One sustainability condition, evaluated, with the number behind it.
 *
 * <p>Never a bare boolean. "Held long enough: no" is not something an engineer can act on; "held for
 * 2m; an average-load test requires 5m before a level is quotable as capacity" is both the
 * qualification and the instruction for fixing it.
 *
 * @param statement what was measured and what it was measured against
 */
public record ConditionResult(SustainabilityCondition condition, Outcome outcome, String statement) {

    public enum Outcome {

        MET("Met"),

        NOT_MET("Not met"),

        /**
         * Nothing was measured that could answer this.
         *
         * <p>Deliberately not {@link #MET}. Condition five is the one that lands here: where no
         * resource telemetry exists, "no resource reached its limit" is not something the run
         * established, and treating an absence as satisfaction is how a capacity figure quietly
         * becomes stronger than its evidence.
         */
        NOT_EVALUATED("Not evaluated");

        private final String label;

        Outcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public ConditionResult {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(outcome, "outcome");
        if (statement == null || statement.isBlank()) {
            throw new IllegalArgumentException(
                    "a sustainability condition must state what it measured; " + condition
                            + " on its own tells a reader nothing they can check");
        }
    }

    public static ConditionResult met(SustainabilityCondition condition, String statement) {
        return new ConditionResult(condition, Outcome.MET, statement);
    }

    public static ConditionResult notMet(SustainabilityCondition condition, String statement) {
        return new ConditionResult(condition, Outcome.NOT_MET, statement);
    }

    public static ConditionResult notEvaluated(SustainabilityCondition condition, String statement) {
        return new ConditionResult(condition, Outcome.NOT_EVALUATED, statement);
    }

    /** Whether this condition blocks the claim. An unevaluated condition weakens it instead. */
    public boolean blocks() {
        return outcome == Outcome.NOT_MET;
    }
}
