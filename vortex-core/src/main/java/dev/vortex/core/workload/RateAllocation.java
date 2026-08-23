package dev.vortex.core.workload;

import dev.vortex.core.shared.OperationId;
import dev.vortex.core.shared.RequestsPerSecond;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of dividing one total arrival rate across an operation mix.
 *
 * @param requestedTotal the total the user asked for
 * @param allocatedTotal the sum of the per-operation rates after rounding; may differ from
 *                       {@code requestedTotal} by at most one rounding unit per operation
 * @param allocations    per-operation rates, in mix order
 */
public record RateAllocation(
        RequestsPerSecond requestedTotal,
        RequestsPerSecond allocatedTotal,
        List<AllocatedRate> allocations) {

    public RateAllocation {
        Objects.requireNonNull(requestedTotal, "requestedTotal");
        Objects.requireNonNull(allocatedTotal, "allocatedTotal");
        allocations = List.copyOf(Objects.requireNonNull(allocations, "allocations"));
    }

    public Optional<AllocatedRate> forOperation(OperationId operationId) {
        return allocations.stream().filter(a -> a.operationId().equals(operationId)).findFirst();
    }

    /**
     * The absolute difference between what was requested and what could be allocated.
     *
     * <p>Non-zero values are normal and small: they come from rounding each operation's share to the
     * rate granularity the load generator can actually schedule.
     */
    public BigDecimal roundingDrift() {
        return allocatedTotal.value().subtract(requestedTotal.value()).abs();
    }

    public boolean isExact() {
        return roundingDrift().signum() == 0;
    }
}
