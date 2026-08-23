package com.acltabontabon.vortex.core.resource;

import com.acltabontabon.vortex.core.target.CpuAllocation;
import com.acltabontabon.vortex.core.target.MemoryAllocation;
import com.acltabontabon.vortex.core.target.ResourceEnvelopeRequest;
import java.util.Objects;

/**
 * How much CPU and memory Vortex allows the load generator to use.
 *
 * <p>{@link BudgetMode#AUTOMATIC} names an intent, not a number — the concrete figure a run actually
 * uses is computed once, at resolution time, from the host that run executes on (see
 * {@link AutomaticLoadGeneratorAllocation}). {@link BudgetMode#CUSTOM} is an explicit envelope an
 * advanced user configured directly.
 *
 * <p>Reuses {@link ResourceEnvelopeRequest} — the same type an {@code ExecutionTarget} already
 * carries for the system under test's resources — rather than a generator-specific value type, since
 * "cpu and memory, independently optional" is exactly the same shape. This type lives beside {@link
 * ResourceScope}/{@link LimitBasis} rather than in {@code com.acltabontabon.vortex.core.target}, because it is not
 * a target concept: it governs the load generator, which is not the thing being tested.
 */
public record LoadGeneratorResourceBudget(BudgetMode mode, ResourceEnvelopeRequest envelope) {

    /** Whether Vortex decides the budget, or an advanced user configured it directly. */
    public enum BudgetMode {
        AUTOMATIC, CUSTOM
    }

    public LoadGeneratorResourceBudget {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(envelope, "envelope");
        if (mode == BudgetMode.AUTOMATIC && !envelope.isEmpty()) {
            throw new IllegalArgumentException(
                    "an automatic budget names an intent, not a number - it must carry no envelope; "
                            + "the concrete figure belongs on a ResolvedLoadGeneratorBudget instead");
        }
        if (mode == BudgetMode.CUSTOM
                && (envelope.cpuIfPresent().isEmpty() || envelope.memoryIfPresent().isEmpty())) {
            throw new IllegalArgumentException(
                    "a custom budget must configure both cpu and memory - a generator constrained on "
                            + "only one axis is simply free to exhaust the other");
        }
    }

    /** Vortex decides the budget from the host it runs on, each time a run resolves it. */
    public static LoadGeneratorResourceBudget automatic() {
        return new LoadGeneratorResourceBudget(BudgetMode.AUTOMATIC, ResourceEnvelopeRequest.none());
    }

    /** An advanced user's explicit CPU/memory budget. */
    public static LoadGeneratorResourceBudget custom(CpuAllocation cpu, MemoryAllocation memory) {
        return new LoadGeneratorResourceBudget(BudgetMode.CUSTOM,
                new ResourceEnvelopeRequest(cpu, memory));
    }
}
