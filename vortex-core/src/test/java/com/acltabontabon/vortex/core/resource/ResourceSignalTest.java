package com.acltabontabon.vortex.core.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acltabontabon.vortex.core.analysis.ResourcePressure;
import com.acltabontabon.vortex.core.metrics.Aggregation;
import com.acltabontabon.vortex.core.metrics.MetricObservation;
import com.acltabontabon.vortex.core.metrics.MetricSource;
import com.acltabontabon.vortex.core.metrics.MetricUnit;
import com.acltabontabon.vortex.core.metrics.TimeWindow;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A resource is near its limit when it is near <em>its limit</em>, not when its unit is percent.
 *
 * <p>The rule this replaces decided pressure from {@code unit == PERCENT && value >= 90}, which is a
 * statement about how a number happens to be written down. These tests pin both directions of the
 * correction: things that used to qualify and should not, and things that did not and should.
 */
class ResourceSignalTest {

    private static final TimeWindow WINDOW = new TimeWindow(
            Instant.parse("2026-08-21T10:00:00Z"), Instant.parse("2026-08-21T10:10:00Z"));

    private static MetricObservation observation(String id, MetricUnit unit, double value) {
        return MetricObservation.of("metric:" + id, id, MetricSource.PROMETHEUS, unit,
                Aggregation.MAX, value, WINDOW);
    }

    @Nested
    @DisplayName("the bug ADR-037 exists to fix")
    class TheOldRule {

        @Test
        @DisplayName("an error rate of 92% can never become resource pressure")
        void anErrorRateIsNotAResource() {
            // The headline complaint. Under the old rule this was indistinguishable from a CPU at
            // 92%, and would have been reported as a resource near its limit beside a capacity
            // figure. It is not a resource at all, so there is no signal to ask about — and that is
            // the point: the type system, not a caller's diligence, is what prevents it.
            var errorRate = observation("http.errorRate", MetricUnit.PERCENT, 92);

            assertThat(errorRate.unit()).isEqualTo(MetricUnit.PERCENT);
            assertThat(errorRate.value()).isGreaterThan(90);
            // There is deliberately no ResourcePressure overload that would accept it.
            assertThat(ResourcePressure.isUnderPressure(null)).isFalse();
        }

        @Test
        @DisplayName("a heap at 3.9 GB of a 4 GB limit is at its limit, though it is not a percentage")
        void bytesAgainstAPublishedLimitCount() {
            var heap = new ResourceSignal(
                    observation("jvm.memory.used", MetricUnit.BYTES, 3.9e9),
                    ResourceKind.RUNTIME_MEMORY, ResourceScope.SYSTEM_UNDER_TEST,
                    ResourceLimit.published(4.0e9, MetricUnit.BYTES, "the JVM's maximum heap"));

            assertThat(ResourcePressure.isUnderPressure(heap)).isTrue();
            assertThat(heap.utilisation()).hasValueSatisfying(
                    used -> assertThat(used).isGreaterThan(0.95));
        }
    }

    @Nested
    @DisplayName("a limit nobody published is not a limit")
    class WithoutALimit {

        private final ResourceSignal unbounded = ResourceSignal.unbounded(
                observation("executor.queued", MetricUnit.COUNT, 4_000),
                ResourceKind.QUEUE, ResourceScope.SYSTEM_UNDER_TEST);

        @Test
        @DisplayName("utilisation is absent rather than computed against a guessed denominator")
        void utilisationIsAbsent() {
            assertThat(unbounded.limitIfPresent()).isEmpty();
            assertThat(unbounded.utilisation()).isEmpty();
        }

        @Test
        @DisplayName("it is never at a limit, however large the number")
        void itIsNeverAtItsLimit() {
            assertThat(unbounded.isAtItsLimit()).isFalse();
            assertThat(ResourcePressure.isUnderPressure(unbounded)).isFalse();
            assertThat(unbounded.canEstablishAServiceLimit()).isFalse();
        }

        @Test
        @DisplayName("it still describes itself honestly, rather than going quiet")
        void itStillDescribesItself() {
            assertThat(unbounded.describe()).contains("against no published limit");
        }
    }

    @Nested
    @DisplayName("whose resource it is decides what may be said about it")
    class Scope {

