package dev.vortex.core.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vortex.core.shared.OperationId;
import dev.vortex.core.shared.RequestsPerSecond;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The allocator is the guard against Vortex's most damaging possible modelling error: running a
 * traffic mix where each operation is driven at the full total rate.
 */
class RateAllocatorTest {

    private final RateAllocator allocator = new RateAllocator();

    private static final OperationId LOOKUP = OperationId.of("lookup");
    private static final OperationId REPAYMENT = OperationId.of("repayment");
    private static final OperationId CANCELLATION = OperationId.of("cancellation");

    private static OperationMix mix60_30_10() {
        return OperationMix.of(List.of(
                WeightedOperation.of(LOOKUP, 60),
                WeightedOperation.of(REPAYMENT, 30),
                WeightedOperation.of(CANCELLATION, 10)));
    }

    private static double rateOf(RateAllocation allocation, OperationId operationId) {
        return allocation.forOperation(operationId).orElseThrow().rate().asDouble();
    }

    @Nested
    @DisplayName("splits the total rather than repeating it")
    class SplitsTheTotal {

        @Test
        void allocatesEachOperationItsShareOfTheTotal() {
            RateAllocation allocation = allocator.allocate(RequestsPerSecond.of(100), mix60_30_10());

            assertThat(rateOf(allocation, LOOKUP)).isEqualTo(60.0);
            assertThat(rateOf(allocation, REPAYMENT)).isEqualTo(30.0);
            assertThat(rateOf(allocation, CANCELLATION)).isEqualTo(10.0);
        }

        @Test
        void neverGivesAnyOperationTheFullTotal() {
            RateAllocation allocation = allocator.allocate(RequestsPerSecond.of(100), mix60_30_10());

            assertThat(allocation.allocations())
                    .allSatisfy(a -> assertThat(a.rate().asDouble()).isLessThan(100.0));
        }

        @Test
        void handlesTheDocumentedFractionalExample() {
            RateAllocation allocation = allocator.allocate(RequestsPerSecond.of(67), mix60_30_10());

            assertThat(rateOf(allocation, LOOKUP)).isEqualTo(40.2);
            assertThat(rateOf(allocation, REPAYMENT)).isEqualTo(20.1);
            assertThat(rateOf(allocation, CANCELLATION)).isEqualTo(6.7);
        }

        @Test
        void singleOperationReceivesTheEntireTotal() {
            RateAllocation allocation =
                    allocator.allocate(RequestsPerSecond.of(42.5), OperationMix.single(LOOKUP));

            assertThat(allocation.allocations()).hasSize(1);
            assertThat(rateOf(allocation, LOOKUP)).isEqualTo(42.5);
            assertThat(allocation.isExact()).isTrue();
        }
    }

    @Nested
    @DisplayName("preserves the requested total")
    class PreservesTotal {

        @ParameterizedTest
        @ValueSource(doubles = {0.5, 1, 7, 20, 67, 99.999, 100, 143.7, 1000, 12345.678})
        void allocatedRatesSumToTheRequestedTotal(double total) {
            RateAllocation allocation = allocator.allocate(RequestsPerSecond.of(total), mix60_30_10());

            BigDecimal sum = allocation.allocations().stream()
                    .map(a -> a.rate().value())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(sum).isEqualByComparingTo(BigDecimal.valueOf(total));
            assertThat(allocation.isExact()).isTrue();
        }

        @Test
        void indivisibleThirdsStillSumToTheTotal() {
            OperationMix evenThirds = OperationMix.of(List.of(
                    WeightedOperation.of(LOOKUP, 1),
                    WeightedOperation.of(REPAYMENT, 1),
                    WeightedOperation.of(CANCELLATION, 1)));

            RateAllocation allocation = allocator.allocate(RequestsPerSecond.of(10), evenThirds);

            BigDecimal sum = allocation.allocations().stream()
                    .map(a -> a.rate().value())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(sum).isEqualByComparingTo(BigDecimal.TEN);
            // 3.334 / 3.333 / 3.333 — the leftover unit goes to the largest remainder.
            assertThat(allocation.allocations())
                    .allSatisfy(a -> assertThat(a.rate().asDouble()).isBetween(3.333, 3.334));
        }

        @Test
        void manyOperationsStillSumToTheTotal() {
            List<WeightedOperation> entries = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                entries.add(WeightedOperation.of(OperationId.of("operation" + i), i + 1));
            }

