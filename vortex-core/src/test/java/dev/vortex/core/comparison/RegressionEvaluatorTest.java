package dev.vortex.core.comparison;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.execution.ExecutionState;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.workload.TestType;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.workload.ConstantArrivalRateShape;
import dev.vortex.core.workload.ConstantConcurrencyShape;
import dev.vortex.core.workload.OperationMix;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Almost all the value here is in the refusal.
 *
 * <p>Computing "p95 went from 280 ms to 405 ms, +44.6%" is trivial. Knowing whether that sentence
 * means anything is not, and a percentage produced across two different experiments reads exactly
 * like a regression while measuring nothing of the kind.
 */
class RegressionEvaluatorTest {

    private final RegressionEvaluator evaluator = new RegressionEvaluator();

    private static TestExecution run(EffectiveTestPlan plan, long p95Millis) {
        return new TestExecution(ExecutionId.generate(), plan.projectId(), plan,
                ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW,
                Fixtures.NOW.plusSeconds(600), Fixtures.results(p95Millis, 0.001),
                null, null, null, null, "");
    }

    @Nested
    @DisplayName("comparison safety")
    class ComparisonSafety {

        @Test
        @DisplayName("the same number under a different workload model is not the same experiment")
        void openAndClosedAreNotComparable() {
            var byRate = run(Fixtures.plan(TestType.AVERAGE_LOAD,
                    ConstantArrivalRateShape.of(50, Duration.ofMinutes(10)),
                    OperationMix.single(Fixtures.GET_ORDER)), 280);
            var byConcurrency = run(Fixtures.plan(TestType.AVERAGE_LOAD,
                    ConstantConcurrencyShape.of(50, Duration.ofMinutes(10)),
                    OperationMix.single(Fixtures.GET_ORDER)), 405);

            var comparison = evaluator.compare(byRate, byConcurrency);

            assertThat(evaluator.evaluate(comparison)).isEqualTo(RegressionVerdict.NOT_COMPARABLE);
            assertThat(comparison.differences())
                    .anyMatch(difference -> difference.contains("workload model changed"))
                    .anyMatch(difference -> difference.contains("controls a different quantity"));
        }

        @Test
        @DisplayName("a changed operation mix means the two runs applied different traffic")
        void aChangedMixIsNotComparable() {
            var twoWay = run(Fixtures.plan(TestType.AVERAGE_LOAD,
                    ConstantArrivalRateShape.of(100, Duration.ofMinutes(10)),
                    Fixtures.operationMix()), 280);
            var fourWay = run(Fixtures.plan(TestType.AVERAGE_LOAD,
                    ConstantArrivalRateShape.of(100, Duration.ofMinutes(10)),
                    Fixtures.fourWayMix()), 405);

            var comparison = evaluator.compare(twoWay, fourWay);

            assertThat(evaluator.evaluate(comparison)).isEqualTo(RegressionVerdict.NOT_COMPARABLE);
            assertThat(comparison.differences())
                    .anyMatch(difference -> difference.contains("set of operations changed"));
        }

        @Test
        @DisplayName("a reweighted mix is reported by operation, not as a bare 'configuration changed'")
        void aReweightedMixNamesWhatMoved() {
            var even = run(Fixtures.plan(TestType.AVERAGE_LOAD,
                    ConstantArrivalRateShape.of(100, Duration.ofMinutes(10)),
                    OperationMix.of(java.util.List.of(
                            dev.vortex.core.workload.WeightedOperation.of(Fixtures.GET_ACCOUNT, 50),
                            dev.vortex.core.workload.WeightedOperation.of(Fixtures.GET_ORDER, 50)))),
                    280);
            var skewed = run(Fixtures.plan(TestType.AVERAGE_LOAD,
                    ConstantArrivalRateShape.of(100, Duration.ofMinutes(10)),
                    Fixtures.operationMix()), 405);

            var comparison = evaluator.compare(even, skewed);

            assertThat(comparison.supportsRegressionVerdict()).isFalse();
            assertThat(comparison.differences())
                    .anyMatch(difference -> difference.contains("share changed from 50% to 70%"));
        }

        @Test
        void aChangedLevelIsNotComparable() {
            var slower = run(Fixtures.plan(TestType.AVERAGE_LOAD,
                    ConstantArrivalRateShape.of(100, Duration.ofMinutes(10))), 280);
            var faster = run(Fixtures.plan(TestType.AVERAGE_LOAD,
                    ConstantArrivalRateShape.of(120, Duration.ofMinutes(10))), 405);

            assertThat(evaluator.evaluate(evaluator.compare(slower, faster)))
                    .isEqualTo(RegressionVerdict.NOT_COMPARABLE);
        }

        @Test
        void aChangedTestTypeIsNotComparable() {
            var averageLoad = run(Fixtures.plan(TestType.AVERAGE_LOAD,
                    ConstantArrivalRateShape.of(100, Duration.ofMinutes(10))), 280);
            var stress = run(Fixtures.plan(TestType.STRESS,
                    ConstantArrivalRateShape.of(100, Duration.ofMinutes(10))), 405);

            assertThat(evaluator.evaluate(evaluator.compare(averageLoad, stress)))
                    .isEqualTo(RegressionVerdict.NOT_COMPARABLE);
        }
    }

