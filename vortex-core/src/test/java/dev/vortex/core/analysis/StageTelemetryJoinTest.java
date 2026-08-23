package dev.vortex.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.metrics.Aggregation;
import dev.vortex.core.metrics.MetricObservation;
import dev.vortex.core.metrics.MetricSource;
import dev.vortex.core.metrics.MetricUnit;
import dev.vortex.core.metrics.StageTelemetry;
import dev.vortex.core.metrics.TelemetryAvailability;
import dev.vortex.core.metrics.TelemetryGap;
import dev.vortex.core.metrics.TimeWindow;
import dev.vortex.core.resource.ResourceKind;
import dev.vortex.core.resource.ResourceLimit;
import dev.vortex.core.resource.ResourceScope;
import dev.vortex.core.resource.ResourceSignal;
import dev.vortex.core.shared.ErrorRate;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.workload.StageWindowBasis;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Joining what the service said about itself to what the load generator was doing at the time.
 *
 * <p>Absence is the interesting half. A stage with no telemetry must read as a stage nobody measured,
 * never as a stage measured at zero — a connection pool reported at 0% because nothing was collected
 * would send an engineer looking somewhere else entirely.
 */
class StageTelemetryJoinTest {

    private static final Instant START = Instant.parse("2026-08-21T10:00:00Z");

    private static MetricObservation pool(double percent) {
        return MetricObservation.of("metric:pool.connections.utilization",
                "Connection pool utilisation", MetricSource.ACTUATOR, MetricUnit.PERCENT,
                Aggregation.MAX, percent, new TimeWindow(START, START.plusSeconds(300)));
    }

    @Nested
    @DisplayName("a stage carrying the service's own view")
    class WithSignals {

        private final StageObservation stage = new StageObservation(
                RequestsPerSecond.of(100), RequestsPerSecond.of(98), Duration.ofMillis(210),
                ErrorRate.ZERO, 12, List.of(), List.of(pool(94)), StageWindowBasis.OBSERVED);

        @Test
        void reportsThatItHasOne() {
            assertThat(stage.hasSignals()).isTrue();
        }

        @Test
        void letsAFindingCiteASignalById() {
            assertThat(stage.signal("metric:pool.connections.utilization"))
                    .hasValueSatisfying(signal -> assertThat(signal.value()).isEqualTo(94));
        }

        @Test
        void aSignalItDoesNotCarryIsAbsentRatherThanZero() {
            assertThat(stage.signal("metric:jvm.memory.utilization")).isEmpty();
        }
    }

    @Nested
    @DisplayName("a stage nobody measured")
    class WithoutSignals {

        private final StageObservation stage = new StageObservation(
                RequestsPerSecond.of(100), RequestsPerSecond.of(98), Duration.ofMillis(210),
                ErrorRate.ZERO, 12, List.of());

        @Test
        void carriesNoSignalsAtAll() {
            assertThat(stage.hasSignals()).isFalse();
            assertThat(stage.signals()).isEmpty();
        }

        @Test
        @DisplayName("still reports what the load generator saw")
        void keepsTheClientSideView() {
            // The absent server-side view never invalidates the client-side one. A run with no
            // telemetry is still a run.
            assertThat(stage.achievedRateIfPresent()).isPresent();
            assertThat(stage.isCompliant()).isTrue();
        }

        @Test
        void defaultsToTheCautiousBasis() {
            assertThat(stage.basis()).isEqualTo(StageWindowBasis.DERIVED_FROM_PLAN);
            assertThat(stage.supportsStrongerEvidence()).isFalse();
        }
    }

    @Nested
    @DisplayName("what the alignment basis permits")
    class Basis {

        @Test
        void measuredBoundariesMayStrengthenAFinding() {
            var measured = new StageObservation(RequestsPerSecond.of(100), null, null,
                    ErrorRate.ZERO, 1, List.of(), List.of(pool(94)), StageWindowBasis.OBSERVED);

            assertThat(measured.supportsStrongerEvidence()).isTrue();
        }

        @Test
        @DisplayName("computed boundaries never do, however well they line up")
        void computedBoundariesNeverDo() {
            // Overlapping timestamps that Vortex generated from planned durations are its own
            // arithmetic, not independent corroboration. Letting them raise confidence would
            // manufacture certainty rather than establish it.
            var computed = new StageObservation(RequestsPerSecond.of(100), null, null,
                    ErrorRate.ZERO, 1, List.of(), List.of(pool(99)),
                    StageWindowBasis.DERIVED_FROM_PLAN);

            assertThat(computed.supportsStrongerEvidence()).isFalse();
        }

