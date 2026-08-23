package com.acltabontabon.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.comparison.RegressionEvaluator;
import com.acltabontabon.vortex.core.comparison.RegressionVerdict;
import com.acltabontabon.vortex.core.execution.ExecutionState;
import com.acltabontabon.vortex.core.execution.FailureReason;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.fixtures.InMemoryExecutions;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.workload.TestType;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.workload.ConstantArrivalRateShape;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Finding the run worth comparing against.
 *
 * <p>"The previous run" is the phrase everyone uses and almost never what they mean. The previous
 * run of this project may have applied a different workload to a different environment; comparing
 * against it measures the difference between two experiments and reports it as a change in the
 * service.
 */
class ComparisonServiceTest {

    private InMemoryExecutions executions;
    private ComparisonService comparisons;

    @BeforeEach
    void setUp() {
        executions = new InMemoryExecutions();
        comparisons = new ComparisonService(executions, new RegressionEvaluator());
    }

    private TestExecution completed(String id, EffectiveTestPlan plan, long secondsAgo,
            long p95Millis) {

        var requestedAt = Fixtures.NOW.minusSeconds(secondsAgo);
        return executions.save(new TestExecution(ExecutionId.of(id), plan.projectId(), plan,
                ExecutionState.COMPLETED, requestedAt, requestedAt,
                requestedAt.plusSeconds(600), Fixtures.results(p95Millis, 0.001), null, null, null,
                null, ""));
    }

    private static EffectiveTestPlan release(String version) {
        return Fixtures.plan().withServiceVersion(version).withComputedFingerprint();
    }

    @Nested
    @DisplayName("finding a baseline")
    class FindingABaseline {

        @Test
        @DisplayName("the previous run of the same experiment against a different release")
        void findsTheEarlierRunOfTheSameExperiment() {
            var baseline = completed("baseline", release("abc123"), 3600, 280);
            var candidate = completed("candidate", release("def456"), 0, 300);

            assertThat(comparisons.previousCompatible(candidate))
                    .hasValueSatisfying(found ->
                            assertThat(found.id()).isEqualTo(baseline.id()));
        }

        @Test
        @DisplayName("a run of a different experiment is not a baseline, however recent")
        void skipsIncompatibleRuns() {
            completed("other-workload", Fixtures.plan(TestType.AVERAGE_LOAD,
                    ConstantArrivalRateShape.of(999, Duration.ofMinutes(10))), 60, 280);
            var candidate = completed("candidate", release("def456"), 0, 300);

            assertThat(comparisons.previousCompatible(candidate)).isEmpty();
        }

        @Test
        @DisplayName("the run itself is never its own baseline")
        void excludesItself() {
            var only = completed("only", release("abc123"), 0, 280);

            assertThat(comparisons.previousCompatible(only)).isEmpty();
        }

        @Test
        @DisplayName("the most recent earlier run wins, not the oldest")
        void picksTheMostRecent() {
            completed("oldest", release("v1"), 7200, 280);
            completed("middle", release("v2"), 3600, 290);
            var candidate = completed("candidate", release("v3"), 0, 300);

            assertThat(comparisons.previousCompatible(candidate))
                    .hasValueSatisfying(found ->
                            assertThat(found.id().value()).isEqualTo("middle"));
        }

        @Test
        @DisplayName("a run that did not complete is not evidence of anything")
        void skipsRunsThatNeverCompleted() {
            var plan = release("abc123");
            executions.save(new TestExecution(ExecutionId.of("failed"), plan.projectId(), plan,
                    ExecutionState.FAILED, Fixtures.NOW.minusSeconds(3600),
                    Fixtures.NOW.minusSeconds(3600), Fixtures.NOW.minusSeconds(3500), null, null,
                    null, null, FailureReason.ENGINE_FAILED, ""));
            var candidate = completed("candidate", release("def456"), 0, 300);

            assertThat(comparisons.previousCompatible(candidate)).isEmpty();
        }

        @Test
        @DisplayName("every earlier compatible run is available, not only the one Vortex would pick")
        void offersTheWholeCompatibleHistory() {
            completed("oldest", release("v1"), 7200, 280);
            completed("middle", release("v2"), 3600, 290);
            var candidate = completed("candidate", release("v3"), 0, 300);

            assertThat(comparisons.compatibleHistory(candidate))
                    .extracting(execution -> execution.id().value())
                    .containsExactly("middle", "oldest");
        }
    }

    @Nested
    @DisplayName("comparing")
    class Comparing {

        @Test
        @DisplayName("two releases of the same experiment produce a verdict")
        void differentReleasesAreComparable() {
            var baseline = completed("baseline", release("abc123"), 3600, 280);
            var candidate = completed("candidate", release("def456"), 0, 520);

            var result = comparisons.compareAndEvaluate(baseline, candidate);

            assertThat(result.supportsVerdict()).isTrue();
            assertThat(result.verdict()).isEqualTo(RegressionVerdict.REGRESSED);
        }

        @Test
        @DisplayName("a changed workload is explained rather than turned into a percentage")
        void anIncompatiblePairIsExplained() {
            var baseline = completed("baseline", release("abc123"), 3600, 280);
            var candidate = completed("candidate", Fixtures.plan(TestType.AVERAGE_LOAD,
                    ConstantArrivalRateShape.of(999, Duration.ofMinutes(10))), 0, 520);

            var result = comparisons.compareAndEvaluate(baseline, candidate);

            assertThat(result.supportsVerdict()).isFalse();
            assertThat(result.verdict()).isEqualTo(RegressionVerdict.NOT_COMPARABLE);
            assertThat(result.comparison().differences())
                    .anyMatch(difference -> difference.contains("offered load changed"));
            // The measurements are still shown; only the conclusion is withheld.
            assertThat(result.comparison().deltas()).isNotEmpty();
        }

        @Test
        @DisplayName("a run that measured nothing yields no conclusion in either direction")
        void anUnmeasuredRunYieldsNoVerdict() {
            var plan = release("abc123");
            var failed = new TestExecution(ExecutionId.of("failed"), plan.projectId(), plan,
                    ExecutionState.FAILED, Fixtures.NOW, Fixtures.NOW, Fixtures.NOW.plusSeconds(12),
                    null, null, null, null, FailureReason.ENGINE_FAILED, "");
            var completed = completed("candidate", release("def456"), 0, 300);

            var result = comparisons.compareAndEvaluate(failed, completed);

            assertThat(result.verdict()).isEqualTo(RegressionVerdict.NOT_COMPARABLE);
            assertThat(result.comparison().differences())
                    .anyMatch(difference -> difference.contains("no measurements"));
        }
    }
}
