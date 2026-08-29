package com.acltabontabon.vortex.core.threshold.recommend;

import com.acltabontabon.vortex.core.shared.ErrorRate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Objects;

/**
 * Turns threshold evidence into candidate numbers, by explicit, stated arithmetic — never by asking
 * a language model. Centralized here, and only here, so the same formula produces the same threshold
 * everywhere it's used, and so a test can assert against a named constant instead of a literal buried
 * in a controller or a component.
 *
 * <h2>Rounding</h2>
 * Every candidate is rounded to a human-friendly step before it is shown: a recommendation of
 * "487.31 ms" invites false precision about a figure that is already an estimate. Latency rounds to
 * the nearest 25 ms below one second, 100 ms from one to ten seconds, and 1 s above ten seconds. Error
 * rate rounds to the nearest 0.1 percentage point below 5%, 0.5 points above. A candidate meant to stay
 * safely inside its source evidence (Production Parity, Baseline Protection) always rounds toward the
 * looser value; a candidate meant to be strictly stricter (Improvement) always rounds toward the
 * stricter value — rounding the wrong way would silently weaken or defeat the strategy it belongs to.
 */
public final class ThresholdRecommendationPolicy {

    /** Production Parity: how much slack above the observed figure the objective allows. */
    public static final BigDecimal PARITY_TOLERANCE = BigDecimal.valueOf(1.10);

    /** Baseline Protection: how much slack above the best known-good baseline the objective allows. */
    public static final BigDecimal BASELINE_BUFFER = BigDecimal.valueOf(1.10);

    /** Improvement: the default fraction better than baseline, absent a user-chosen value. */
    public static final BigDecimal DEFAULT_IMPROVEMENT = BigDecimal.valueOf(0.10);

    public static final BigDecimal MIN_IMPROVEMENT = BigDecimal.valueOf(0.05);
    public static final BigDecimal MAX_IMPROVEMENT = BigDecimal.valueOf(0.50);

    /** Production Parity error rate: observed rates near zero need a multiplicative allowance. */
    public static final BigDecimal ERROR_RATE_PARITY_MULTIPLIER = BigDecimal.valueOf(1.5);

    /** Baseline Protection error rate: a flat buffer in percentage points, not a multiplier. */
    public static final BigDecimal ERROR_RATE_BASELINE_BUFFER_POINTS = BigDecimal.valueOf(0.5);

    /** No error-rate objective this policy proposes is ever tighter than this. */
    public static final ErrorRate MIN_ERROR_RATE_FLOOR = ErrorRate.ofPercent(0.1);

    private static final long LATENCY_STEP_SUB_SECOND_MILLIS = 25;
    private static final long LATENCY_STEP_SUB_TEN_SECOND_MILLIS = 100;
    private static final long LATENCY_STEP_ABOVE_TEN_SECOND_MILLIS = 1000;

    private static final BigDecimal ERROR_RATE_STEP_BELOW_5_PERCENT = BigDecimal.valueOf(0.1);
    private static final BigDecimal ERROR_RATE_STEP_ABOVE_5_PERCENT = BigDecimal.valueOf(0.5);
    private static final BigDecimal ERROR_RATE_STEP_THRESHOLD_PERCENT = BigDecimal.valueOf(5);

    // ---------------------------------------------------------------------------------- latency

    /** {@code ceil_readable(observed × PARITY_TOLERANCE)} — the loose direction, safely above production. */
    public Duration productionParityLatency(Duration observedP95) {
        Objects.requireNonNull(observedP95, "observedP95");
        return roundLatencyLoose(scale(observedP95, PARITY_TOLERANCE));
    }

    /** {@code ceil_readable(bestValidBaseline × BASELINE_BUFFER)} — the loose direction. */
    public Duration baselineProtectionLatency(Duration bestValidBaseline) {
        Objects.requireNonNull(bestValidBaseline, "bestValidBaseline");
        return roundLatencyLoose(scale(bestValidBaseline, BASELINE_BUFFER));
    }

    /**
     * {@code floor_readable(baseline × (1 − improvement))} — the strict direction, so rounding never
     * quietly gives back part of the improvement being asked for.
     */
    public Duration improvementLatency(Duration baseline, BigDecimal improvement) {
        Objects.requireNonNull(baseline, "baseline");
        BigDecimal fraction = clampImprovement(improvement);
        BigDecimal factor = BigDecimal.ONE.subtract(fraction);
        return roundLatencyStrict(scale(baseline, factor));
    }

    /** Rounds toward the larger (safer, looser) duration — {@code ceil_readable}. */
    public Duration roundLatencyLoose(Duration value) {
        return roundLatency(value, RoundingMode.CEILING);
    }

    /** Rounds toward the smaller (stricter) duration — {@code floor_readable}. */
    public Duration roundLatencyStrict(Duration value) {
        return roundLatency(value, RoundingMode.FLOOR);
    }

