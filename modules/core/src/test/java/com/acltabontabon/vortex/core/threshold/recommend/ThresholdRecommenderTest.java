package com.acltabontabon.vortex.core.threshold.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.validity.RunQuality;
import com.acltabontabon.vortex.core.workload.Observation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ThresholdRecommenderTest {

    private final ThresholdRecommender recommender = ThresholdRecommender.defaults();
    private final Instant now = Instant.parse("2026-08-29T00:00:00Z");

    @Test
    void missingEvidenceNeverThrowsAndProducesNoRecommendations() {
        List<ThresholdRecommendation> latency = recommender.recommendLatency(ThresholdEvidence.empty(), now);
        List<ThresholdRecommendation> errorRate = recommender.recommendErrorRate(ThresholdEvidence.empty(), now);

        assertThat(latency).isEmpty();
        assertThat(errorRate).isEmpty();
    }

    @Test
    void productionOnlyEvidenceProducesBalancedAndProductionParityAndStricter() {
        ThresholdEvidence evidence = new ThresholdEvidence(
                ThresholdEvidence.ProductionEvidence.latency(
                        Duration.ofMillis(620), recentWindow(), "prometheus", true, true),
                List.of());

        List<ThresholdRecommendation> recommendations = recommender.recommendLatency(evidence, now);

        assertThat(recommendations).extracting(ThresholdRecommendation::label)
                .containsExactly("Balanced", "Production parity", "Stricter objective");
        assertThat(recommendations).allSatisfy(rec ->
                assertThat(rec.provenance().source()).isEqualTo(ThresholdSource.PRODUCTION_BASELINE));
        // Balanced falls back to Production Parity's own formula when no baseline exists.
        assertThat(recommendations.get(0).latencyValue()).isEqualTo(recommendations.get(1).latencyValue());
    }

    @Test
    void baselineEvidenceProducesBalancedFromBaselineProtection() {
        ThresholdEvidence evidence = new ThresholdEvidence(null, List.of(
                ThresholdEvidence.BaselineEvidence.latency("run-1", RunQuality.VALID, Duration.ofMillis(510), now)));

        List<ThresholdRecommendation> recommendations = recommender.recommendLatency(evidence, now);

        assertThat(recommendations).extracting(ThresholdRecommendation::label)
                .containsExactly("Balanced", "Stricter objective");
        assertThat(recommendations.get(0).provenance().source()).isEqualTo(ThresholdSource.VORTEX_BASELINE);
        assertThat(recommendations.get(0).provenance().baselineExecutionIdIfPresent()).contains("run-1");
        assertThat(recommendations.get(0).latencyValue()).isEqualTo(Duration.ofMillis(575));
    }

    @Test
    void bothProductionAndBaselinePreferBaselineForBalanced() {
        ThresholdEvidence evidence = new ThresholdEvidence(
                ThresholdEvidence.ProductionEvidence.latency(Duration.ofMillis(620), recentWindow(), "prometheus", true, true),
                List.of(ThresholdEvidence.BaselineEvidence.latency("run-1", RunQuality.VALID, Duration.ofMillis(510), now)));

        List<ThresholdRecommendation> recommendations = recommender.recommendLatency(evidence, now);

        assertThat(recommendations).extracting(ThresholdRecommendation::label)
                .containsExactly("Balanced", "Production parity", "Stricter objective");
        assertThat(recommendations.get(0).provenance().source()).isEqualTo(ThresholdSource.VORTEX_BASELINE);
    }

    @Test
    void invalidBaselineIsExcludedFromCandidacy() {
        ThresholdEvidence evidence = new ThresholdEvidence(null, List.of(
                ThresholdEvidence.BaselineEvidence.latency("run-invalid", RunQuality.INVALID, Duration.ofMillis(100), now)));

        List<ThresholdRecommendation> recommendations = recommender.recommendLatency(evidence, now);

        assertThat(recommendations).isEmpty();
    }

    @Test
    void degradedBaselineStillRecommendsButAtLimitedQuality() {
        ThresholdEvidence evidence = new ThresholdEvidence(null, List.of(
                ThresholdEvidence.BaselineEvidence.latency("run-degraded", RunQuality.DEGRADED, Duration.ofMillis(510), now)));

        List<ThresholdRecommendation> recommendations = recommender.recommendLatency(evidence, now);

        assertThat(recommendations).isNotEmpty();
        assertThat(recommendations.get(0).provenance().quality()).isEqualTo(EvidenceQuality.LIMITED);
    }

    @Test
    void validBaselineWithThreeOrMoreCompatibleRunsIsStrongEvidence() {
        List<ThresholdEvidence.BaselineEvidence> baselines = List.of(
                ThresholdEvidence.BaselineEvidence.latency("run-1", RunQuality.VALID, Duration.ofMillis(500), now.minusSeconds(3)),
                ThresholdEvidence.BaselineEvidence.latency("run-2", RunQuality.VALID, Duration.ofMillis(510), now.minusSeconds(2)),
                ThresholdEvidence.BaselineEvidence.latency("run-3", RunQuality.VALID, Duration.ofMillis(505), now.minusSeconds(1)));
        ThresholdEvidence evidence = new ThresholdEvidence(null, baselines);

        List<ThresholdRecommendation> recommendations = recommender.recommendLatency(evidence, now);

        assertThat(recommendations.get(0).provenance().quality()).isEqualTo(EvidenceQuality.STRONG);
    }

    @Test
    void staleProductionEvidenceIsLimitedQuality() {
        Observation staleWindow = Observation.over(
                now.minus(Duration.ofDays(90)), now.minus(Duration.ofDays(65)));
        ThresholdEvidence evidence = new ThresholdEvidence(
                ThresholdEvidence.ProductionEvidence.latency(Duration.ofMillis(620), staleWindow, "prometheus", true, true),
                List.of());

        List<ThresholdRecommendation> recommendations = recommender.recommendLatency(evidence, now);

        assertThat(recommendations).allSatisfy(rec ->
                assertThat(rec.provenance().quality()).isEqualTo(EvidenceQuality.LIMITED));
    }

    @Test
    void adjustableImprovementPercentageChangesTheStricterRecommendation() {
        ThresholdEvidence evidence = new ThresholdEvidence(null, List.of(
                ThresholdEvidence.BaselineEvidence.latency("run-1", RunQuality.VALID, Duration.ofMillis(1000), now)));

        List<ThresholdRecommendation> tenPercent = recommender.recommendLatency(evidence, now,
                ThresholdRecommendationPolicy.DEFAULT_IMPROVEMENT);
        List<ThresholdRecommendation> twentyPercent = recommender.recommendLatency(evidence, now,
                java.math.BigDecimal.valueOf(0.20));

        Duration tenPercentStricter = tenPercent.stream()
                .filter(r -> r.label().equals("Stricter objective")).findFirst().orElseThrow().latencyValue();
        Duration twentyPercentStricter = twentyPercent.stream()
                .filter(r -> r.label().equals("Stricter objective")).findFirst().orElseThrow().latencyValue();

        assertThat(twentyPercentStricter).isLessThan(tenPercentStricter);
    }

    @Test
    void errorRateRecommendationsFollowTheSameShapeAsLatency() {
        ThresholdEvidence evidence = new ThresholdEvidence(
                ThresholdEvidence.ProductionEvidence.errorRate(
                        ErrorRate.ofPercent(0.4), recentWindow(), "prometheus", true, true),
                List.of());

        List<ThresholdRecommendation> recommendations = recommender.recommendErrorRate(evidence, now);

        assertThat(recommendations).extracting(ThresholdRecommendation::label)
                .containsExactly("Balanced", "Production parity", "Stricter objective");
        assertThat(recommendations).allSatisfy(rec -> assertThat(rec.errorRateValue()).isNotNull());
    }

    @Test
    void latencyEvidenceNeverProducesAnErrorRateRecommendationAndViceVersa() {
        ThresholdEvidence evidence = new ThresholdEvidence(
                ThresholdEvidence.ProductionEvidence.latency(Duration.ofMillis(620), recentWindow(), "prometheus", true, true),
                List.of());

        assertThat(recommender.recommendErrorRate(evidence, now)).isEmpty();
    }

    private static Observation recentWindow() {
        Instant end = Instant.parse("2026-08-29T00:00:00Z");
        return Observation.over(end.minus(Duration.ofDays(30)), end);
    }
}
