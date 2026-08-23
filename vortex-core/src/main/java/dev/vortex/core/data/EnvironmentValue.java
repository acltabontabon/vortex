package dev.vortex.core.data;

import dev.vortex.core.environment.SecretReferences;
import java.util.Objects;
import java.util.Set;

/**
 * A value resolved from the process environment when the load generator is launched.
 *
 * <p>This is how a credential participates in a test without being written down. Vortex stores the
 * <em>reference</em> — {@code ${API_TOKEN}} — and resolves it exactly once, into the environment of
 * the engine subprocess. The resolved value never reaches {@code vortex.yaml}, the local database,
 * the effective plan, an artifact, a log line, a report or an AI prompt.
 *
 * <p>Deliberately not a second secret mechanism. Environment header values have worked this way
 * since the first release; this case makes the same reference explicit and selectable in the
 * interface, using the same {@link SecretReferences} parser and the same launch-time resolution.
 *
 * <p>The whole template is kept rather than only the variable name, because {@code Bearer ${TOKEN}}
 * is a real and common shape. Vortex substitutes into the template; it does not require the value to
 * be the entire header.
 *
 * @param template a string containing at least one {@code ${NAME}} reference
 */
public record EnvironmentValue(String template) implements RequestValue {

    public EnvironmentValue {
        Objects.requireNonNull(template, "template");
        if (!SecretReferences.containsReference(template)) {
            throw new IllegalArgumentException(
                    "an environment value must reference at least one variable as ${NAME}, but was: "
                            + template + ". A value with no reference is a fixed value.");
        }
    }

    /** An environment value referencing one variable in full, e.g. {@code API_TOKEN}. */
    public static EnvironmentValue named(String variableName) {
        if (variableName == null || variableName.isBlank()) {
            throw new IllegalArgumentException("environment variable name must not be blank");
        }
        return new EnvironmentValue("${" + variableName.trim() + "}");
    }

    static boolean looksLikeReference(String value) {
        return SecretReferences.containsReference(value);
    }

    /** The environment variable names this value depends on. */
    public Set<String> referencedNames() {
        return SecretReferences.referencedNames(template);
    }

    @Override
    public String describeSource() {
        return "environment: " + String.join(", ", referencedNames());
    }

    @Override
    public boolean isDynamic() {
        // Constant for the duration of a run, but not knowable at generation time: the script has to
        // read it from the environment rather than carry it.
        return true;
    }
}
