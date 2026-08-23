package dev.vortex.core.shared;

/** Opaque identifier for a operation id. */
public record OperationId(String value) implements Identifier {

    public OperationId {
        value = Ids.require("OperationId", value);
    }

    public static OperationId generate() {
        return new OperationId(Ids.generate());
    }

    public static OperationId of(String value) {
        return new OperationId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