        private ResourceSignal cpuAt(ResourceScope scope) {
            return new ResourceSignal(observation("system.cpu.utilization", MetricUnit.PERCENT, 99),
                    ResourceKind.CPU, scope, ResourceLimit.inherentToPercentage());
        }

        @Test
        @DisplayName("the service's own CPU at its limit may constrain the service")
        void theServiceCanBeConstrained() {
            assertThat(ResourcePressure.constrainsTheServiceUnderTest(
                    cpuAt(ResourceScope.SYSTEM_UNDER_TEST))).isTrue();
        }

        @Test
        @DisplayName("the load generator's CPU at its limit is under pressure and constrains nothing")
        void theGeneratorConstrainsNothing() {
            var generator = cpuAt(ResourceScope.LOAD_GENERATOR);

            // Both halves matter. Vortex must notice its own machine is pinned — that is what
            // GENERATOR_SATURATED will rest on — and must never report it as the service's limit.
            assertThat(ResourcePressure.isUnderPressure(generator)).isTrue();
            assertThat(ResourcePressure.constrainsTheServiceUnderTest(generator)).isFalse();
            assertThat(generator.canEstablishAServiceLimit()).isFalse();
        }

        @Test
        @DisplayName("a dependency's resource is not the service's either")
        void aDependencyConstrainsNothing() {
            assertThat(ResourcePressure.constrainsTheServiceUnderTest(
                    cpuAt(ResourceScope.DEPENDENCY))).isFalse();
        }
    }

    @Nested
    @DisplayName("a limit expressed in the wrong unit is not silently ignored")
    class Units {

        @Test
        @DisplayName("a ratio measured against a percentage limit yields no utilisation")
        void mismatchedUnitsDoNotDivide() {
            // Micrometer publishes system.cpu.usage as a ratio, and a hundred-percent limit against
            // it would put a CPU at 0.99 nowhere near "its limit" of 100. Refusing to divide is the
            // honest answer; quietly dividing would produce 0.0099 and a resource that can never be
            // found at its limit.
            var mismatched = new ResourceSignal(
                    observation("system.cpu.usage", MetricUnit.RATIO, 0.99),
                    ResourceKind.CPU, ResourceScope.SYSTEM_UNDER_TEST,
                    ResourceLimit.inherentToPercentage());

            assertThat(mismatched.utilisation()).isEmpty();
            assertThat(mismatched.isAtItsLimit()).isFalse();
        }

        @Test
        @DisplayName("the inherent limit follows the unit the provider actually used")
        void inherentLimitsFollowTheUnit() {
            var ratio = new ResourceSignal(
                    observation("system.cpu.usage", MetricUnit.RATIO, 0.99),
                    ResourceKind.CPU, ResourceScope.SYSTEM_UNDER_TEST,
                    ResourceLimit.inherentTo(MetricUnit.RATIO));

            assertThat(ratio.utilisation()).hasValueSatisfying(
                    used -> assertThat(used).isGreaterThan(0.98));
            assertThat(ResourcePressure.isUnderPressure(ratio)).isTrue();
        }

        @Test
        @DisplayName("units that carry no inherent limit are given none")
        void unitsWithoutAnInherentLimitGetNone() {
            assertThat(ResourceLimit.inherentTo(MetricUnit.BYTES)).isNull();
            assertThat(ResourceLimit.inherentTo(MetricUnit.COUNT)).isNull();
            assertThat(ResourceLimit.inherentTo(MetricUnit.MILLISECONDS)).isNull();
        }
    }

    @Test
    @DisplayName("a limit of zero is refused, because everything would be at it")
    void aZeroLimitIsRefused() {
        assertThatThrownBy(() -> ResourceLimit.published(0, MetricUnit.BYTES, "nothing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    @DisplayName("a limit Vortex configured itself carries VORTEX_CONFIGURED, not PUBLISHED_BY_PROVIDER")
    void vortexConfiguredCarriesItsOwnBasis() {
        ResourceLimit limit = ResourceLimit.vortexConfigured(0.5, MetricUnit.RATIO,
                "the container's configured CPU limit");

        assertThat(limit.value()).isEqualTo(0.5);
        assertThat(limit.unit()).isEqualTo(MetricUnit.RATIO);
        assertThat(limit.basis()).isEqualTo(LimitBasis.VORTEX_CONFIGURED);
        assertThat(limit.describedAs()).isEqualTo("the container's configured CPU limit");
    }
}
