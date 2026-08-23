package dev.vortex.core.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.analysis.LimitFindings;
import dev.vortex.core.analysis.StageObservation;
import dev.vortex.core.analysis.ThroughputCeiling;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.metrics.Aggregation;
import dev.vortex.core.metrics.MetricObservation;
import dev.vortex.core.metrics.MetricSource;
import dev.vortex.core.metrics.MetricUnit;
import dev.vortex.core.metrics.TimeWindow;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.resource.ResourceKind;
import dev.vortex.core.resource.ResourceLimit;
import dev.vortex.core.resource.ResourceScope;
import dev.vortex.core.resource.ResourceSignal;
import dev.vortex.core.shared.ErrorRate;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.validity.RunQualityAssessment;
import dev.vortex.core.validity.ValidityEffect;
import dev.vortex.core.validity.ValidityFinding;
import dev.vortex.core.validity.ValidityReason;
import dev.vortex.core.workload.RampingArrivalRateShape;
import dev.vortex.core.workload.Stage;
import dev.vortex.core.workload.StageWindowBasis;
import dev.vortex.core.workload.TestType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The highest level a service was demonstrated to sustain, and the five reasons it usually is not.
 *
 * <p>Every condition is asserted failing in isolation, because the value of the figure is that each
 * one is separately falsifiable. A capacity that frequently declines to exist is the cost of one
 * that means something when it does — and the refusal has to name which condition failed, or it is
 * just a missing number.
 */
class SustainableCapacityTest {

    private static final Instant START = Instant.parse("2026-08-21T10:00:00Z");

    private final SustainableCapacityCalculator calculator = new SustainableCapacityCalculator();

    /** An average-load plan that ramps to 200 and then holds it. */
    private static EffectiveTestPlan heldFor(Duration hold) {
        return Fixtures.plan(TestType.AVERAGE_LOAD, ramp(
                new Stage(RequestsPerSecond.of(200), Duration.ofMinutes(1)),
                new Stage(RequestsPerSecond.of(200), hold)));
    }

    private static RampingArrivalRateShape ramp(Stage... stages) {
        return new RampingArrivalRateShape(RequestsPerSecond.of(10), List.of(stages));
    }

    private static StageObservation stage(double offered, double achieved,
            List<String> violated, List<ResourceSignal> signals) {
        return new StageObservation(RequestsPerSecond.of(offered), RequestsPerSecond.of(achieved),
                Duration.ofMillis(120), ErrorRate.ZERO, 12, violated, List.of(),
                StageWindowBasis.OBSERVED, signals, 5_000);
    }

    private static ResourceSignal pool(double percent) {
        return new ResourceSignal(
                MetricObservation.of("metric:pool.connections.utilization", "Connection pool",
                        MetricSource.ACTUATOR, MetricUnit.PERCENT, Aggregation.MAX, percent,
                        new TimeWindow(START, START.plusSeconds(600))),
                ResourceKind.POOL, ResourceScope.SYSTEM_UNDER_TEST,
                ResourceLimit.inherentToPercentage());
    }

    private SustainableCapacity calculate(EffectiveTestPlan plan, List<StageObservation> stages,
            RunQualityAssessment quality, LimitFindings limits) {
        return calculator.calculate(plan, stages, quality, limits);
    }

    @Test
    @DisplayName("all five met establishes the level, with every condition reported")
    void allFiveConditionsMet() {
        var capacity = calculate(heldFor(Duration.ofMinutes(10)),
                List.of(stage(200, 199, List.of(), List.of(pool(30)))),
                RunQualityAssessment.valid(), null);

        assertThat(capacity.isEstablished()).isTrue();
        assertThat(capacity.levelIfPresent())
                .hasValueSatisfying(level -> assertThat(level.asDouble()).isEqualTo(200));
        // Every condition individually evaluated is what makes the figure defensible in a review.
        assertThat(capacity.conditions()).hasSize(5);
        assertThat(capacity.conditions()).allSatisfy(condition ->
                assertThat(condition.outcome()).isEqualTo(ConditionResult.Outcome.MET));
        assertThat(capacity.unmet()).isEmpty();
    }

    @Nested
    @DisplayName("each condition, failing on its own")
    class EachCondition {

