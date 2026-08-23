package dev.vortex.core.shared;

/** Opaque identifier for a execution id. */
public record ExecutionId(String value) implements Identifier {

    public ExecutionId {
        value = Ids.require("ExecutionId", value);
    }

    public static ExecutionId generate() {
        return new ExecutionId(Ids.generate());
    }

    public static ExecutionId of(String value) {
        return new ExecutionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
