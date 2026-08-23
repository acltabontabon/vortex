package com.acltabontabon.vortex.core.target;

/**
 * Millicores — matches Kubernetes' own unit and keeps experiment identity free of binary-float
 * drift. Integer millicores is the only domain representation: no float constructor, no double
 * accessor. A Docker adapter converts to a decimal {@code --cpus} string using exact decimal
 * arithmetic (millicores/1000 is always exact), never via double.
 */
public record CpuAllocation(int millicores) {

    public CpuAllocation {
        if (millicores <= 0) {
            throw new IllegalArgumentException("cpu allocation must be positive");
        }
    }

    public static CpuAllocation ofMillicores(int millicores) {
        return new CpuAllocation(millicores);
    }
}
