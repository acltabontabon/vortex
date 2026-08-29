package com.acltabontabon.vortex.core.threshold.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.Percentile;
import com.acltabontabon.vortex.core.threshold.ErrorRateThreshold;
import com.acltabontabon.vortex.core.threshold.LatencyThreshold;
import com.acltabontabon.vortex.core.threshold.ThresholdSet;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ThresholdSanityCheckerTest {

    private final ThresholdSanityChecker checker = new ThresholdSanityChecker();

    @Test
    void contradictoryPercentilesAreInvalid() {
        ThresholdSet set = ThresholdSet.of(
                LatencyThreshold.of(Percentile.P95, Duration.ofMillis(500)),
                LatencyThreshold.of(Percentile.P99, Duration.ofMillis(400)));

        var findings = checker.checkConsistency(set);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(Severity.INVALID);
            assertThat(finding.thresholdId()).isEqualTo("latency.p99");
        });
    }

    @Test
    void consistentPercentilesProduceNoFindings() {
        ThresholdSet set = ThresholdSet.of(
                LatencyThreshold.of(Percentile.P95, Duration.ofMillis(500)),
                LatencyThreshold.of(Percentile.P99, Duration.ofMillis(1000)));

        assertThat(checker.checkConsistency(set)).isEmpty();
    }

    @Test
    void equalPercentileMaximaAreNotContradictory() {
        ThresholdSet set = ThresholdSet.of(
                LatencyThreshold.of(Percentile.P95, Duration.ofMillis(500)),
                LatencyThreshold.of(Percentile.P99, Duration.ofMillis(500)));

        assertThat(checker.checkConsistency(set)).isEmpty();
    }

    @Test
    void percentilesAtDifferentScopesAreNeverCompared() {
        var operationId = new com.acltabontabon.vortex.core.shared.OperationId("createOrder");
        ThresholdSet set = ThresholdSet.of(
                LatencyThreshold.of(Percentile.P95, Duration.ofMillis(500)),
                LatencyThreshold.of(operationId, Percentile.P99, Duration.ofMillis(100)));

        assertThat(checker.checkConsistency(set)).isEmpty();
    }

    @Test
    void unrealisticallyStrictLatencyIsInvalid() {
        LatencyThreshold threshold = LatencyThreshold.of(Percentile.P95, Duration.ofMillis(100));

        var findings = checker.checkLatencyThreshold(threshold, Duration.ofMillis(850), null);

        assertThat(findings).singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(Severity.INVALID));
    }

    @Test
    void noticeablyStrictLatencyIsCaution() {
        // 560ms is 70% of an 800ms production p95 -- inside the 50-80% caution band
        LatencyThreshold threshold = LatencyThreshold.of(Percentile.P95, Duration.ofMillis(560));

        var findings = checker.checkLatencyThreshold(threshold, Duration.ofMillis(800), null);

        assertThat(findings).singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(Severity.CAUTION));
    }

    @Test
    void veryLooseLatencyVsProductionIsCaution() {
        LatencyThreshold threshold = LatencyThreshold.of(Percentile.P95, Duration.ofSeconds(5));

        var findings = checker.checkLatencyThreshold(threshold, Duration.ofMillis(620), null);

        assertThat(findings).singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(Severity.CAUTION));
    }

    @Test
    void looseLatencyVsBaselineAloneIsCaution() {
        LatencyThreshold threshold = LatencyThreshold.of(Percentile.P95, Duration.ofMillis(2000));

        var findings = checker.checkLatencyThreshold(threshold, null, Duration.ofMillis(500));

        assertThat(findings).singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(Severity.CAUTION));
    }

    @Test
    void proportionateThresholdProducesNoFindings() {
        LatencyThreshold threshold = LatencyThreshold.of(Percentile.P95, Duration.ofMillis(650));

        var findings = checker.checkLatencyThreshold(threshold, Duration.ofMillis(620), Duration.ofMillis(600));

        assertThat(findings).isEmpty();
    }

    @Test
    void missingEvidenceProducesNoFindings() {
        LatencyThreshold threshold = LatencyThreshold.of(Percentile.P95, Duration.ofMillis(500));

        assertThat(checker.checkLatencyThreshold(threshold, null, null)).isEmpty();
    }

    @Test
    void errorRateManyMultiplesLooserThanObservedIsInvalid() {
        // 5% allowed vs 0.08% observed = 62.5x
        ErrorRateThreshold threshold = ErrorRateThreshold.ofPercent(5);

        var findings = checker.checkErrorRateThreshold(threshold, ErrorRate.ofPercent(0.08));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo(Severity.INVALID);
            assertThat(finding.message()).contains("62");
        });
    }

    @Test
    void errorRateModeratelyLooserThanObservedIsCaution() {
        // 1% allowed vs 0.08% observed = 12.5x
        ErrorRateThreshold threshold = ErrorRateThreshold.ofPercent(1);

        var findings = checker.checkErrorRateThreshold(threshold, ErrorRate.ofPercent(0.08));

        assertThat(findings).singleElement()
                .satisfies(finding -> assertThat(finding.severity()).isEqualTo(Severity.CAUTION));
    }

    @Test
    void reasonableErrorRateProducesNoFindings() {
        ErrorRateThreshold threshold = ErrorRateThreshold.ofPercent(1);

        assertThat(checker.checkErrorRateThreshold(threshold, ErrorRate.ofPercent(0.5))).isEmpty();
    }

    @Test
    void zeroObservedErrorRateNeverProducesAnUndefinedMultiple() {
        ErrorRateThreshold threshold = ErrorRateThreshold.ofPercent(1);

        assertThat(checker.checkErrorRateThreshold(threshold, ErrorRate.ZERO)).isEmpty();
    }
}
