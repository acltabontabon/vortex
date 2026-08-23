package com.acltabontabon.vortex.core.comparison;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/**
 * The change in one measurement between two executions.
 *
 * @param metric      what changed, e.g. {@code p95 latency}
 * @param metricId    stable identifier for this delta, e.g. {@code latency.p95} — combined with
 *                    {@link com.acltabontabon.vortex.core.analysis.EvidenceIds#DELTA} this is what an AI
 *                    interpretation of a comparison must cite, the same discipline {@link
 *                    com.acltabontabon.vortex.core.analysis.EvidenceIds} already applies to single-run metrics
 * @param baseline    the earlier value
 * @param candidate   the later value
 * @param display     human-readable form of both values
 * @param lowerIsBetter whether a decrease is an improvement
 */
public record MetricDelta(
        String metric,
        String metricId,
        BigDecimal baseline,
        BigDecimal candidate,
        String display,
        boolean lowerIsBetter,
        DeltaKind kind) {

    public MetricDelta {
        Objects.requireNonNull(metric, "metric");
        metricId = metricId == null ? "" : metricId;
        display = display == null ? "" : display;
        // Defaulted rather than inferred from the identifier. Deducing "this id contains 'latency',
        // so it is a latency delta" is a string match on a metric name, which is the thing the
        // reasoning model is not allowed to do - and it would silently mis-file a renamed metric
        // into a category whose refusals then do not apply to it.
        kind = kind == null ? DeltaKind.THROUGHPUT : kind;
    }

    /** Convenience constructor for callers that have not assigned a stable identifier. */
    public MetricDelta(String metric, BigDecimal baseline, BigDecimal candidate, String display,
            boolean lowerIsBetter) {
        this(metric, "", baseline, candidate, display, lowerIsBetter, DeltaKind.THROUGHPUT);
    }

    /** A delta with an identifier but no declared kind. */
    public MetricDelta(String metric, String metricId, BigDecimal baseline, BigDecimal candidate,
            String display, boolean lowerIsBetter) {
        this(metric, metricId, baseline, candidate, display, lowerIsBetter, DeltaKind.THROUGHPUT);
    }

    /** The identifier an AI interpretation must cite to reference this delta. */
    public String evidenceId() {
        return com.acltabontabon.vortex.core.analysis.EvidenceIds.delta(metricId);
    }

    /** Relative change as a percentage, or empty when the baseline is zero. */
    public Optional<BigDecimal> percentChange() {
        if (baseline == null || candidate == null || baseline.signum() == 0) {
            return Optional.empty();
        }
        return Optional.of(candidate.subtract(baseline)
                .divide(baseline, MathContext.DECIMAL64)
                .movePointRight(2)
                .setScale(1, RoundingMode.HALF_UP));
    }

    /** Signed display, e.g. {@code +44.6%} or {@code −3.2%}. */
    public String percentChangeDisplay() {
        return percentChange()
                .map(change -> (change.signum() >= 0 ? "+" : "") + change.toPlainString() + "%")
                .orElse("—");
    }

    /** Whether the change is in the direction the user wants. Empty when unchanged or unknown. */
    public Optional<Boolean> isImprovement() {
        return percentChange().map(change -> {
            if (change.signum() == 0) {
                return null;
            }
            return lowerIsBetter == (change.signum() < 0);
        });
    }

    /**
     * Whether this measurement moved away from zero, in either direction.
     *
     * <p>A relative change cannot be expressed against a baseline of zero — there is no percentage
     * that describes 0% errors becoming 25% — so {@link #percentChange()} is empty for exactly the
     * case a healthy baseline produces. Treating that emptiness as "no change" is how the most
     * important regression a service can have, an error rate appearing where there was none,
     * becomes the one nothing reports. This states the movement instead.
     *
     * @return {@code true} when one side is zero and the other is not
     */
    public boolean crossesZero() {
        if (baseline == null || candidate == null) {
            return false;
        }
        return (baseline.signum() == 0) != (candidate.signum() == 0);
    }

    /**
     * Whether the measurement moved in the unwanted direction, regardless of how it is expressed.
     *
     * <p>Empty when nothing moved, or when neither a percentage nor a zero crossing applies.
     *
     * @param noiseThresholdPercent relative movement below which a change is treated as variance;
     *                              never applied to a zero crossing, which is not noise
     */
    public Optional<Boolean> isDegradation(BigDecimal noiseThresholdPercent) {
        if (crossesZero()) {
            // Away from zero is worse when lower is better, and better when higher is better.
            boolean movedAwayFromZero = baseline.signum() == 0;
            return Optional.of(movedAwayFromZero == lowerIsBetter);
        }
        return percentChange()
                .filter(change -> change.abs().compareTo(noiseThresholdPercent) >= 0)
                .map(change -> lowerIsBetter == (change.signum() > 0));
    }
}
