package com.acltabontabon.vortex.core.comparison;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.plan.ExperimentCompatibility;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Whether an AI interpretation may trust the pair it was given is a fact Vortex computes, the same
 * way the deltas themselves are — never left for a model to notice partway through.
 */
class ComparabilityTest {

    private static final ExecutionId BASELINE = ExecutionId.of("baseline");
    private static final ExecutionId CANDIDATE = ExecutionId.of("candidate");

    private static MetricDelta latencyDelta() {
        return new MetricDelta("p95 latency", "latency.p95",
                BigDecimal.valueOf(280), BigDecimal.valueOf(405), "280 ms → 405 ms", true);
    }

    @Test
    void differentExperimentsAreInvalid() {
        ExecutionComparison comparison = new ExecutionComparison(BASELINE, CANDIDATE,
                ExperimentCompatibility.of(List.of("workload model changed")), List.of(latencyDelta()));

        assertThat(Comparability.classify(comparison, false, false)).isEqualTo(Comparability.INVALID);
    }

    @Test
    void noDeltasAreInvalidEvenWhenCompatible() {
        ExecutionComparison comparison = new ExecutionComparison(BASELINE, CANDIDATE,
                ExperimentCompatibility.COMPATIBLE, List.of());

        assertThat(Comparability.classify(comparison, false, false)).isEqualTo(Comparability.INVALID);
    }

    @Test
    void compatibleWithGapsOnEitherSideIsPartial() {
        ExecutionComparison comparison = new ExecutionComparison(BASELINE, CANDIDATE,
                ExperimentCompatibility.COMPATIBLE, List.of(latencyDelta()));

        assertThat(Comparability.classify(comparison, true, false)).isEqualTo(Comparability.PARTIAL);
        assertThat(Comparability.classify(comparison, false, true)).isEqualTo(Comparability.PARTIAL);
    }

    @Test
    void compatibleWithNoGapsIsHigh() {
        ExecutionComparison comparison = new ExecutionComparison(BASELINE, CANDIDATE,
                ExperimentCompatibility.COMPATIBLE, List.of(latencyDelta()));

        assertThat(Comparability.classify(comparison, false, false)).isEqualTo(Comparability.HIGH);
    }
}
