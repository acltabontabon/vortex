package dev.vortex.core.target;

import java.util.Optional;

/**
 * What was actually applied — values only, no {@code LimitBasis}. {@code ResourceSignal}/{@code
 * ResourceLimit} (the existing {@code dev.vortex.core.resource} model) is where a limit's basis is
 * carried, per-signal; duplicating it here would let this record's basis and a later-emitted
 * signal's basis silently disagree.
 */
public record EffectiveResourceEnvelope(CpuAllocation cpu, MemoryAllocation memory) {

    public Optional<CpuAllocation> cpuIfPresent() {
        return Optional.ofNullable(cpu);
    }

    public Optional<MemoryAllocation> memoryIfPresent() {
        return Optional.ofNullable(memory);
    }
}