            RateAllocation allocation =
                    allocator.allocate(RequestsPerSecond.of(333.333), OperationMix.of(entries));

            BigDecimal sum = allocation.allocations().stream()
                    .map(a -> a.rate().value())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            assertThat(sum).isEqualByComparingTo(BigDecimal.valueOf(333.333));
            assertThat(allocation.allocations()).hasSize(25);
        }
    }

    @Nested
    @DisplayName("weights are relative, not percentages")
    class RelativeWeights {

        @Test
        void equivalentRatiosProduceIdenticalAllocations() {
            OperationMix asPercentages = mix60_30_10();
            OperationMix asSmallIntegers = OperationMix.of(List.of(
                    WeightedOperation.of(LOOKUP, 6),
                    WeightedOperation.of(REPAYMENT, 3),
                    WeightedOperation.of(CANCELLATION, 1)));

            RateAllocation a = allocator.allocate(RequestsPerSecond.of(200), asPercentages);
            RateAllocation b = allocator.allocate(RequestsPerSecond.of(200), asSmallIntegers);

            assertThat(a.allocations()).isEqualTo(b.allocations());
        }

        @Test
        void weightsNeedNotSumToOneHundred() {
            OperationMix odd = OperationMix.of(List.of(
                    WeightedOperation.of(LOOKUP, 7),
                    WeightedOperation.of(REPAYMENT, 3)));

            RateAllocation allocation = allocator.allocate(RequestsPerSecond.of(50), odd);

            assertThat(rateOf(allocation, LOOKUP)).isEqualTo(35.0);
            assertThat(rateOf(allocation, REPAYMENT)).isEqualTo(15.0);
        }

        @Test
        void reportsEachOperationShareAsAFraction() {
            RateAllocation allocation = allocator.allocate(RequestsPerSecond.of(100), mix60_30_10());

            assertThat(allocation.forOperation(LOOKUP).orElseThrow().share())
                    .isEqualByComparingTo("0.6");
            assertThat(allocation.forOperation(CANCELLATION).orElseThrow().share())
                    .isEqualByComparingTo("0.1");
        }
    }

    @Nested
    @DisplayName("very low arrival rates")
    class LowRates {

        @Test
        void everyOperationGetsAtLeastOneRateUnit() {
            RateAllocation allocation = allocator.allocate(RequestsPerSecond.of(0.005), mix60_30_10());

            assertThat(allocation.allocations())
                    .allSatisfy(a -> assertThat(a.rate().asDouble()).isGreaterThan(0.0));
        }

        @Test
        void refusesToSilentlyDropAnOperationWhenTheTotalIsTooSmall() {
            assertThatThrownBy(() -> allocator.allocate(RequestsPerSecond.of(0.002), mix60_30_10()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be split across 3 operations")
                    .hasMessageContaining("Raise the total arrival rate");
        }
    }

    @Nested
    @DisplayName("invalid input is rejected at the type level")
    class InvalidInput {

        @Test
        void zeroWeightIsRejected() {
            assertThatThrownBy(() -> WeightedOperation.of(LOOKUP, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("weight must be greater than 0");
        }

        @Test
        void negativeWeightIsRejected() {
            assertThatThrownBy(() -> WeightedOperation.of(LOOKUP, -5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("weight must be greater than 0");
        }

        @Test
        @DisplayName("a zero rate is a valid observation but not a valid workload")
        void zeroArrivalRateIsRejectedByTheWorkload() {
            // Zero is representable, because observing no throughput is a real measurement. It is
            // the workload that has no use for it: traffic nobody generates produces no evidence.
            assertThat(RequestsPerSecond.of(0).isPositive()).isFalse();

            assertThatThrownBy(() -> dev.vortex.core.workload.ConstantArrivalRateShape.of(
                    0, java.time.Duration.ofMinutes(1)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("more than 0 requests/sec");
        }

        @Test
        void negativeTotalArrivalRateIsRejected() {
            assertThatThrownBy(() -> RequestsPerSecond.of(-5))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not be negative");
        }

        @Test
        void emptyOperationMixIsRejected() {
            assertThatThrownBy(() -> OperationMix.of(List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one operation");
        }

        @Test
        void duplicateOperationInOperationMixIsRejected() {
            assertThatThrownBy(() -> OperationMix.of(List.of(
                    WeightedOperation.of(LOOKUP, 60),
                    WeightedOperation.of(LOOKUP, 40))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("only once");
        }
    }
}
