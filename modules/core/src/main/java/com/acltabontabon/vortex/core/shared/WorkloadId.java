package com.acltabontabon.vortex.core.shared;

/** Opaque identifier for a workload. */
public record WorkloadId(String value) implements Identifier {

    public WorkloadId {
        value = Ids.require("WorkloadId", value);
    }

    public static WorkloadId generate() {
        return new WorkloadId(Ids.generate());
    }

    public static WorkloadId of(String value) {
        return new WorkloadId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
