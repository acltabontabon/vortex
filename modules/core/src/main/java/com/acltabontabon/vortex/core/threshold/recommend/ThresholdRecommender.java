package com.acltabontabon.vortex.core.threshold.recommend;

import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.threshold.Durations;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns {@link ThresholdEvidence} into a short, deterministic list of candidate threshold values —
 * "Balanced", "Production parity", "Stricter objective" — never by asking a language model what a
 * threshold should be. Missing evidence is a normal outcome, not a failure: a metric with no
 * production observation and no eligible baseline simply produces an empty list, and the caller falls
 * back to a manual objective.
 *
 * <p>"Balanced" is Baseline Protection when an eligible baseline exists, otherwise Production Parity
 * when production evidence exists, otherwise omitted. The two are computed by the same named formulas
 * whether shown as "Balanced" or under their own card, so a value never disagrees with itself across
 * the two labels it might appear under — a case that legitimately produces two identical-looking
 * cards when a workload has production evidence but no baseline yet, which is left to the caller to
 * merge for display if it chooses to.
 */
public final class ThresholdRecommender {

    private final ThresholdRecommendationPolicy policy;

    public ThresholdRecommender(ThresholdRecommendationPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public static ThresholdRecommender defaults() {
        return new ThresholdRecommender(new ThresholdRecommendationPolicy());
    }

    public List<ThresholdRecommendation> recommendLatency(ThresholdEvidence evidence, Instant now) {
        return recommendLatency(evidence, now, ThresholdRecommendationPolicy.DEFAULT_IMPROVEMENT);
    }

    public List<ThresholdRecommendation> recommendLatency(
            ThresholdEvidence evidence, Instant now, BigDecimal improvementFraction) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(now, "now");
        Optional<ThresholdEvidence.ProductionEvidence> production = evidence.productionIfPresent()
                .filter(p -> p.latency() != null);
        Optional<ThresholdEvidence.BaselineEvidence> baseline = evidence.bestBaseline()
                .filter(b -> b.latency() != null);

        List<ThresholdRecommendation> recommendations = new ArrayList<>();

        if (baseline.isPresent()) {
            recommendations.add(balancedFromBaselineLatency(evidence, baseline.get(), now));
        } else if (production.isPresent()) {
            recommendations.add(balancedFromProductionLatency(production.get(), now));
        }

        production.ifPresent(p -> recommendations.add(productionParityLatency(p, now)));

        (baseline.isPresent() ? baseline.map(b -> improvementFromBaselineLatency(evidence, b, now, improvementFraction))
                : production.map(p -> improvementFromProductionLatency(p, now, improvementFraction)))
                .ifPresent(recommendations::add);

        return List.copyOf(recommendations);
    }

    public List<ThresholdRecommendation> recommendErrorRate(ThresholdEvidence evidence, Instant now) {
        return recommendErrorRate(evidence, now, ThresholdRecommendationPolicy.DEFAULT_IMPROVEMENT);
    }

    public List<ThresholdRecommendation> recommendErrorRate(
            ThresholdEvidence evidence, Instant now, BigDecimal improvementFraction) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(now, "now");
        Optional<ThresholdEvidence.ProductionEvidence> production = evidence.productionIfPresent()
                .filter(p -> p.errorRate() != null);
        Optional<ThresholdEvidence.BaselineEvidence> baseline = evidence.bestBaseline()
                .filter(b -> b.errorRate() != null);

        List<ThresholdRecommendation> recommendations = new ArrayList<>();

        if (baseline.isPresent()) {
            recommendations.add(balancedFromBaselineErrorRate(evidence, baseline.get(), now));
        } else if (production.isPresent()) {
            recommendations.add(balancedFromProductionErrorRate(production.get(), now));
        }

        production.ifPresent(p -> recommendations.add(productionParityErrorRate(p, now)));

        (baseline.isPresent() ? baseline.map(b -> improvementFromBaselineErrorRate(evidence, b, now, improvementFraction))
                : production.map(p -> improvementFromProductionErrorRate(p, now, improvementFraction)))
                .ifPresent(recommendations::add);

