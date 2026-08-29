package com.acltabontabon.vortex.core.application;

import com.acltabontabon.vortex.core.capacity.ProductionObservation;
import com.acltabontabon.vortex.core.capacity.ProductionServiceLevel;
import com.acltabontabon.vortex.core.execution.ExecutionState;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.port.Clock;
import com.acltabontabon.vortex.core.port.Repositories;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.Percentile;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.threshold.recommend.ThresholdEvidence;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Assembles {@link ThresholdEvidence} for one metric — the current production observation, when a
 * source is configured and reports it, and every eligible prior execution, as a candidate baseline.
 *
 * <p>Objectives are configured at the service (project) level, not per workload — see ADR discussion
 * on why: different workloads legitimately need different intent, but one project's objectives are
 * meant to describe one service's acceptable behaviour, checked consistently across whatever runs
 * against it. Baseline candidacy is therefore scoped to the whole project by default. A
 * {@code workloadName} may still be supplied to narrow evidence to one workload's own history (e.g. a
 * capacity-specific question), but the common case leaves it absent and aggregates across every
 * completed execution in the project.
 *
 * <p>Deliberately thin, in the same spirit as {@link CalibrationService}: it decides <em>which</em>
 * evidence is relevant and translates it into {@link ThresholdEvidence}'s shape, and does no
 * recommendation arithmetic itself — that is {@code ThresholdRecommender}'s job, identically whether
 * the evidence came from here or a test double. It writes nothing.
 *
 * <h2>Why it reads {@code ProjectConfiguration.productionObservation}, not a live fetch</h2>
 * A recommendation reflects the evidence a user has actually reviewed and saved — the same
 * observation {@code WorkloadRecommender} already reads for the same reason. Fetching live on every
 * recommendation call would mean a threshold suggestion could change between keystrokes with no
 * action from the user, and re-fetching stays {@link CalibrationService}'s explicit, separate act.
 *
 * <h2>Why not {@code ExecutionRepository.findCompatible}</h2>
 * {@code findCompatible} matches on {@code ExperimentIdentity}'s fingerprint, and thresholds are one
 * of that fingerprint's own dimensions by design — two executions with different thresholds are,
 * correctly, a different experiment for regression-comparison purposes. Using it here would mean a
 * threshold could only ever be "protected" against executions that already carried the exact value
 * being reconsidered, which defeats the purpose. Baseline candidacy for threshold recommendation is a
 * looser question, so this walks {@link Repositories.ExecutionRepository#findByProject} directly.
 */
public final class ThresholdRecommendationService {

    /** How many of a project's most recent executions are scanned for baseline candidates. */
    static final int CANDIDATE_LOOKBACK = 25;

    private final Repositories.ExecutionRepository executions;
    private final Clock clock;

    public ThresholdRecommendationService(Repositories.ExecutionRepository executions, Clock clock) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Evidence for a latency percentile. Absent production or an unconfigured source produces an
     * empty {@code production} — never an error, since missing evidence must never block configuring
     * a threshold.
     *
     * @param workloadName narrows baseline candidacy to one workload's own history; null or blank
     *                      aggregates across every completed execution in the project — the normal
     *                      case for a service-level objective
     */
    public ThresholdEvidence latencyEvidence(ProjectId projectId, ProjectConfiguration configuration,
            String workloadName, Percentile percentile) {
        Objects.requireNonNull(percentile, "percentile");
        ThresholdEvidence.ProductionEvidence production = productionLatencyEvidence(configuration, percentile);
        List<ThresholdEvidence.BaselineEvidence> baselines =
                baselineEvidence(projectId, workloadName, execution -> latencyBaseline(execution, percentile));
        return new ThresholdEvidence(production, baselines);
    }

    /** Evidence for the error rate, following the same shape as {@link #latencyEvidence}. */
    public ThresholdEvidence errorRateEvidence(ProjectId projectId, ProjectConfiguration configuration,
            String workloadName) {
        ThresholdEvidence.ProductionEvidence production = productionErrorRateEvidence(configuration);
        List<ThresholdEvidence.BaselineEvidence> baselines =
                baselineEvidence(projectId, workloadName, ThresholdRecommendationService::errorRateBaseline);
        return new ThresholdEvidence(production, baselines);
    }

    // ------------------------------------------------------------------------------ production

    private ThresholdEvidence.ProductionEvidence productionLatencyEvidence(
            ProjectConfiguration configuration, Percentile percentile) {
        return productionServiceLevel(configuration).flatMap(level -> latencyFor(level, percentile)
                        .map(value -> ThresholdEvidence.ProductionEvidence.latency(
                                value, level.observation(), level.source(), level.wasFetched(), mixCoverageComplete(configuration))))
                .orElse(null);
    }

    private ThresholdEvidence.ProductionEvidence productionErrorRateEvidence(ProjectConfiguration configuration) {
        return productionServiceLevel(configuration)
                .flatMap(level -> level.errorRateIfPresent()
                        .map(value -> ThresholdEvidence.ProductionEvidence.errorRate(
                                value, level.observation(), level.source(), level.wasFetched(), mixCoverageComplete(configuration))))
                .orElse(null);
    }

    private static Optional<Duration> latencyFor(ProductionServiceLevel level, Percentile percentile) {
        if (percentile.equals(Percentile.P95)) {
            return level.p95LatencyIfPresent();
        }
        if (percentile.equals(Percentile.P99)) {
            return level.p99LatencyIfPresent();
        }
        return Optional.empty();
    }

    /**
     * Prefers whatever the project already has recorded over fetching live — a recommendation should
     * reflect the evidence a user has actually reviewed and saved, not a number that could change
     * between page loads. Live re-fetching is a distinct, explicit action ({@link CalibrationService}),
     * not something a recommendation triggers as a side effect.
     */
    private Optional<ProductionServiceLevel> productionServiceLevel(ProjectConfiguration configuration) {
        return configuration.productionObservationIfPresent().flatMap(ProductionObservation::serviceLevelIfPresent);
    }

    private static boolean mixCoverageComplete(ProjectConfiguration configuration) {
        return configuration.productionObservationIfPresent()
                .flatMap(ProductionObservation::mixCoverageIfPresent)
                .map(coverage -> coverage.isComplete())
                .orElse(false);
    }

    // -------------------------------------------------------------------------------- baselines

    private List<ThresholdEvidence.BaselineEvidence> baselineEvidence(ProjectId projectId, String workloadName,
            java.util.function.Function<TestExecution, ThresholdEvidence.BaselineEvidence> extractor) {
        if (projectId == null) {
            return List.of();
        }
        boolean scopedToOneWorkload = workloadName != null && !workloadName.isBlank();
        return executions.findByProject(projectId, CANDIDATE_LOOKBACK).stream()
                .filter(execution -> execution.state() == ExecutionState.COMPLETED)
                .filter(execution -> !scopedToOneWorkload
                        || workloadName.equalsIgnoreCase(execution.plan().workloadName()))
                .map(extractor)
                .filter(Objects::nonNull)
                .toList();
    }

    private static ThresholdEvidence.BaselineEvidence latencyBaseline(TestExecution execution, Percentile percentile) {
        if (execution.results() == null) {
            return null;
        }
        return execution.results().latency().at(percentile)
                .map(value -> ThresholdEvidence.BaselineEvidence.latency(
                        execution.id().value(), execution.quality().quality(), value, anchor(execution)))
                .orElse(null);
    }

    private static ThresholdEvidence.BaselineEvidence errorRateBaseline(TestExecution execution) {
        if (execution.results() == null || execution.results().requests() <= 0) {
            return null;
        }
        ErrorRate rate = ErrorRate.of(execution.results().failures(), execution.results().requests());
        return ThresholdEvidence.BaselineEvidence.errorRate(
                execution.id().value(), execution.quality().quality(), rate, anchor(execution));
    }

    private static Instant anchor(TestExecution execution) {
        return execution.finishedAt() != null ? execution.finishedAt() : execution.requestedAt();
    }

    Clock clock() {
        return clock;
    }
}
