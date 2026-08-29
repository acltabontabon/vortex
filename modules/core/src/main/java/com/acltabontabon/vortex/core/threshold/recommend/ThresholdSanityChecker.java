package com.acltabontabon.vortex.core.threshold.recommend;

import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.threshold.Durations;
import com.acltabontabon.vortex.core.threshold.ErrorRateThreshold;
import com.acltabontabon.vortex.core.threshold.LatencyThreshold;
import com.acltabontabon.vortex.core.threshold.ThresholdSet;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic sanity checks for a proposed threshold — flags, never opinions about whether the
 * user's decision is a good one. Every rule here compares a proposed number against a number Vortex
 * already has (production, a baseline, or another threshold in the same set); nothing is judged in
 * the abstract.
 *
 * <p>{@link #checkConsistency(ThresholdSet)} needs no evidence and is the one rule that blocks a
 * save. {@link #checkLatencyThreshold} and {@link #checkErrorRateThreshold} are advisory —
 * {@link Severity#CAUTION} at most unless the ratio is extreme enough to read as a misconfiguration
 * rather than a deliberate choice.
 */
public final class ThresholdSanityChecker {

    static final BigDecimal STRICT_INVALID_RATIO = BigDecimal.valueOf(0.50);
    static final BigDecimal STRICT_CAUTION_RATIO = BigDecimal.valueOf(0.80);
    static final BigDecimal LOOSE_PRODUCTION_CAUTION_RATIO = BigDecimal.valueOf(5);
    static final BigDecimal LOOSE_BASELINE_CAUTION_RATIO = BigDecimal.valueOf(3);
    static final BigDecimal ERROR_RATE_CAUTION_MULTIPLE = BigDecimal.valueOf(10);
    static final BigDecimal ERROR_RATE_INVALID_MULTIPLE = BigDecimal.valueOf(50);

    /**
     * A stricter percentile can never legitimately carry a smaller maximum than a looser one at the
     * same scope — p99 latency is never smaller than p95 latency in a real distribution. The one rule
     * here that is a logical contradiction rather than a judgement call, so it is the one that blocks
     * a save.
     */
    public List<SanityFinding> checkConsistency(ThresholdSet proposed) {
        Objects.requireNonNull(proposed, "proposed");
        List<SanityFinding> findings = new ArrayList<>();
        List<LatencyThreshold> latencyThresholds = proposed.latencyThresholds();
        for (LatencyThreshold a : latencyThresholds) {
            for (LatencyThreshold b : latencyThresholds) {
                if (a == b || !a.scope().equals(b.scope())) {
                    continue;
                }
                if (a.percentile().compareTo(b.percentile()) > 0 && a.maximum().compareTo(b.maximum()) < 0) {
                    findings.add(new SanityFinding(Severity.INVALID, a.id(),
                            a.describe() + " contradicts " + b.describe() + " — " + a.percentile().label()
                                    + " latency can never be smaller than " + b.percentile().label()
                                    + " latency, so a smaller maximum for " + a.percentile().label()
                                    + " can never be satisfied."));
                }
            }
        }
        return List.copyOf(findings);
    }

    /**
     * Flags a latency threshold that sits far outside what production or a baseline actually shows —
     * unrealistically strict (likely to fail immediately) or so loose it could not detect a real
     * regression.
     */
    public List<SanityFinding> checkLatencyThreshold(LatencyThreshold threshold, Duration production, Duration baseline) {
        Objects.requireNonNull(threshold, "threshold");
        List<SanityFinding> findings = new ArrayList<>();
        if (production != null) {
            BigDecimal ratio = ratio(threshold.maximum(), production);
            if (ratio.compareTo(STRICT_INVALID_RATIO) < 0) {
                findings.add(new SanityFinding(Severity.INVALID, threshold.id(),
                        threshold.describe() + " is " + percent(ratio) + " of observed production ("
                                + Durations.display(production) + ") — significantly stricter than current "
                                + "production behavior. This objective is likely to fail immediately."));
            } else if (ratio.compareTo(STRICT_CAUTION_RATIO) < 0) {
                findings.add(new SanityFinding(Severity.CAUTION, threshold.id(),
                        threshold.describe() + " is " + percent(ratio) + " of observed production ("
                                + Durations.display(production) + ") — noticeably stricter than current "
                                + "production behavior."));
            } else if (ratio.compareTo(LOOSE_PRODUCTION_CAUTION_RATIO) > 0) {
                findings.add(new SanityFinding(Severity.CAUTION, threshold.id(),
                        threshold.describe() + " is " + multiple(ratio) + "× observed production ("
                                + Durations.display(production) + ") — substantially looser than current "
                                + "behavior and may not detect a meaningful regression."));
            }
        }
        if (baseline != null) {
            BigDecimal ratio = ratio(threshold.maximum(), baseline);
            if (ratio.compareTo(LOOSE_BASELINE_CAUTION_RATIO) > 0) {
                findings.add(new SanityFinding(Severity.CAUTION, threshold.id(),
                        threshold.describe() + " is " + multiple(ratio) + "× your best valid baseline ("
                                + Durations.display(baseline) + ") — substantially looser than tested "
                                + "capability and may not detect a meaningful regression."));
            }
        }
        return List.copyOf(findings);
    }

    /**
     * Flags an allowed error rate many multiples looser than what production actually experiences —
     * a threshold that would never meaningfully fail.
     */
    public List<SanityFinding> checkErrorRateThreshold(ErrorRateThreshold threshold, ErrorRate production) {
        Objects.requireNonNull(threshold, "threshold");
        if (production == null || production.fraction().signum() == 0) {
            return List.of();
        }
        BigDecimal multiple = threshold.maximum().fraction()
                .divide(production.fraction(), 4, RoundingMode.HALF_UP);
        if (multiple.compareTo(ERROR_RATE_INVALID_MULTIPLE) > 0) {
            return List.of(new SanityFinding(Severity.INVALID, threshold.id(),
                    "Allowed error rate " + threshold.maximum().display() + " would permit roughly "
                            + multiple(multiple) + "× the observed production failure rate ("
                            + production.display() + ") — this threshold would not detect meaningful "
                            + "degradation."));
        }
        if (multiple.compareTo(ERROR_RATE_CAUTION_MULTIPLE) > 0) {
            return List.of(new SanityFinding(Severity.CAUTION, threshold.id(),
                    "Allowed error rate " + threshold.maximum().display() + " would permit roughly "
                            + multiple(multiple) + "× the observed production failure rate ("
                            + production.display() + ")."));
        }
        return List.of();
    }

    private static BigDecimal ratio(Duration value, Duration reference) {
        return BigDecimal.valueOf(value.toNanos())
                .divide(BigDecimal.valueOf(reference.toNanos()), 4, RoundingMode.HALF_UP);
    }

    private static String percent(BigDecimal ratio) {
        return ratio.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String multiple(BigDecimal ratio) {
        return ratio.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