    private Duration roundLatency(Duration value, RoundingMode mode) {
        Objects.requireNonNull(value, "value");
        long millis = value.toMillis();
        long step = millis < 1000 ? LATENCY_STEP_SUB_SECOND_MILLIS
                : millis < 10_000 ? LATENCY_STEP_SUB_TEN_SECOND_MILLIS
                : LATENCY_STEP_ABOVE_TEN_SECOND_MILLIS;
        BigDecimal stepDecimal = BigDecimal.valueOf(step);
        BigDecimal rounded = BigDecimal.valueOf(millis).divide(stepDecimal, 0, mode).multiply(stepDecimal);
        long roundedMillis = Math.max(step, rounded.longValueExact());
        return Duration.ofMillis(roundedMillis);
    }

    private static Duration scale(Duration value, BigDecimal factor) {
        return Duration.ofNanos(BigDecimal.valueOf(value.toNanos()).multiply(factor)
                .setScale(0, RoundingMode.HALF_UP).longValueExact());
    }

    // ------------------------------------------------------------------------------- error rate

    /** {@code max(observed × ERROR_RATE_PARITY_MULTIPLIER, MIN_ERROR_RATE_FLOOR)}, loosely rounded. */
    public ErrorRate productionParityErrorRate(ErrorRate observed) {
        Objects.requireNonNull(observed, "observed");
        BigDecimal scaled = observed.fraction().multiply(ERROR_RATE_PARITY_MULTIPLIER);
        ErrorRate candidate = clampFraction(scaled);
        return roundErrorRateLoose(max(candidate, MIN_ERROR_RATE_FLOOR));
    }

    /** {@code bestValidBaseline + ERROR_RATE_BASELINE_BUFFER_POINTS}, floored, loosely rounded. */
    public ErrorRate baselineProtectionErrorRate(ErrorRate bestValidBaseline) {
        Objects.requireNonNull(bestValidBaseline, "bestValidBaseline");
        BigDecimal points = BigDecimal.valueOf(bestValidBaseline.asPercent());
        BigDecimal withBuffer = points.add(ERROR_RATE_BASELINE_BUFFER_POINTS);
        ErrorRate candidate = ErrorRate.ofPercent(withBuffer.doubleValue());
        return roundErrorRateLoose(max(candidate, MIN_ERROR_RATE_FLOOR));
    }

    /** {@code baseline × (1 − improvement)}, floored, strictly rounded. */
    public ErrorRate improvementErrorRate(ErrorRate baseline, BigDecimal improvement) {
        Objects.requireNonNull(baseline, "baseline");
        BigDecimal fraction = clampImprovement(improvement);
        BigDecimal factor = BigDecimal.ONE.subtract(fraction);
        ErrorRate candidate = clampFraction(baseline.fraction().multiply(factor));
        return roundErrorRateStrict(max(candidate, MIN_ERROR_RATE_FLOOR));
    }

    /** Rounds toward the larger (looser) error rate — {@code ceil_readable}. */
    public ErrorRate roundErrorRateLoose(ErrorRate value) {
        return roundErrorRate(value, RoundingMode.CEILING);
    }

    /** Rounds toward the smaller (stricter) error rate — {@code floor_readable}. */
    public ErrorRate roundErrorRateStrict(ErrorRate value) {
        return roundErrorRate(value, RoundingMode.FLOOR);
    }

    private ErrorRate roundErrorRate(ErrorRate value, RoundingMode mode) {
        Objects.requireNonNull(value, "value");
        BigDecimal percent = BigDecimal.valueOf(value.asPercent());
        BigDecimal step = percent.compareTo(ERROR_RATE_STEP_THRESHOLD_PERCENT) < 0
                ? ERROR_RATE_STEP_BELOW_5_PERCENT : ERROR_RATE_STEP_ABOVE_5_PERCENT;
        BigDecimal rounded = percent.divide(step, 0, mode).multiply(step);
        if (rounded.signum() <= 0) {
            rounded = step;
        }
        if (rounded.compareTo(BigDecimal.valueOf(100)) > 0) {
            rounded = BigDecimal.valueOf(100);
        }
        return ErrorRate.ofPercent(rounded.doubleValue());
    }

    private static ErrorRate max(ErrorRate a, ErrorRate b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static ErrorRate clampFraction(BigDecimal fraction) {
        if (fraction.compareTo(BigDecimal.ONE) > 0) {
            fraction = BigDecimal.ONE;
        }
        if (fraction.signum() < 0) {
            fraction = BigDecimal.ZERO;
        }
        return new ErrorRate(fraction);
    }

    private static BigDecimal clampImprovement(BigDecimal improvement) {
        if (improvement == null) {
            return DEFAULT_IMPROVEMENT;
        }
        if (improvement.compareTo(MIN_IMPROVEMENT) < 0) {
            return MIN_IMPROVEMENT;
        }
        if (improvement.compareTo(MAX_IMPROVEMENT) > 0) {
            return MAX_IMPROVEMENT;
        }
        return improvement;
    }
}