        @Test
        @DisplayName("the offered load was not generated")
        void loadWasNotGenerated() {
            var withheld = RunQualityAssessment.of(List.of(new ValidityFinding(
                    ValidityReason.OFFERED_LOAD_NOT_GENERATED, ValidityEffect.WITHHOLDS_CAPACITY,
                    "The generator could not start 4812 units of work.", List.of())));

            var capacity = calculate(heldFor(Duration.ofMinutes(10)),
                    List.of(stage(200, 199, List.of(), List.of(pool(30)))), withheld, null);

            assertThat(capacity.isEstablished()).isFalse();
            assertThat(capacity.unmet()).singleElement().satisfies(condition ->
                    assertThat(condition.condition())
                            .isEqualTo(SustainabilityCondition.OFFERED_LOAD_WAS_GENERATED));
            assertThat(capacity.refusal()).contains("4812");
        }

        @Test
        @DisplayName("an objective was violated")
        void objectivesWereNotMet() {
            var capacity = calculate(heldFor(Duration.ofMinutes(10)),
                    List.of(stage(200, 199, List.of("p95 latency below 200 ms"), List.of(pool(30)))),
                    RunQualityAssessment.valid(), null);

            assertThat(capacity.isEstablished()).isFalse();
            assertThat(capacity.refusal()).contains("p95 latency below 200 ms");
        }

        @Test
        @DisplayName("the level was not held long enough, and both durations are named")
        void notHeldLongEnough() {
            var capacity = calculate(heldFor(Duration.ofMinutes(2)),
                    List.of(stage(200, 199, List.of(), List.of(pool(30)))),
                    RunQualityAssessment.valid(), null);

            assertThat(capacity.isEstablished()).isFalse();
            // The number an engineer needs to argue with the refusal is the number they need to fix
            // it, so both appear.
            assertThat(capacity.refusal()).contains("2m").contains("5m");
        }

        @Test
        @DisplayName("throughput stopped tracking the offered load")
        void throughputDidNotTrack() {
            var capacity = calculate(heldFor(Duration.ofMinutes(10)),
                    List.of(stage(200, 140, List.of(), List.of(pool(30)))),
                    RunQualityAssessment.valid(), null);

            assertThat(capacity.isEstablished()).isFalse();
            assertThat(capacity.unmet()).anySatisfy(condition -> assertThat(condition.condition())
                    .isEqualTo(SustainabilityCondition.THROUGHPUT_TRACKED_OFFERED_LOAD));
        }

        @Test
        @DisplayName("a level above the throughput ceiling is not sustainable")
        void aboveTheThroughputCeiling() {
            var limits = new LimitFindings(null,
                    new ThroughputCeiling(ThroughputCeiling.Status.OBSERVED,
                            RequestsPerSecond.of(150), RequestsPerSecond.of(200),
                            dev.vortex.core.analysis.EvidenceStrength.HIGH, 4, ""),
                    null, null, List.of());

            var capacity = calculate(heldFor(Duration.ofMinutes(10)),
                    List.of(stage(200, 199, List.of(), List.of(pool(30)))),
                    RunQualityAssessment.valid(), limits);

            assertThat(capacity.isEstablished()).isFalse();
            assertThat(capacity.refusal()).contains("throughput ceiling");
        }

        @Test
        @DisplayName("a resource reached its declared limit")
        void aResourceRanOut() {
            var capacity = calculate(heldFor(Duration.ofMinutes(10)),
                    List.of(stage(200, 199, List.of(), List.of(pool(98)))),
                    RunQualityAssessment.valid(), null);

            assertThat(capacity.isEstablished()).isFalse();
            assertThat(capacity.unmet()).anySatisfy(condition -> assertThat(condition.condition())
                    .isEqualTo(SustainabilityCondition.NO_RESOURCE_REACHED_ITS_LIMIT));
        }
    }

    @Nested
    @DisplayName("condition five with no telemetry")
    class WithoutResourceTelemetry {

        @Test
        @DisplayName("is not evaluated rather than assumed satisfied")
        void notEvaluatedRatherThanMet() {
            // The single most important distinction in this class. "No resource reached its limit"
            // is not something a run without resource telemetry established, and treating an
            // absence as satisfaction is how a capacity figure quietly outgrows its evidence.
            var capacity = calculate(heldFor(Duration.ofMinutes(10)),
                    List.of(stage(200, 199, List.of(), List.of())),
                    RunQualityAssessment.valid(), null);

            assertThat(capacity.notEvaluatedConditions()).singleElement()
                    .satisfies(condition -> assertThat(condition.condition())
                            .isEqualTo(SustainabilityCondition.NO_RESOURCE_REACHED_ITS_LIMIT));
        }

