package com.acltabontabon.vortex.core.shared;

/** Opaque identifier for a project id. */
public record ProjectId(String value) implements Identifier {

    public ProjectId {
        value = Ids.require("ProjectId", value);
    }

    public static ProjectId generate() {
        return new ProjectId(Ids.generate());
    }

    public static ProjectId of(String value) {
        return new ProjectId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