        @Test
        void andStageTelemetryAnswersTheSameQuestionTheSameWay() {
            var derived = new StageTelemetry(0, new TimeWindow(START, START.plusSeconds(60)),
                    StageWindowBasis.DERIVED_FROM_PLAN, List.of(pool(99)));
            var observed = new StageTelemetry(0, new TimeWindow(START, START.plusSeconds(60)),
                    StageWindowBasis.OBSERVED, List.of(pool(99)));

            assertThat(derived.supportsStrongerEvidence()).isFalse();
            assertThat(observed.supportsStrongerEvidence()).isTrue();
        }
    }

    @Nested
    @DisplayName("resource pressure, and how strongly it is supported")
    class Pressure {

        /** The pool, classified as a provider would classify it. */
        private ResourceSignal typedPool(double percent) {
            return new ResourceSignal(pool(percent), ResourceKind.POOL,
                    ResourceScope.SYSTEM_UNDER_TEST, ResourceLimit.inherentToPercentage());
        }

        @Test
        void aSignalAtItsLimitIsUnderPressure() {
            assertThat(ResourcePressure.isUnderPressure(typedPool(94))).isTrue();
            assertThat(ResourcePressure.isUnderPressure(typedPool(40))).isFalse();
        }

        @Test
        @DisplayName("a measurement no provider classified cannot be under pressure at all")
        void anUnclassifiedMeasurementIsNotAResource() {
            // This used to assert that a millisecond reading was not a utilisation, which was a
            // statement about its unit. The rule is now about classification: a downstream latency
            // is not a resource of the service under test whatever unit it arrives in, and there is
            // no longer an overload that would let a caller ask.
            var latency = MetricObservation.of("metric:dependency.latency.p95", "Downstream p95",
                    MetricSource.PROMETHEUS, MetricUnit.MILLISECONDS, Aggregation.MAX, 4000,
                    new TimeWindow(START, START.plusSeconds(60)));

            assertThat(new StageObservation(RequestsPerSecond.of(100), null, null, null, 10,
                    List.of(), List.of(latency), StageWindowBasis.OBSERVED)
                    .serviceResourceSignals()).isEmpty();
        }

        @Test
        void crossingAtTheBreakpointOnMeasuredBoundariesIsTheStrongestCase() {
            assertThat(ResourcePressure.strength(true, true, StageWindowBasis.OBSERVED))
                    .isEqualTo(EvidenceStrength.HIGH);
        }

        @Test
        @DisplayName("the same crossing on computed boundaries is capped")
        void crossingOnComputedBoundariesIsCapped() {
            assertThat(ResourcePressure.strength(true, true, StageWindowBasis.DERIVED_FROM_PLAN))
                    .isEqualTo(EvidenceStrength.MEDIUM);
        }

        @Test
        void aBarePeakWithNoMovementIsWeakest() {
            assertThat(ResourcePressure.strength(false, false, StageWindowBasis.OBSERVED))
                    .isEqualTo(EvidenceStrength.LOW);
        }
    }

    @Nested
    @DisplayName("a gap explains itself")
    class Gaps {

        @Test
        void namesTheMetricAndTheCause() {
            var gap = new TelemetryGap("prometheus", "jvm.memory.used",
                    TelemetryAvailability.NO_DATA, "the query matched no series");

            assertThat(gap.describe())
                    .contains("jvm.memory.used")
                    .contains("prometheus")
                    .contains("no samples")
                    .contains("the query matched no series");
        }

        @Test
        void distinguishesARefusedTokenFromAnEmptyResult() {
            var refused = TelemetryGap.of("dynatrace", "builtin:host.cpu.usage",
                    TelemetryAvailability.UNAUTHORIZED);
            var empty = TelemetryGap.of("dynatrace", "builtin:host.cpu.usage",
                    TelemetryAvailability.NO_DATA);

            assertThat(refused.describe()).isNotEqualTo(empty.describe());
            assertThat(refused.describe()).contains("rejected the credentials");
        }

        @Test
        @DisplayName("cannot describe something that was in fact observed")
        void cannotBeConstructedForAnAvailableMetric() {
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            TelemetryGap.of("actuator", "system.cpu.usage",
                                    TelemetryAvailability.AVAILABLE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("describes something that is missing");
        }
    }
}