        @Test
        @DisplayName("but still establishes a capacity, reported as the weaker claim it is")
        void stillEstablishesAWeakerCapacity() {
            var capacity = calculate(heldFor(Duration.ofMinutes(10)),
                    List.of(stage(200, 199, List.of(), List.of())),
                    RunQualityAssessment.valid(), null);

            // Refusing outright would leave every team without resource telemetry with no capacity
            // at all; asserting it as strongly as a fully observed run would overstate it. Stated
            // as weaker is the only answer true in both directions.
            assertThat(capacity.isEstablished()).isTrue();
            assertThat(capacity.strength())
                    .isEqualTo(dev.vortex.core.analysis.EvidenceStrength.MEDIUM);
        }

        @Test
        @DisplayName("and the statement says unknown rather than ruled out")
        void theStatementSaysUnknown() {
            var condition = calculate(heldFor(Duration.ofMinutes(10)),
                    List.of(stage(200, 199, List.of(), List.of())),
                    RunQualityAssessment.valid(), null)
                    .notEvaluatedConditions().getFirst();

            assertThat(condition.statement()).contains("unknown rather than ruled out");
        }
    }

    @Nested
    @DisplayName("test types that never hold a level")
    class NeverQuotable {

        @Test
        @DisplayName("a smoke test establishes no capacity, and says why")
        void smokeTestsEstablishNothing() {
            var plan = Fixtures.plan(TestType.SMOKE,
                    ramp(new Stage(RequestsPerSecond.of(5), Duration.ofSeconds(30))));

            var capacity = calculate(plan, List.of(stage(5, 5, List.of(), List.of(pool(10)))),
                    RunQualityAssessment.valid(), null);

            assertThat(capacity.isEstablished()).isFalse();
            assertThat(capacity.refusal()).contains("never establishes a capacity");
        }

        @Test
        @DisplayName("a spike test's subject is arrival, not a held level")
        void spikeTestsEstablishNothing() {
            var plan = Fixtures.plan(TestType.SPIKE, ramp(
                    new Stage(RequestsPerSecond.of(400), Duration.ofSeconds(10)),
                    new Stage(RequestsPerSecond.of(400), Duration.ofMinutes(5))));

            var capacity = calculate(plan, List.of(stage(400, 399, List.of(), List.of(pool(10)))),
                    RunQualityAssessment.valid(), null);

            assertThat(capacity.isEstablished()).isFalse();
            assertThat(capacity.refusal()).contains("no level is held");
        }
    }

    @Nested
    @DisplayName("a ramp is not a hold")
    class RampsAreNotHolds {

        @Test
        @DisplayName("a level a ramp passed through was never held, however long the ramp")
        void aRampDoesNotCount() {
            // Fifteen seconds of a ramp and ten minutes at a plateau are not the same evidence:
            // JIT, pool growth, cache fill and the first collection all land in the first minute.
            var rampOnly = Fixtures.plan(TestType.AVERAGE_LOAD, ramp(
                    new Stage(RequestsPerSecond.of(100), Duration.ofMinutes(10)),
                    new Stage(RequestsPerSecond.of(200), Duration.ofMinutes(10))));

            var capacity = calculate(rampOnly,
                    List.of(stage(200, 199, List.of(), List.of(pool(30)))),
                    RunQualityAssessment.valid(), null);

            // Every stage changes target, so nothing was held: crediting the climb to the level it
            // ended at is exactly the confusion this condition exists to catch.
            assertThat(capacity.isEstablished()).isFalse();
            assertThat(capacity.refusal()).contains("Held for 0 ms").contains("an average-load test");
        }
    }

    @Test
    @DisplayName("the highest level that passed is carried beneath, even when there is no capacity")
    void theHighestPassingLevelSurvivesTheRefusal() {
        var capacity = calculate(heldFor(Duration.ofMinutes(2)),
                List.of(stage(200, 199, List.of(), List.of(pool(30)))),
                RunQualityAssessment.valid(), null);

        // Both figures always appear. The one that passed keeps its name and its meaning; it simply
        // is not the headline, and is explicitly not a capacity claim.
        assertThat(capacity.isEstablished()).isFalse();
        assertThat(capacity.highestLevelThatPassedIfPresent())
                .hasValueSatisfying(level -> assertThat(level.asDouble()).isEqualTo(200));
    }
}
