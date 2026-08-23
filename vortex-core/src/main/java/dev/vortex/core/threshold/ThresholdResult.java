package dev.vortex.core.threshold;

import java.util.Objects;

/**
 * The evaluation of one threshold against measured results.
 *
 * @param threshold the objective being evaluated
 * @param verdict   the deterministic outcome
 * @param observed  human-readable observed value, e.g. {@code 522 ms} or {@code 0.8%}; empty when
 *                  the measurement was unavailable
 * @param note      why the threshold could not be evaluated, when applicable
 */
public record ThresholdResult(Threshold threshold, Verdict verdict, String observed, String note) {

    public ThresholdResult {
        Objects.requireNonNull(threshold, "threshold");
        Objects.requireNonNull(verdict, "verdict");
        observed = observed == null ? "" : observed;
        note = note == null ? "" : note;
    }

    public static ThresholdResult pass(Threshold threshold, String observed) {
        return new ThresholdResult(threshold, Verdict.PASS, observed, "");
    }

    public static ThresholdResult fail(Threshold threshold, String observed) {
        return new ThresholdResult(threshold, Verdict.FAIL, observed, "");
    }

    public static ThresholdResult notEvaluated(Threshold threshold, String note) {
        return new ThresholdResult(threshold, Verdict.NOT_EVALUATED, "", note);
    }

    public String thresholdId() {
        return threshold.id();
    }
}
