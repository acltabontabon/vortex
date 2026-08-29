package com.acltabontabon.vortex.core.threshold.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.shared.ErrorRate;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ThresholdRecommendationPolicyTest {

    private final ThresholdRecommendationPolicy policy = new ThresholdRecommendationPolicy();

    @Test
    void productionParityLatencyAppliesTenPercentToleranceRoundedLoose() {
        // 620ms * 1.10 = 682ms, rounded up to nearest 25ms (sub-1s bucket) = 700ms
        Duration result = policy.productionParityLatency(Duration.ofMillis(620));

        assertThat(result).isEqualTo(Duration.ofMillis(700));
    }

    @Test
    void baselineProtectionLatencyAppliesTenPercentBufferRoundedLoose() {
        // 510ms * 1.10 = 561ms, rounded up to nearest 25ms = 575ms
        Duration result = policy.baselineProtectionLatency(Duration.ofMillis(510));

        assertThat(result).isEqualTo(Duration.ofMillis(575));
    }

    @Test
    void improvementLatencyDefaultTenPercentRoundedStrict() {
        // 510ms * 0.90 = 459ms, rounded down to nearest 25ms = 450ms
        Duration result = policy.improvementLatency(Duration.ofMillis(510), ThresholdRecommendationPolicy.DEFAULT_IMPROVEMENT);

        assertThat(result).isEqualTo(Duration.ofMillis(450));
    }

    @Test
    void improvementLatencyClampsBelowMinimumToFivePercent() {
        Duration lenient = policy.improvementLatency(Duration.ofMillis(1000), BigDecimal.valueOf(0.01));
        Duration atMinimum = policy.improvementLatency(Duration.ofMillis(1000), ThresholdRecommendationPolicy.MIN_IMPROVEMENT);

        assertThat(lenient).isEqualTo(atMinimum);
    }

    @Test
    void improvementLatencyClampsAboveMaximumToFiftyPercent() {
        Duration aggressive = policy.improvementLatency(Duration.ofMillis(1000), BigDecimal.valueOf(0.99));
        Duration atMaximum = policy.improvementLatency(Duration.ofMillis(1000), ThresholdRecommendationPolicy.MAX_IMPROVEMENT);

        assertThat(aggressive).isEqualTo(atMaximum);
    }

    @Test
    void improvementLatencyNullFractionDefaultsToTenPercent() {
        Duration withNull = policy.improvementLatency(Duration.ofMillis(510), null);
        Duration withDefault = policy.improvementLatency(Duration.ofMillis(510), ThresholdRecommendationPolicy.DEFAULT_IMPROVEMENT);

        assertThat(withNull).isEqualTo(withDefault);
    }

    @Test
    void latencyRoundingStepsChangeAboveOneSecond() {
        // 1450ms in the 1-10s bucket rounds to the nearest 100ms
        Duration looseRounded = policy.roundLatencyLoose(Duration.ofMillis(1450));
        assertThat(looseRounded).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    void latencyRoundingStepsChangeAboveTenSeconds() {
        // 12400ms in the above-10s bucket rounds to the nearest whole second
        Duration looseRounded = policy.roundLatencyLoose(Duration.ofMillis(12_400));
        assertThat(looseRounded).isEqualTo(Duration.ofMillis(13_000));

        Duration strictRounded = policy.roundLatencyStrict(Duration.ofMillis(12_400));
        assertThat(strictRounded).isEqualTo(Duration.ofMillis(12_000));
    }

    @Test
    void latencyRoundingNeverProducesAZeroStep() {
        Duration result = policy.roundLatencyStrict(Duration.ofMillis(5));

        assertThat(result).isGreaterThan(Duration.ZERO);
    }

    @Test
    void productionParityErrorRateAppliesOneAndAHalfMultiplierRoundedLoose() {
        // 0.4% * 1.5 = 0.6%, rounded up to nearest 0.1pp = 0.6%
        ErrorRate result = policy.productionParityErrorRate(ErrorRate.ofPercent(0.4));

        assertThat(result).isEqualTo(ErrorRate.ofPercent(0.6));
    }

    @Test
    void productionParityErrorRateNeverGoesBelowTheFloor() {
        // 0.02% * 1.5 = 0.03%, which is below the floor of 0.1%
        ErrorRate result = policy.productionParityErrorRate(ErrorRate.ofPercent(0.02));

        assertThat(result).isEqualTo(ThresholdRecommendationPolicy.MIN_ERROR_RATE_FLOOR);
    }

    @Test
    void baselineProtectionErrorRateAddsHalfAPercentagePointBuffer() {
        // 0.8% + 0.5pp = 1.3%, rounded up to nearest 0.1pp = 1.3%
        ErrorRate result = policy.baselineProtectionErrorRate(ErrorRate.ofPercent(0.8));

        assertThat(result).isEqualTo(ErrorRate.ofPercent(1.3));
    }

    @Test
    void errorRateRoundingStepWidensAboveFivePercent() {
        // 6.2% rounds to the nearest 0.5pp above the 5% threshold
        ErrorRate result = policy.roundErrorRateLoose(ErrorRate.ofPercent(6.2));

        assertThat(result).isEqualTo(ErrorRate.ofPercent(6.5));
    }

    @Test
    void improvementErrorRateDefaultTenPercentRoundedStrict() {
        // 1.0% * 0.90 = 0.9%, rounded down to nearest 0.1pp = 0.9%
        ErrorRate result = policy.improvementErrorRate(ErrorRate.ofPercent(1.0), ThresholdRecommendationPolicy.DEFAULT_IMPROVEMENT);

        assertThat(result).isEqualTo(ErrorRate.ofPercent(0.9));
    }

    @Test
    void improvementErrorRateNeverGoesBelowTheFloor() {
        ErrorRate result = policy.improvementErrorRate(ErrorRate.ofPercent(0.1), ThresholdRecommendationPolicy.DEFAULT_IMPROVEMENT);

        assertThat(result).isEqualTo(ThresholdRecommendationPolicy.MIN_ERROR_RATE_FLOOR);
    }
}
