package com.acltabontabon.vortex.core.threshold.recommend;

import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.validity.RunQuality;
import com.acltabontabon.vortex.core.workload.Observation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything Vortex currently knows that could inform one threshold objective, already assembled for
 * a single metric — a latency percentile or the error rate — so {@link ThresholdRecommender} and
 * {@link ThresholdRecommendationPolicy} never have to reach past this to a live port, a repository or
 * a clock. Assembly (fetching production evidence, walking compatible prior executions) is the
 * application layer's job; this is deliberately just data.
 *
 * <p>Both {@link #production()} and {@link #baselines()} are legitimately empty — missing evidence
 * must never block configuring or running a test, so every consumer of this type has to handle the
 * empty case as a normal, unremarkable outcome rather than an error.
 *
 * @param production the current production evidence for this metric, when a source is configured and
 *                    reports it
 * @param baselines   candidate prior Vortex executions for this metric, in no particular order —
 *                    {@link ThresholdRecommender} selects among them
 */
public record ThresholdEvidence(ProductionEvidence production, List<BaselineEvidence> baselines) {

    public ThresholdEvidence {
        baselines = baselines == null ? List.of() : List.copyOf(baselines);
    }

    public static ThresholdEvidence empty() {
        return new ThresholdEvidence(null, List.of());
    }

    public Optional<ProductionEvidence> productionIfPresent() {
        return Optional.ofNullable(production);
    }

    /** Baselines from a run whose quality permits it to support a recommendation at all. */
    public List<BaselineEvidence> eligibleBaselines() {
        return baselines.stream().filter(b -> b.quality() != RunQuality.INVALID).toList();
    }

    /**
     * The single best eligible baseline, when one exists: the most recent {@code VALID} run, or —
     * failing that — the most recent run of any non-{@code INVALID} quality. Invalid runs are excluded
     * from candidacy entirely, reusing {@code RunQuality} rather than inventing separate trust logic.
     */
    public Optional<BaselineEvidence> bestBaseline() {
        List<BaselineEvidence> eligible = eligibleBaselines();
        return eligible.stream()
                .filter(b -> b.quality() == RunQuality.VALID)
                .max((a, b) -> a.executedAt().compareTo(b.executedAt()))
                .or(() -> eligible.stream().max((a, b) -> a.executedAt().compareTo(b.executedAt())));
    }

    /** How many eligible baselines share the best one's quality tier — feeds {@link EvidenceQuality}. */
    public int compatibleValidBaselineCount() {
        return (int) eligibleBaselines().stream().filter(b -> b.quality() == RunQuality.VALID).count();
    }

    /**
     * Production evidence for one metric — a latency percentile or the error rate, never both at
     * once, since a recommendation is always computed for a single metric at a time.
     *
     * @param latency              observed latency for this metric, when the metric is latency
     * @param errorRate            observed error rate, when the metric is error rate
     * @param observedAt           when this was observed, and over what period
     * @param source               where the observation came from
     * @param fetched              whether a monitoring system produced this, rather than a person
     * @param mixCoverageComplete  whether the observed mix accounts for all of production traffic
     */
    public record ProductionEvidence(
            Duration latency, ErrorRate errorRate, Observation observedAt, String source,
            boolean fetched, boolean mixCoverageComplete) {

        public ProductionEvidence {
            observedAt = observedAt == null ? Observation.unknown() : observedAt;
            source = source == null ? "" : source.trim();
            if (latency == null && errorRate == null) {
                throw new IllegalArgumentException(
                        "production evidence must report at least one of latency or error rate");
            }
            if (latency != null && errorRate != null) {
                throw new IllegalArgumentException(
                        "production evidence is assembled per metric — latency and error rate never "
                                + "both at once");
            }
        }

        public static ProductionEvidence latency(Duration value, Observation observedAt, String source,
                boolean fetched, boolean mixCoverageComplete) {
            return new ProductionEvidence(value, null, observedAt, source, fetched, mixCoverageComplete);
        }

        public static ProductionEvidence errorRate(ErrorRate value, Observation observedAt, String source,
                boolean fetched, boolean mixCoverageComplete) {
            return new ProductionEvidence(null, value, observedAt, source, fetched, mixCoverageComplete);
        }

        public boolean isStale(Instant now) {
            return EvidenceStaleness.isProductionStale(observedAt, now);
        }
    }

    /**
     * One prior Vortex execution's measured value for this metric, considered as a candidate baseline.
     *
     * @param executionId the execution this was measured on
     * @param quality     the run's validity grade — {@code INVALID} runs must never be treated as
     *                    evidence, only shown and explicitly excluded
     * @param latency     the execution's measured value, when the metric is latency
     * @param errorRate   the execution's measured value, when the metric is error rate
     * @param executedAt  when the execution ran
     */
    public record BaselineEvidence(
            String executionId, RunQuality quality, Duration latency, ErrorRate errorRate, Instant executedAt) {

        public BaselineEvidence {
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(quality, "quality");
            Objects.requireNonNull(executedAt, "executedAt");
            if (latency == null && errorRate == null) {
                throw new IllegalArgumentException(
                        "baseline evidence must report at least one of latency or error rate");
            }
            if (latency != null && errorRate != null) {
                throw new IllegalArgumentException(
                        "baseline evidence is assembled per metric — latency and error rate never both "
                                + "at once");
            }
        }

        public static BaselineEvidence latency(String executionId, RunQuality quality, Duration value,
                Instant executedAt) {
            return new BaselineEvidence(executionId, quality, value, null, executedAt);
        }

        public static BaselineEvidence errorRate(String executionId, RunQuality quality, ErrorRate value,
                Instant executedAt) {
            return new BaselineEvidence(executionId, quality, null, value, executedAt);
        }

        public boolean isStale(Instant now) {
            return EvidenceStaleness.isBaselineStale(executedAt, now);
        }
    }
}