        return List.copyOf(recommendations);
    }

    // ------------------------------------------------------------------------------------ latency

    private ThresholdRecommendation balancedFromBaselineLatency(
            ThresholdEvidence evidence, ThresholdEvidence.BaselineEvidence baseline, Instant now) {
        Duration value = policy.baselineProtectionLatency(baseline.latency());
        String derivation = "Your best valid baseline (run " + baseline.executionId() + ") of "
                + Durations.display(baseline.latency()) + " × " + ThresholdRecommendationPolicy.BASELINE_BUFFER
                + " = " + Durations.display(scaleLatency(baseline.latency(), ThresholdRecommendationPolicy.BASELINE_BUFFER))
                + ", rounded to " + Durations.display(value) + ".";
        EvidenceQuality quality = EvidenceQuality.ofBaseline(
                baseline.quality(), evidence.compatibleValidBaselineCount(), baseline.isStale(now));
        ThresholdProvenance provenance = ThresholdProvenance.derived(ThresholdSource.VORTEX_BASELINE,
                "run " + baseline.executionId(), pointObservation(baseline.executedAt()), derivation, quality,
                baseline.executionId(), now);
        return ThresholdRecommendation.ofLatency("Balanced", value, provenance);
    }

    private ThresholdRecommendation balancedFromProductionLatency(
            ThresholdEvidence.ProductionEvidence production, Instant now) {
        return productionParityLatency(production, now, "Balanced");
    }

    private ThresholdRecommendation productionParityLatency(ThresholdEvidence.ProductionEvidence production, Instant now) {
        return productionParityLatency(production, now, "Production parity");
    }

    private ThresholdRecommendation productionParityLatency(
            ThresholdEvidence.ProductionEvidence production, Instant now, String label) {
        Duration value = policy.productionParityLatency(production.latency());
        String derivation = "Your observed production " + Durations.display(production.latency()) + " × "
                + ThresholdRecommendationPolicy.PARITY_TOLERANCE + " = "
                + Durations.display(scaleLatency(production.latency(), ThresholdRecommendationPolicy.PARITY_TOLERANCE))
                + ", rounded to " + Durations.display(value) + ".";
        EvidenceQuality quality = EvidenceQuality.ofProduction(
                production.fetched(), production.mixCoverageComplete(), production.isStale(now));
        ThresholdProvenance provenance = ThresholdProvenance.derived(ThresholdSource.PRODUCTION_BASELINE,
                production.source(), production.observedAt(), derivation, quality, "", now);
        return ThresholdRecommendation.ofLatency(label, value, provenance);
    }

    private ThresholdRecommendation improvementFromBaselineLatency(ThresholdEvidence evidence,
            ThresholdEvidence.BaselineEvidence baseline, Instant now, BigDecimal improvementFraction) {
        BigDecimal fraction = improvementFraction == null
                ? ThresholdRecommendationPolicy.DEFAULT_IMPROVEMENT : improvementFraction;
        Duration value = policy.improvementLatency(baseline.latency(), fraction);
        String derivation = percentLabel(fraction) + " improvement on your best valid baseline (run "
                + baseline.executionId() + ") of " + Durations.display(baseline.latency()) + ": rounded to "
                + Durations.display(value) + ".";
        EvidenceQuality quality = EvidenceQuality.ofBaseline(
                baseline.quality(), evidence.compatibleValidBaselineCount(), baseline.isStale(now));
        ThresholdProvenance provenance = ThresholdProvenance.derived(ThresholdSource.VORTEX_BASELINE,
                "run " + baseline.executionId(), pointObservation(baseline.executedAt()), derivation, quality,
                baseline.executionId(), now);
        return ThresholdRecommendation.ofLatency("Stricter objective", value, provenance);
    }

    private ThresholdRecommendation improvementFromProductionLatency(
            ThresholdEvidence.ProductionEvidence production, Instant now, BigDecimal improvementFraction) {
        BigDecimal fraction = improvementFraction == null
                ? ThresholdRecommendationPolicy.DEFAULT_IMPROVEMENT : improvementFraction;
        Duration value = policy.improvementLatency(production.latency(), fraction);
        String derivation = percentLabel(fraction) + " improvement on your observed production "
                + Durations.display(production.latency()) + ": rounded to " + Durations.display(value) + ".";
        EvidenceQuality quality = EvidenceQuality.ofProduction(
                production.fetched(), production.mixCoverageComplete(), production.isStale(now));
        ThresholdProvenance provenance = ThresholdProvenance.derived(ThresholdSource.PRODUCTION_BASELINE,
                production.source(), production.observedAt(), derivation, quality, "", now);
        return ThresholdRecommendation.ofLatency("Stricter objective", value, provenance);
    }

    private static Duration scaleLatency(Duration value, BigDecimal factor) {
        return Duration.ofNanos((long) (value.toNanos() * factor.doubleValue()));
    }

    // ---------------------------------------------------------------------------------- error rate

    private ThresholdRecommendation balancedFromBaselineErrorRate(
            ThresholdEvidence evidence, ThresholdEvidence.BaselineEvidence baseline, Instant now) {
        ErrorRate value = policy.baselineProtectionErrorRate(baseline.errorRate());
        String derivation = "Your best valid baseline (run " + baseline.executionId() + ") of "
                + baseline.errorRate().display() + " + " + ThresholdRecommendationPolicy.ERROR_RATE_BASELINE_BUFFER_POINTS
                + " points, rounded to " + value.display() + ".";
        EvidenceQuality quality = EvidenceQuality.ofBaseline(
                baseline.quality(), evidence.compatibleValidBaselineCount(), baseline.isStale(now));
        ThresholdProvenance provenance = ThresholdProvenance.derived(ThresholdSource.VORTEX_BASELINE,
                "run " + baseline.executionId(), pointObservation(baseline.executedAt()), derivation, quality,
                baseline.executionId(), now);
        return ThresholdRecommendation.ofErrorRate("Balanced", value, provenance);
    }

    private ThresholdRecommendation balancedFromProductionErrorRate(
            ThresholdEvidence.ProductionEvidence production, Instant now) {
        return productionParityErrorRate(production, now, "Balanced");
    }

    private ThresholdRecommendation productionParityErrorRate(
            ThresholdEvidence.ProductionEvidence production, Instant now) {
        return productionParityErrorRate(production, now, "Production parity");
    }

    private ThresholdRecommendation productionParityErrorRate(
            ThresholdEvidence.ProductionEvidence production, Instant now, String label) {
        ErrorRate value = policy.productionParityErrorRate(production.errorRate());
        String derivation = "Your observed production " + production.errorRate().display() + " × "
                + ThresholdRecommendationPolicy.ERROR_RATE_PARITY_MULTIPLIER + ", rounded to " + value.display() + ".";
        EvidenceQuality quality = EvidenceQuality.ofProduction(
                production.fetched(), production.mixCoverageComplete(), production.isStale(now));
        ThresholdProvenance provenance = ThresholdProvenance.derived(ThresholdSource.PRODUCTION_BASELINE,
                production.source(), production.observedAt(), derivation, quality, "", now);
        return ThresholdRecommendation.ofErrorRate(label, value, provenance);
    }

    private ThresholdRecommendation improvementFromBaselineErrorRate(ThresholdEvidence evidence,
            ThresholdEvidence.BaselineEvidence baseline, Instant now, BigDecimal improvementFraction) {
        BigDecimal fraction = improvementFraction == null
                ? ThresholdRecommendationPolicy.DEFAULT_IMPROVEMENT : improvementFraction;
        ErrorRate value = policy.improvementErrorRate(baseline.errorRate(), fraction);
        String derivation = percentLabel(fraction) + " improvement on your best valid baseline (run "
                + baseline.executionId() + ") of " + baseline.errorRate().display() + ": rounded to "
                + value.display() + ".";
        EvidenceQuality quality = EvidenceQuality.ofBaseline(
                baseline.quality(), evidence.compatibleValidBaselineCount(), baseline.isStale(now));
        ThresholdProvenance provenance = ThresholdProvenance.derived(ThresholdSource.VORTEX_BASELINE,
                "run " + baseline.executionId(), pointObservation(baseline.executedAt()), derivation, quality,
                baseline.executionId(), now);
        return ThresholdRecommendation.ofErrorRate("Stricter objective", value, provenance);
    }

    private ThresholdRecommendation improvementFromProductionErrorRate(
            ThresholdEvidence.ProductionEvidence production, Instant now, BigDecimal improvementFraction) {
        BigDecimal fraction = improvementFraction == null
                ? ThresholdRecommendationPolicy.DEFAULT_IMPROVEMENT : improvementFraction;
        ErrorRate value = policy.improvementErrorRate(production.errorRate(), fraction);
        String derivation = percentLabel(fraction) + " improvement on your observed production "
                + production.errorRate().display() + ": rounded to " + value.display() + ".";
        EvidenceQuality quality = EvidenceQuality.ofProduction(
                production.fetched(), production.mixCoverageComplete(), production.isStale(now));
        ThresholdProvenance provenance = ThresholdProvenance.derived(ThresholdSource.PRODUCTION_BASELINE,
                production.source(), production.observedAt(), derivation, quality, "", now);
        return ThresholdRecommendation.ofErrorRate("Stricter objective", value, provenance);
    }

    // ------------------------------------------------------------------------------------- shared

    private static String percentLabel(BigDecimal fraction) {
        return fraction.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }

    private static com.acltabontabon.vortex.core.workload.Observation pointObservation(Instant executedAt) {
        return com.acltabontabon.vortex.core.workload.Observation.at(executedAt);
    }
}
