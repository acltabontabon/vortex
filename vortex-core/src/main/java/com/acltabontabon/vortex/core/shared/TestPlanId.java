package com.acltabontabon.vortex.core.shared;

/** Opaque identifier for a test plan id. */
public record TestPlanId(String value) implements Identifier {

    public TestPlanId {
        value = Ids.require("TestPlanId", value);
    }

    public static TestPlanId generate() {
        return new TestPlanId(Ids.generate());
    }

    public static TestPlanId of(String value) {
        return new TestPlanId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
