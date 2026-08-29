package com.acltabontabon.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.execution.ExecutionState;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.fixtures.InMemoryExecutions;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.shared.Percentile;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.threshold.LatencyThreshold;
import com.acltabontabon.vortex.core.threshold.ThresholdSet;
import com.acltabontabon.vortex.core.validity.RunQualityAssessment;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ThresholdHistoryServiceTest {

    private static final ProjectId PROJECT = ProjectId.of("checkout");
    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");

    private final InMemoryExecutions executions = new InMemoryExecutions();
    private final ThresholdHistoryService service = new ThresholdHistoryService(executions);

    @Test
    void historyIsEmptyWhenNothingHasRun() {
        assertThat(service.history(PROJECT, "average_load", "latency.p95")).isEmpty();
    }

    @Test
    void onlyChangesAreRecordedNewestFirst() {
        saveExecution("run-1", NOW.minus(Duration.ofDays(3)), Duration.ofMillis(700));
        saveExecution("run-2", NOW.minus(Duration.ofDays(2)), Duration.ofMillis(700));
        saveExecution("run-3", NOW.minus(Duration.ofDays(1)), Duration.ofMillis(550));

        var history = service.history(PROJECT, "average_load", "latency.p95");

        assertThat(history).extracting(ThresholdHistoryEntry::value).containsExactly("550 ms", "700 ms");
        assertThat(history).extracting(ThresholdHistoryEntry::executionId).containsExactly("run-3", "run-1");
    }

    @Test
    void executionsWithoutThatThresholdAreSilentlySkipped() {
        saveExecution("run-1", NOW.minus(Duration.ofDays(1)), Duration.ofMillis(500));

        assertThat(service.history(PROJECT, "average_load", "latency.p99")).isEmpty();
    }

    private void saveExecution(String id, Instant requestedAt, Duration p95) {
        EffectiveTestPlan basePlan = Fixtures.plan();
        ThresholdSet thresholds = ThresholdSet.of(LatencyThreshold.of(Percentile.P95, p95));
        EffectiveTestPlan plan = new EffectiveTestPlan(
                basePlan.id(), PROJECT, basePlan.projectName(), basePlan.serviceVersion(), basePlan.intent(),
                "average_load", basePlan.workloadDescription(), basePlan.testType(), basePlan.workloadModel(),
                basePlan.peakLevel(), basePlan.stages(), basePlan.operations(), basePlan.datasets(),
                basePlan.workloadSource(), thresholds, basePlan.environmentName(),
                basePlan.environmentType(), basePlan.configuredTarget(), basePlan.effectiveTarget(),
                basePlan.targetRewriteReason(), basePlan.dependencyMode(), basePlan.classification(),
                basePlan.headers(), basePlan.k6Options(), basePlan.runner(), basePlan.scriptSource(),
                basePlan.safetyDecisions(), basePlan.fingerprint());

        TestExecution execution = new TestExecution(ExecutionId.of(id), PROJECT, plan, ExecutionState.COMPLETED,
                requestedAt, requestedAt, requestedAt, Fixtures.results(280, 0.001), null, null, null, null, "",
                RunQualityAssessment.valid(), null, null);
        executions.save(execution);
    }
}
