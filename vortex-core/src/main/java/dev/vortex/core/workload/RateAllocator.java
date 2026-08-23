package dev.vortex.core.workload;

import dev.vortex.core.shared.OperationId;
import dev.vortex.core.shared.RequestsPerSecond;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Divides a workload's single total arrival rate across the operations in a mix.
 *
 * <p>This is the only way an {@link AllocatedRate} can be created, which makes the dangerous
 * reading — "each operation runs at the total rate" — structurally impossible to express.
 *
 * <p>Allocation applies to {@link WorkloadModel#OPEN} workloads only. Under a closed workload the
 * quantity being divided would be virtual users, and a virtual user's throughput depends on the
 * latency of the operation it calls — so an even split of VUs is not an even split of traffic, and
 * the gap widens exactly as the service degrades. Vortex therefore does not offer a multi-operation
 * mix for closed workloads at all rather than allocate a number that would quietly mean something
 * other than what it says.
 *
 * <h2>Rounding policy</h2>
 * Rates are rounded to three decimal places, which is the finest granularity the load generator can
 * meaningfully schedule. Naive per-operation rounding drifts away from the requested total, so
 * allocation uses the <em>largest remainder</em> method:
 *
 * <ol>
 *   <li>compute each operation's exact share of the total;</li>
 *   <li>round every share <em>down</em> to the rate unit;</li>
 *   <li>distribute the leftover units one at a time to the operations with the largest discarded
 *       remainder, breaking ties by larger weight and then by mix order.</li>
 * </ol>
 *
 * <p>The sum of the allocated rates therefore equals the requested total exactly whenever the total
 * is itself expressible in rate units — which, given both are held to three decimals, is always.
 *
 * <h2>Minimum rate</h2>
 * Every operation in a mix receives a strictly positive rate. If the total is too small to give
 * every operation at least one rate unit — for example 0.002/sec split three ways — allocation fails
 * with a message that says so, rather than silently dropping an operation from the test.
 *
 * <p>Example: a total of 67 requests/sec across a 60/30/10 mix allocates 40.2, 20.1 and 6.7.
 */
public final class RateAllocator {

    /** Smallest schedulable rate increment, matching {@link RequestsPerSecond#SCALE}. */
    private static final BigDecimal UNIT = RequestsPerSecond.UNIT;

    public RateAllocation allocate(RequestsPerSecond total, OperationMix mix) {
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(mix, "mix");

        List<WeightedOperation> entries = mix.entries();
        BigDecimal totalUnits = total.value().divide(UNIT, 0, RoundingMode.HALF_UP);
        long units = totalUnits.longValueExact();
        long totalWeight = mix.totalWeight();

        if (units < entries.size()) {
            throw new IllegalArgumentException(
                    "a total of " + total.display() + " requests/sec cannot be split across "
                            + entries.size() + " operations: every operation must receive at least "
                            + UNIT.toPlainString() + "/sec. Raise the total arrival rate or reduce the "
                            + "number of operations in the mix.");
        }

        record Candidate(int index, WeightedOperation entry, long floorUnits, BigDecimal remainder) {
        }

        List<Candidate> candidates = new ArrayList<>(entries.size());
        long assigned = 0;
        for (int i = 0; i < entries.size(); i++) {
            WeightedOperation entry = entries.get(i);
            BigDecimal exact = BigDecimal.valueOf(units)
                    .multiply(BigDecimal.valueOf(entry.weight().value()))
                    .divide(BigDecimal.valueOf(totalWeight), MathContext.DECIMAL64);
            long floor = exact.setScale(0, RoundingMode.FLOOR).longValueExact();
            // Guarantee every operation is actually exercised.
            floor = Math.max(floor, 1L);
            candidates.add(new Candidate(i, entry, floor, exact.subtract(BigDecimal.valueOf(floor))));
            assigned += floor;
        }

        long leftover = units - assigned;
        long[] finalUnits = new long[entries.size()];
        for (Candidate c : candidates) {
            finalUnits[c.index()] = c.floorUnits();
        }

        if (leftover > 0) {
            List<Candidate> byRemainder = new ArrayList<>(candidates);
            byRemainder.sort(Comparator
                    .comparing(Candidate::remainder, Comparator.reverseOrder())
                    .thenComparing((Candidate c) -> c.entry().weight().value(), Comparator.reverseOrder())
                    .thenComparingInt(Candidate::index));
            for (int i = 0; i < leftover; i++) {
                finalUnits[byRemainder.get(i % byRemainder.size()).index()]++;
            }
        } else if (leftover < 0) {
            // Only reachable when the minimum-one-unit floor pushed the sum above the total.
            List<Candidate> bySmallestRemainder = new ArrayList<>(candidates);
            bySmallestRemainder.sort(Comparator
                    .comparing(Candidate::remainder)
                    .thenComparingInt(Candidate::index));
            long toReclaim = -leftover;
            int cursor = 0;
            while (toReclaim > 0) {
                int idx = bySmallestRemainder.get(cursor % bySmallestRemainder.size()).index();
                if (finalUnits[idx] > 1) {
                    finalUnits[idx]--;
                    toReclaim--;
                }
                cursor++;
                if (cursor > bySmallestRemainder.size() * (units + 1)) {
                    break;
                }
            }
        }

        List<AllocatedRate> allocations = new ArrayList<>(entries.size());
        BigDecimal allocatedTotal = BigDecimal.ZERO;
        for (int i = 0; i < entries.size(); i++) {
            BigDecimal rate = BigDecimal.valueOf(finalUnits[i]).multiply(UNIT);
            allocatedTotal = allocatedTotal.add(rate);
            allocations.add(new AllocatedRate(
                    entries.get(i).operationId(),
                    new RequestsPerSecond(rate),
                    BigDecimal.valueOf(entries.get(i).weight().value())
                            .divide(BigDecimal.valueOf(totalWeight), 6, RoundingMode.HALF_UP)));
        }

        return new RateAllocation(total, new RequestsPerSecond(allocatedTotal), allocations);
    }

    /** Convenience for the common single-operation case. */
    public RateAllocation allocate(RequestsPerSecond total, OperationId onlyOperation) {
        return allocate(total, OperationMix.single(onlyOperation));
    }
}
