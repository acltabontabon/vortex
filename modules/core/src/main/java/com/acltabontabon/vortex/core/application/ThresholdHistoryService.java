package com.acltabontabon.vortex.core.application;

import com.acltabontabon.vortex.core.execution.ExecutionState;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.port.Repositories;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.threshold.Durations;
import com.acltabontabon.vortex.core.threshold.ErrorRateThreshold;
import com.acltabontabon.vortex.core.threshold.LatencyThreshold;
import com.acltabontabon.vortex.core.threshold.Threshold;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A threshold's history, read from what actually ran — never a separate audit log.
 *
 * <p>Every execution already snapshots the resolved {@code ThresholdSet} it was evaluated against, on
 * its {@code EffectiveTestPlan}. This is a query over that existing evidence, not a new store: history
 * exists so an experiment stays reproducible and so a run comparison can flag "the objective changed",
 * not to be a general-purpose audit product.
 */
public final class ThresholdHistoryService {

    /** How many of a project's most recent executions are scanned for a threshold's history. */
    static final int LOOKBACK = 50;

    private final Repositories.ExecutionRepository executions;

    public ThresholdHistoryService(Repositories.ExecutionRepository executions) {
        this.executions = Objects.requireNonNull(executions, "executions");
    }

    /**
     * A threshold's value at each point it changed, newest first. An execution whose plan carries no
     * such threshold id is silently skipped — the id did not exist in that run, which is not a change
     * to track, it is a threshold that did not exist yet.
     *
     * @param workloadName narrows history to one workload's own runs; null or blank aggregates across
     *                      every completed execution in the project — the normal case for a
     *                      service-level objective
     */
    public List<ThresholdHistoryEntry> history(ProjectId projectId, String workloadName, String thresholdId) {
        if (projectId == null || thresholdId == null || thresholdId.isBlank()) {
            return List.of();
        }
        boolean scopedToOneWorkload = workloadName != null && !workloadName.isBlank();

        List<TestExecution> chronological = executions.findByProject(projectId, LOOKBACK).stream()
                .filter(execution -> execution.state() == ExecutionState.COMPLETED)
                .filter(execution -> !scopedToOneWorkload
                        || workloadName.equalsIgnoreCase(execution.plan().workloadName()))
                .sorted((a, b) -> anchor(a).compareTo(anchor(b)))
                .toList();

        List<ThresholdHistoryEntry> entries = new ArrayList<>();
        String lastValue = null;
        for (TestExecution execution : chronological) {
            Threshold threshold = execution.plan().thresholds().byId(thresholdId).orElse(null);
            if (threshold == null) {
                continue;
            }
            String value = displayValue(threshold);
            if (!value.equals(lastValue)) {
                entries.add(new ThresholdHistoryEntry(execution.id().value(), value, anchor(execution)));
                lastValue = value;
            }
        }

        return entries.reversed();
    }

    private static String displayValue(Threshold threshold) {
        if (threshold instanceof LatencyThreshold latency) {
            return Durations.display(latency.maximum());
        }
        if (threshold instanceof ErrorRateThreshold errorRate) {
            return errorRate.maximum().display();
        }
        throw new IllegalStateException("unrecognised threshold type: " + threshold.getClass());
    }

    private static Instant anchor(TestExecution execution) {
        return execution.finishedAt() != null ? execution.finishedAt() : execution.requestedAt();
    }
}
