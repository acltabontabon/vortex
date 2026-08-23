package dev.vortex.core.data;

import java.util.Objects;

/**
 * A literal value the user supplied.
 *
 * <p>The same value on every request from every virtual user. That is frequently correct — a
 * product code, a client identifier, a tenant header — and it is what Vortex has always done. It is
 * the default source, and the one a value falls back to when nothing else is chosen.
 *
 * <p>A literal containing {@code ${NAME}} is not this case: it is an {@link EnvironmentValue}, so
 * that a token pasted into a fixed field still resolves at launch rather than being written into
 * {@code vortex.yaml}. {@link #of} applies that rule so callers cannot get it wrong.
 *
 * @param literal the value to send; may be empty, which is a legitimate query parameter
 */
public record FixedValue(String literal) implements RequestValue {

    public FixedValue {
        Objects.requireNonNull(literal, "literal");
    }

    /**
     * A fixed value, unless the text carries a secret reference — in which case it is an environment
     * value, because a {@code ${NAME}} treated as a literal would be sent verbatim to the service and
     * stored in the configuration file.
     */
    public static RequestValue of(String text) {
        String value = text == null ? "" : text;
        return EnvironmentValue.looksLikeReference(value)
                ? new EnvironmentValue(value)
                : new FixedValue(value);
    }

    @Override
    public String describeSource() {
        return "fixed";
    }

    @Override
    public boolean isDynamic() {
        return false;
    }
}
