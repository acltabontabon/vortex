package dev.vortex.core.threshold;

import java.util.Objects;

/**
 * The evaluation of one threshold against measured results.
 *
 * @param threshold        the objective being evaluated
 * @param verdict          the deterministic outcome
 * @param observed         human-readable observed value, e.g. {@code 522 ms} or {@code 0.8%}; empty
 *                         when the measurement was unavailable
 * @param note             why the threshold could not be evaluated, when applicable
 * @param observedPosition the observed value as a fraction of the threshold's own limit (1.0 sits
 *                         exactly at the limit; a failed objective can exceed 1.0) — null when the
 *                         measurement was unavailable. Exists so a renderer can place a marker on an
 *                         objective bar without re-parsing {@code observed}, the same discipline
 *                         {@code CapacityRange.Marker.position} already follows for capacity figures.
 */
public record ThresholdResult(
        Threshold threshold, Verdict verdict, String observed, String note, Double observedPosition) {

    public ThresholdResult {
        Objects.requireNonNull(threshold, "threshold");
        Objects.requireNonNull(verdict, "verdict");
        observed = observed == null ? "" : observed;
        note = note == null ? "" : note;
    }

    public static ThresholdResult pass(Threshold threshold, String observed) {
        return new ThresholdResult(threshold, Verdict.PASS, observed, "", null);
    }

    public static ThresholdResult pass(Threshold threshold, String observed, Double observedPosition) {
        return new ThresholdResult(threshold, Verdict.PASS, observed, "", observedPosition);
    }

    public static ThresholdResult fail(Threshold threshold, String observed) {
        return new ThresholdResult(threshold, Verdict.FAIL, observed, "", null);
    }

    public static ThresholdResult fail(Threshold threshold, String observed, Double observedPosition) {
        return new ThresholdResult(threshold, Verdict.FAIL, observed, "", observedPosition);
    }

    public static ThresholdResult notEvaluated(Threshold threshold, String note) {
        return new ThresholdResult(threshold, Verdict.NOT_EVALUATED, "", note, null);
    }

    public String thresholdId() {
        return threshold.id();
    }
}
