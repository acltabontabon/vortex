package dev.vortex.core.shared;

/** Opaque identifier for a environment id. */
public record EnvironmentId(String value) implements Identifier {

    public EnvironmentId {
        value = Ids.require("EnvironmentId", value);
    }

    public static EnvironmentId generate() {
        return new EnvironmentId(Ids.generate());
    }

    public static EnvironmentId of(String value) {
        return new EnvironmentId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