    @Nested
    @DisplayName("when the two runs did test the same thing")
    class EquivalentRuns {

        @Test
        void identicalWorkloadsCompare() {
            var comparison = evaluator.compare(run(Fixtures.plan(), 280), run(Fixtures.plan(), 280));

            assertThat(comparison.supportsRegressionVerdict()).isTrue();
            assertThat(evaluator.evaluate(comparison)).isEqualTo(RegressionVerdict.UNCHANGED);
        }

        @Test
        void aRealSlowdownIsReportedAsARegression() {
            var comparison = evaluator.compare(run(Fixtures.plan(), 280), run(Fixtures.plan(), 520));

            assertThat(comparison.supportsRegressionVerdict()).isTrue();
            assertThat(evaluator.evaluate(comparison)).isEqualTo(RegressionVerdict.REGRESSED);
        }

        @Test
        @DisplayName("run-to-run wobble is not a regression")
        void smallDifferencesAreVariance() {
            var comparison = evaluator.compare(run(Fixtures.plan(), 280), run(Fixtures.plan(), 295));

            assertThat(evaluator.evaluate(comparison)).isEqualTo(RegressionVerdict.UNCHANGED);
        }
    }

    /**
     * The cases a percentage cannot express.
     *
     * <p>A healthy baseline is the normal baseline, and a healthy baseline has a zero error rate.
     * There is no percentage change from zero, so the single most important regression a service
     * can have — errors appearing where there were none — is exactly the one relative arithmetic
     * silently drops.
     */
    @Nested
    @DisplayName("changes with no percentage to express them")
    class ZeroBaselines {

        private TestExecution withErrors(double fraction) {
            return new TestExecution(ExecutionId.generate(), Fixtures.plan().projectId(),
                    Fixtures.plan(), ExecutionState.COMPLETED, Fixtures.NOW, Fixtures.NOW,
                    Fixtures.NOW.plusSeconds(600), Fixtures.results(280, fraction),
                    null, null, null, null, "");
        }

        @Test
        @DisplayName("errors appearing against a clean baseline is a regression")
        void errorsAppearingIsARegression() {
            var comparison = evaluator.compare(withErrors(0.0), withErrors(0.25));

            assertThat(comparison.supportsRegressionVerdict()).isTrue();
            assertThat(evaluator.evaluate(comparison)).isEqualTo(RegressionVerdict.REGRESSED);
        }

        @Test
        @DisplayName("errors disappearing is an improvement")
        void errorsDisappearingIsAnImprovement() {
            var comparison = evaluator.compare(withErrors(0.25), withErrors(0.0));

            assertThat(evaluator.evaluate(comparison)).isEqualTo(RegressionVerdict.IMPROVED);
        }

        @Test
        @DisplayName("two clean runs are still unchanged")
        void twoCleanRunsAreUnchanged() {
            var comparison = evaluator.compare(withErrors(0.0), withErrors(0.0));

            assertThat(evaluator.evaluate(comparison)).isEqualTo(RegressionVerdict.UNCHANGED);
        }
    }

    /**
     * A run that measured nothing has not been shown to be unchanged.
     *
     * <p>Two failed runs have identical plans and no measurements, which is precisely the shape
     * that arrives at "no change large enough to distinguish from run-to-run variance" — the most
     * confident thing Vortex could say about the least evidence it has.
     */
    @Nested
    @DisplayName("runs that produced no measurements")
    class WithoutResults {

        private TestExecution failedRun() {
            return new TestExecution(ExecutionId.generate(), Fixtures.plan().projectId(),
                    Fixtures.plan(), ExecutionState.FAILED, Fixtures.NOW, Fixtures.NOW,
                    Fixtures.NOW.plusSeconds(12), null, null, null, null,
                    dev.vortex.core.execution.FailureReason.ENGINE_FAILED, "");
        }

        @Test
        void twoRunsWithNothingMeasuredAreNotComparable() {
            var comparison = evaluator.compare(failedRun(), failedRun());

            assertThat(comparison.supportsRegressionVerdict()).isFalse();
            assertThat(evaluator.evaluate(comparison)).isEqualTo(RegressionVerdict.NOT_COMPARABLE);
            assertThat(comparison.differences())
                    .anyMatch(difference -> difference.contains("no measurements"));
        }

        @Test
        @DisplayName("the run that measured nothing is named, so the reader knows which")
        void aHalfMeasuredComparisonNamesTheMissingSide() {
            var comparison = evaluator.compare(run(Fixtures.plan(), 280), failedRun());

            assertThat(evaluator.evaluate(comparison)).isEqualTo(RegressionVerdict.NOT_COMPARABLE);
            assertThat(comparison.differences())
                    .anyMatch(difference -> difference.startsWith("the candidate run"));
        }
    }
}
