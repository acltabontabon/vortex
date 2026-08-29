package com.acltabontabon.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.capacity.ObservationSource;
import com.acltabontabon.vortex.core.capacity.ProductionObservation;
import com.acltabontabon.vortex.core.capacity.ProductionServiceLevel;
import com.acltabontabon.vortex.core.execution.ExecutionState;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.fixtures.InMemoryExecutions;
import com.acltabontabon.vortex.core.metrics.MeasuredResults;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.port.Clock;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.shared.Percentile;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.threshold.recommend.ThresholdEvidence;
import com.acltabontabon.vortex.core.validity.RunQualityAssessment;
import com.acltabontabon.vortex.core.validity.ValidityEffect;
import com.acltabontabon.vortex.core.validity.ValidityFinding;
import com.acltabontabon.vortex.core.validity.ValidityReason;
import com.acltabontabon.vortex.core.workload.Observation;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ThresholdRecommendationServiceTest {

    private static final ProjectId PROJECT = ProjectId.of("checkout");
    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");

    private final InMemoryExecutions executions = new InMemoryExecutions();
    private final ThresholdRecommendationService service =
            new ThresholdRecommendationService(executions, Clock.fixed(NOW));

    @Test
    void missingProductionAndNoBaselinesProduceEmptyEvidence() {
        ThresholdEvidence evidence = service.latencyEvidence(
                PROJECT, ProjectConfiguration.empty(), "average_load", Percentile.P95);

        assertThat(evidence.productionIfPresent()).isEmpty();
        assertThat(evidence.baselines()).isEmpty();
    }

    @Test
    void productionEvidenceIsReadFromTheSavedObservationNotFetchedLive() {
        ProjectConfiguration configuration = withServiceLevel(
                new ProductionServiceLevel(Duration.ofMillis(620), Duration.ofMillis(900), null,
                        ErrorRate.ofPercent(0.08), recentWindow(), "prometheus", null));

        ThresholdEvidence evidence = service.latencyEvidence(PROJECT, configuration, "average_load", Percentile.P95);

        assertThat(evidence.productionIfPresent()).hasValueSatisfying(production ->
                assertThat(production.latency()).isEqualTo(Duration.ofMillis(620)));
    }

    @Test
    void onlyCompletedExecutionsOfTheSameWorkloadBecomeBaselineCandidates() {
        saveExecution("run-same-workload", "average_load", ExecutionState.COMPLETED, RunQualityAssessment.valid(),
                () -> Fixtures.results(500, 0.001));
        saveExecution("run-other-workload", "breakpoint", ExecutionState.COMPLETED, RunQualityAssessment.valid(),
                () -> Fixtures.results(100, 0.001));
        saveExecution("run-unfinished", "average_load", ExecutionState.RUNNING, RunQualityAssessment.valid(),
                () -> Fixtures.results(999, 0.001));

        ThresholdEvidence evidence = service.latencyEvidence(
                PROJECT, ProjectConfiguration.empty(), "average_load", Percentile.P95);

        assertThat(evidence.baselines()).extracting(ThresholdEvidence.BaselineEvidence::executionId)
                .containsExactly("run-same-workload");
    }

    @Test
    void invalidRunsAreStillReturnedAsCandidatesButExcludedFromEligibility() {
        saveExecution("run-invalid", "average_load", ExecutionState.COMPLETED,
                RunQualityAssessment.of(List.of(new ValidityFinding(
                        ValidityReason.GENERATOR_SATURATED, ValidityEffect.WITHHOLDS_ALL_CLAIMS, "saturated", List.of()))),
                () -> Fixtures.results(200, 0.001));

        ThresholdEvidence evidence = service.latencyEvidence(
                PROJECT, ProjectConfiguration.empty(), "average_load", Percentile.P95);

        assertThat(evidence.baselines()).hasSize(1);
        assertThat(evidence.eligibleBaselines()).isEmpty();
        assertThat(evidence.bestBaseline()).isEmpty();
    }

    @Test
    void errorRateEvidenceReadsFailuresAndRequestsFromMeasuredResults() {
        saveExecution("run-1", "average_load", ExecutionState.COMPLETED, RunQualityAssessment.valid(),
                () -> Fixtures.results(500, 0.008));

        ThresholdEvidence evidence = service.errorRateEvidence(PROJECT, ProjectConfiguration.empty(), "average_load");

        assertThat(evidence.baselines()).singleElement()
                .satisfies(baseline -> assertThat(baseline.errorRate().asPercent()).isCloseTo(0.8, org.assertj.core.data.Offset.offset(0.05)));
    }

    private ProjectConfiguration withServiceLevel(ProductionServiceLevel serviceLevel) {
        ProductionObservation observation = new ProductionObservation(
                com.acltabontabon.vortex.core.shared.RequestsPerSecond.of(20),
                com.acltabontabon.vortex.core.shared.RequestsPerSecond.of(45),
                com.acltabontabon.vortex.core.shared.RequestsPerSecond.of(80),
                null, null, null, "prometheus", recentWindow(), null, "", serviceLevel);
        return ProjectConfiguration.empty().withProductionObservation(observation);
    }

    private void saveExecution(String id, String workloadName, ExecutionState state,
            RunQualityAssessment quality, Supplier<MeasuredResults> results) {
        EffectiveTestPlan basePlan = Fixtures.plan();
        EffectiveTestPlan plan = new EffectiveTestPlan(
                basePlan.id(), PROJECT, basePlan.projectName(), basePlan.serviceVersion(), basePlan.intent(),
                workloadName, basePlan.workloadDescription(), basePlan.testType(), basePlan.workloadModel(),
                basePlan.peakLevel(), basePlan.stages(), basePlan.operations(), basePlan.datasets(),
                basePlan.workloadSource(), basePlan.thresholds(), basePlan.environmentName(),
                basePlan.environmentType(), basePlan.configuredTarget(), basePlan.effectiveTarget(),
                basePlan.targetRewriteReason(), basePlan.dependencyMode(), basePlan.classification(),
                basePlan.headers(), basePlan.k6Options(), basePlan.runner(), basePlan.scriptSource(),
                basePlan.safetyDecisions(), basePlan.fingerprint());

        TestExecution execution = new TestExecution(ExecutionId.of(id), PROJECT, plan, state,
                NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(1)),
                results.get(), null, null, null, null, "", quality, null, null);
        executions.save(execution);
    }

    private static Observation recentWindow() {
        return Observation.over(NOW.minus(Duration.ofDays(30)), NOW);
    }
}
