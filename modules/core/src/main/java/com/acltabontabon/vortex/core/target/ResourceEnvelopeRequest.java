package com.acltabontabon.vortex.core.target;

import java.util.Optional;

/** Independently optional — either, both, or neither may be requested. */
public record ResourceEnvelopeRequest(CpuAllocation cpu, MemoryAllocation memory) {

    public static ResourceEnvelopeRequest none() {
        return new ResourceEnvelopeRequest(null, null);
    }

    public Optional<CpuAllocation> cpuIfPresent() {
        return Optional.ofNullable(cpu);
    }

    public Optional<MemoryAllocation> memoryIfPresent() {
        return Optional.ofNullable(memory);
    }

    public boolean isEmpty() {
        return cpu == null && memory == null;
    }
}
