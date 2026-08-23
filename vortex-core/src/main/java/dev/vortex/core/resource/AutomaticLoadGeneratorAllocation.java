package dev.vortex.core.resource;

import dev.vortex.core.evidence.HostShape;
import dev.vortex.core.target.CpuAllocation;
import dev.vortex.core.target.MemoryAllocation;
import dev.vortex.core.target.ResourceEnvelopeRequest;
import java.util.Objects;

/**
 * Deterministic, conservative CPU/memory allocation for {@link LoadGeneratorResourceBudget.BudgetMode
 * #AUTOMATIC} — a pure function of the host's shape, computed once per resolution, never adaptive or
 * dynamic. One sentence: reserve a floor for the OS and Vortex itself, reserve half of what is left
 * for a colocated system under test when there is one, and give the load generator the smaller of
 * what remains or a sensible cap.
 *
 * <p>CPU and memory are reserved and capped independently, each guarding its own axis so a machine
 * with plentiful cores but little memory (or the reverse) still gets a sane answer on both.
 *
 * <h2>The constants below are product defaults, not universal truths</h2>
 * Every reserve fraction, floor and cap here is a starting point subject to empirical tuning as real
 * machines and real workloads exercise it — not a value derived from anything more principled than
 * "conservative enough to be safe as a default, generous enough to be usable."
 *
 * <h2>A generator allocation is never zero</h2>
 * {@link CpuAllocation}/{@link MemoryAllocation} both refuse a non-positive value by construction, and
 * a load generator that cannot run at all is a worse outcome on a very small host than an optimistic
 * reservation. The reserve computed for the OS is therefore capped so it can never claim the entire
 * host — see {@link #osReserveMillicores}/{@link #osReserveBytes}. This is expected to matter only on
 * toy or deliberately constrained hosts; a human-configured {@link LoadGeneratorResourceBudget
 * .BudgetMode#CUSTOM} budget remains the escape hatch for anything this default cannot reason about
 * well.
 */
public final class AutomaticLoadGeneratorAllocation {

    /** Reserved for the OS and Vortex itself before anything else is considered. */
    static final int OS_RESERVE_MIN_MILLICORES = 1000;
    static final int OS_RESERVE_PERCENT = 15;

    /** What Vortex targets for the generator; see the class Javadoc for when this cannot be honored. */
    static final int GENERATOR_TARGET_MIN_MILLICORES = 500;
    static final int GENERATOR_TARGET_MAX_MILLICORES = 4000;

    static final long OS_RESERVE_MIN_BYTES = 1L << 30; // 1 GiB
    static final int OS_RESERVE_MEMORY_PERCENT = 10;

    static final long GENERATOR_TARGET_MIN_BYTES = 256L << 20; // 256 MiB
    static final long GENERATOR_TARGET_MAX_BYTES = 4L << 30; // 4 GiB

    private AutomaticLoadGeneratorAllocation() {
    }

    /**
     * Resolves {@link LoadGeneratorResourceBudget#automatic()} against {@code host}.
     *
     * @param colocatedManagedSut whether this run's system under test is Vortex-managed and shares
     *                            this host, so half of what is left after the OS reserve is set aside
     *                            for it before the generator's share is computed
     */
    public static ResolvedLoadGeneratorBudget resolve(HostShape host, boolean colocatedManagedSut) {
        Objects.requireNonNull(host, "host");

        Cpu cpu = resolveCpu(host.availableProcessors(), colocatedManagedSut);
        Memory memory = resolveMemory(host.totalMemoryBytes(), colocatedManagedSut);

        return new ResolvedLoadGeneratorBudget(
                LoadGeneratorResourceBudget.BudgetMode.AUTOMATIC,
                new ResourceEnvelopeRequest(
                        CpuAllocation.ofMillicores(cpu.allocationMillicores),
                        new MemoryAllocation(memory.allocationBytes)),
                host,
                reserveEnvelope(cpu.osReserveMillicores, memory.osReserveBytes),
                colocatedManagedSut
                        ? reserveEnvelope(cpu.sutReserveMillicores, memory.sutReserveBytes)
                        : ResourceEnvelopeRequest.none(),
                colocatedManagedSut);
    }

    private static ResourceEnvelopeRequest reserveEnvelope(int cpuMillicores, long memoryBytes) {
        return new ResourceEnvelopeRequest(
                cpuMillicores > 0 ? CpuAllocation.ofMillicores(cpuMillicores) : null,
                memoryBytes > 0 ? new MemoryAllocation(memoryBytes) : null);
    }

    private record Cpu(int osReserveMillicores, int sutReserveMillicores, int allocationMillicores) {
    }

    private record Memory(long osReserveBytes, long sutReserveBytes, long allocationBytes) {
    }

    /**
     * A host reporting zero available processors is one Vortex could not read at all (see {@link
     * HostShape}'s own contract) — there is nothing to reserve a percentage of, so the generator gets
     * the target floor directly and no reserve is reported, rather than computing a percentage of an
     * unknown quantity.
     */
    private static Cpu resolveCpu(int availableProcessors, boolean colocatedManagedSut) {
        if (availableProcessors <= 0) {
            return new Cpu(0, 0, GENERATOR_TARGET_MIN_MILLICORES);
        }

        int total = Math.multiplyExact(availableProcessors, 1000);
        // The OS reserve can never claim the whole host: it is capped so at least one millicore is
        // always left over, which is the absolute floor CpuAllocation itself allows.
        int osReserve = Math.min(
                Math.max(OS_RESERVE_MIN_MILLICORES, ceilPercent(total, OS_RESERVE_PERCENT)),
                total - 1);
        int remainderAfterOs = total - osReserve;

        int sutReserve = colocatedManagedSut ? remainderAfterOs / 2 : 0;
        int uncapped = remainderAfterOs - sutReserve;
        int allocation = clamp(uncapped, GENERATOR_TARGET_MIN_MILLICORES, GENERATOR_TARGET_MAX_MILLICORES);
        // The target-minimum clamp must never claim more than is actually left after reservation —
        // on a very small host this reclaims from the (purely informational) SUT reserve rather than
        // ever exceeding remainderAfterOs, and remainderAfterOs is itself always >= 1 by construction
        // above, so allocation here is always >= 1.
        allocation = Math.min(allocation, remainderAfterOs);

        return new Cpu(osReserve, sutReserve, allocation);
    }

    private static Memory resolveMemory(long totalMemoryBytes, boolean colocatedManagedSut) {
        if (totalMemoryBytes <= 0) {
            return new Memory(0, 0, GENERATOR_TARGET_MIN_BYTES);
        }

        long osReserve = Math.min(
                Math.max(OS_RESERVE_MIN_BYTES, ceilPercent(totalMemoryBytes, OS_RESERVE_MEMORY_PERCENT)),
                totalMemoryBytes - 1);
        long remainderAfterOs = totalMemoryBytes - osReserve;

        long sutReserve = colocatedManagedSut ? remainderAfterOs / 2 : 0;
        long uncapped = remainderAfterOs - sutReserve;
        long allocation = clamp(uncapped, GENERATOR_TARGET_MIN_BYTES, GENERATOR_TARGET_MAX_BYTES);
        allocation = Math.min(allocation, remainderAfterOs);

        return new Memory(osReserve, sutReserve, allocation);
    }

    /** {@code ceil(total * percent / 100)}, entirely in integer arithmetic. */
    private static int ceilPercent(int total, int percent) {
        return (int) ((total * (long) percent + 99) / 100);
    }

    private static long ceilPercent(long total, int percent) {
        return Math.addExact(Math.multiplyExact(total, percent), 99) / 100;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
