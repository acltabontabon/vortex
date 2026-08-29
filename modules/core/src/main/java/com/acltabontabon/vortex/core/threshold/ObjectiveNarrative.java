package com.acltabontabon.vortex.core.threshold;

import com.acltabontabon.vortex.core.shared.ErrorRate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic, templated plain-language text about a {@link ThresholdSet} — what it requires, how
 * it compares to a reference figure, and, for a saturating test, the condition under which it
 * flags a breakpoint. Every sentence here is assembled from stated numbers; nothing is composed by a
 * language model, so the same configuration always reads the same way.
 */
public final class ObjectiveNarrative {

    /** How close two figures have to be before the comparison reads as "roughly matches" rather than a percentage. */
    private static final BigDecimal ROUGHLY_MATCHES_THRESHOLD_PERCENT = BigDecimal.valueOf(3);

    private ObjectiveNarrative() {
    }

    /** A figure to compare a threshold against, and the phrase naming where it came from. */
    public record Reference(Duration latency, ErrorRate errorRate, String label) {

        public Reference {
            Objects.requireNonNull(label, "label");
            if ((latency == null) == (errorRate == null)) {
                throw new IllegalArgumentException("a reference carries exactly one of latency or errorRate");
            }
        }

        public static Reference latency(Duration value, String label) {
            return new Reference(value, null, label);
        }

        public static Reference errorRate(ErrorRate value, String label) {
            return new Reference(null, value, label);
        }
    }

    /**
     * The full narrative: one sentence stating what the configured objectives require, followed by
     * one comparison sentence per threshold that has a {@link Reference}, in declaration order.
     *
     * @param references keyed by {@code Threshold.id()}; a threshold with no entry is described with
     *                    no comparison clause, which is the normal case for a plain manual objective
     */
    public static String describe(ThresholdSet thresholds, Map<String, Reference> references) {
        Objects.requireNonNull(thresholds, "thresholds");
        Map<String, Reference> byId = references == null ? Map.of() : references;
        if (thresholds.isEmpty()) {
            return "No objectives are configured for this workload — a run will still generate traffic, "
                    + "but it cannot produce a pass/fail verdict.";
        }

        StringBuilder text = new StringBuilder("At the requested workload, ").append(requirementClause(thresholds)).append('.');

        for (var threshold : thresholds.thresholds()) {
            Reference reference = byId.get(threshold.id());
            if (reference == null) {
                continue;
            }
            text.append(' ').append(comparisonSentence(threshold, reference));
        }
        return text.toString();
    }

    private static String requirementClause(ThresholdSet thresholds) {
        var latency = thresholds.latencyThresholds();
        var errorRate = thresholds.errorRateThreshold();

        StringBuilder clause = new StringBuilder();
        for (int i = 0; i < latency.size(); i++) {
            LatencyThreshold threshold = latency.get(i);
            if (i > 0) {
                clause.append(latency.size() == 2 ? " and " : i == latency.size() - 1 ? ", and " : ", ");
            }
            clause.append("at least ").append(threshold.percentile().label().substring(1))
                    .append("% of requests")
                    .append(threshold.scope().isOverall() ? "" : threshold.scope().describeSuffix())
                    .append(" must complete within ").append(Durations.display(threshold.maximum()));
        }
        if (errorRate.isPresent()) {
            if (!latency.isEmpty()) {
                clause.append(latency.size() == 1 ? " and " : ", and ");
            }
            clause.append("fewer than ").append(errorRate.get().maximum().display()).append(" of requests may fail");
        }
        if (clause.isEmpty()) {
            return "an operation-scoped objective applies, with no overall objective configured";
        }
        return clause.toString();
    }

    private static String comparisonSentence(Threshold threshold, Reference reference) {
        String prefix = threshold.describe().substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                + threshold.describe().substring(1);
        if (threshold instanceof LatencyThreshold latencyThreshold && reference.latency() != null) {
            return prefix + " — " + compareLatency(latencyThreshold.maximum(), reference.latency(), reference.label());
        }
        if (threshold instanceof ErrorRateThreshold errorRateThreshold && reference.errorRate() != null) {
            return prefix + " — " + compareErrorRate(errorRateThreshold.maximum(), reference.errorRate(), reference.label());
        }
        return prefix + ".";
    }

    /** The single-line "how does this compare" sentence — also the live-typing feedback text. */
    public static String compareLatency(Duration proposed, Duration reference, String referenceLabel) {
        Objects.requireNonNull(proposed, "proposed");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(referenceLabel, "referenceLabel");
        BigDecimal percent = percentDelta(reference.toNanos(), proposed.toNanos());
        return renderComparison(percent, referenceLabel);
    }

    /** The single-line "how does this compare" sentence for an error-rate threshold. */
    public static String compareErrorRate(ErrorRate proposed, ErrorRate reference, String referenceLabel) {
        Objects.requireNonNull(proposed, "proposed");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(referenceLabel, "referenceLabel");
        if (reference.fraction().signum() == 0) {
            return proposed.fraction().signum() == 0
                    ? "This matches " + referenceLabel + ", which observed no failures."
                    : "This allows failures where " + referenceLabel + " observed none.";
        }
        BigDecimal percent = percentDelta(
                reference.fraction().unscaledValue().longValue(), proposed.fraction().unscaledValue().longValue());
        return renderComparison(percent, referenceLabel);
    }

    private static BigDecimal percentDelta(long referenceValue, long proposedValue) {
        return BigDecimal.valueOf(referenceValue - proposedValue)
                .divide(BigDecimal.valueOf(referenceValue), 4, RoundingMode.HALF_UP)
                .movePointRight(2);
    }

    private static String renderComparison(BigDecimal percent, String referenceLabel) {
        BigDecimal magnitude = percent.abs();
        if (magnitude.compareTo(ROUGHLY_MATCHES_THRESHOLD_PERCENT) < 0) {
            return "This roughly matches " + referenceLabel + ".";
        }
        String rounded = magnitude.setScale(0, RoundingMode.HALF_UP).toPlainString();
        return percent.signum() > 0
                ? rounded + "% stricter than " + referenceLabel + "."
                : rounded + "% looser than " + referenceLabel + ".";
    }

    /**
     * The condition under which this set flags a breakpoint — any threshold failing, joined by "OR".
     * Reuses the same OR-across-thresholds semantics {@code BreakpointDetector} already evaluates; this
     * only states that condition in one sentence, it never re-derives it.
     */
    public static String describeBreakpointCondition(ThresholdSet thresholds) {
        Objects.requireNonNull(thresholds, "thresholds");
        if (thresholds.isEmpty()) {
            return "No objectives are configured, so no breakpoint condition can be evaluated.";
        }
        Map<String, String> clauses = new LinkedHashMap<>();
        for (Threshold threshold : thresholds.thresholds()) {
            clauses.put(threshold.id(), violationClause(threshold));
        }
        if (clauses.size() == 1) {
            return "This test flags a breakpoint when " + clauses.values().iterator().next() + ".";
        }
        return "This test flags a breakpoint when " + String.join(" OR ", clauses.values()) + ".";
    }

    private static String violationClause(Threshold threshold) {
        if (threshold instanceof LatencyThreshold latency) {
            return latency.percentile().label() + " latency" + latency.scope().describeSuffix()
                    + " exceeds " + Durations.display(latency.maximum());
        }
        if (threshold instanceof ErrorRateThreshold errorRate) {
            return "error rate" + errorRate.scope().describeSuffix() + " exceeds " + errorRate.maximum().display();
        }
        throw new IllegalStateException("unrecognised threshold type: " + threshold.getClass());
    }
}
