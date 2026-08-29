package com.acltabontabon.vortex.core.threshold;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.Percentile;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObjectiveNarrativeTest {

    @Test
    void describesAnEmptySetHonestly() {
        String text = ObjectiveNarrative.describe(ThresholdSet.empty(), Map.of());

        assertThat(text).contains("No objectives are configured");
    }

    @Test
    void describesLatencyAndErrorRateTogetherWithNoComparison() {
        ThresholdSet set = ThresholdSet.of(
                LatencyThreshold.of(Percentile.P95, Duration.ofMillis(550)),
                ErrorRateThreshold.ofPercent(1));

        String text = ObjectiveNarrative.describe(set, Map.of());

        assertThat(text).contains("95% of requests").contains("550 ms").contains("fewer than 1% of requests may fail");
    }

    @Test
    void appendsAComparisonSentenceWhenAReferenceIsSupplied() {
        LatencyThreshold threshold = LatencyThreshold.of(Percentile.P95, Duration.ofMillis(550));
        ThresholdSet set = ThresholdSet.of(threshold);
        Map<String, ObjectiveNarrative.Reference> references = Map.of(
                threshold.id(), ObjectiveNarrative.Reference.latency(Duration.ofMillis(620), "current production behavior"));

        String text = ObjectiveNarrative.describe(set, references);

        assertThat(text).contains("stricter than current production behavior");
    }

    @Test
    void compareLatencyReportsPercentStricter() {
        String text = ObjectiveNarrative.compareLatency(Duration.ofMillis(300), Duration.ofMillis(625), "current production behavior");

        assertThat(text).isEqualTo("52% stricter than current production behavior.");
    }

    @Test
    void compareLatencyReportsPercentLooser() {
        String text = ObjectiveNarrative.compareLatency(Duration.ofMillis(700), Duration.ofMillis(620), "current production behavior");

        assertThat(text).contains("looser than current production behavior");
    }

    @Test
    void compareLatencyReportsRoughMatchWithinTolerance() {
        String text = ObjectiveNarrative.compareLatency(Duration.ofMillis(620), Duration.ofMillis(620), "current production behavior");

        assertThat(text).isEqualTo("This roughly matches current production behavior.");
    }

    @Test
    void compareErrorRateHandlesAZeroReferenceWithoutDividingByZero() {
        String text = ObjectiveNarrative.compareErrorRate(ErrorRate.ofPercent(1), ErrorRate.ZERO, "production");

        assertThat(text).contains("allows failures where production observed none");
    }

    @Test
    void breakpointConditionJoinsMultipleThresholdsWithOr() {
        ThresholdSet set = ThresholdSet.of(
                LatencyThreshold.of(Percentile.P95, Duration.ofSeconds(1)),
                ErrorRateThreshold.ofPercent(2));

        String text = ObjectiveNarrative.describeBreakpointCondition(set);

        assertThat(text).isEqualTo(
                "This test flags a breakpoint when p95 latency exceeds 1 s OR error rate exceeds 2%.");
    }

    @Test
    void breakpointConditionWithASingleThresholdHasNoDanglingOr() {
        ThresholdSet set = ThresholdSet.of(LatencyThreshold.of(Percentile.P95, Duration.ofMillis(500)));

        String text = ObjectiveNarrative.describeBreakpointCondition(set);

        assertThat(text).isEqualTo("This test flags a breakpoint when p95 latency exceeds 500 ms.");
        assertThat(text).doesNotContain("OR");
    }

    @Test
    void breakpointConditionOnAnEmptySetIsHonest() {
        String text = ObjectiveNarrative.describeBreakpointCondition(ThresholdSet.empty());

        assertThat(text).contains("No objectives are configured");
    }
}
