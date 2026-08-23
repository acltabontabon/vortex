package com.acltabontabon.vortex.core.target;

/** A memory limit, in bytes. */
public record MemoryAllocation(long bytes) {

    public MemoryAllocation {
        if (bytes <= 0) {
            throw new IllegalArgumentException("memory allocation must be positive");
        }
    }

    public static MemoryAllocation ofMebibytes(long mib) {
        return new MemoryAllocation(Math.multiplyExact(mib, 1024L * 1024L));
    }
}
