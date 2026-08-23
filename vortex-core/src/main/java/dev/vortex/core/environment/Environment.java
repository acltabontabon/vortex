package dev.vortex.core.environment;

import dev.vortex.core.shared.EnvironmentId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;

/**
 * A place a test can run against, together with what it is and what it can prove.
 *
 * <p>Header values may contain secret references of the form {@code ${VORTEX_AUTH_TOKEN}}. Vortex
 * stores the reference, never the resolved value: the substitution happens at the moment the load
 * generator is launched and the result is never written to configuration, artifacts, reports or AI
 * prompts.
 *
 * @param id             stable identifier
 * @param name           short human name, e.g. {@code local}
 * @param type           declared environment class — authoritative
 * @param baseUrl        the target
 * @param capabilities   what the environment provides
 * @param dependencyMode whether downstream systems are real
 * @param headers        headers applied to every request; values may be secret references
 */
public record Environment(
        EnvironmentId id,
        String name,
        EnvironmentType type,
        TargetUrl baseUrl,
        EnvironmentCapabilities capabilities,
        DependencyMode dependencyMode,
        Map<String, String> headers) {

    public Environment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(dependencyMode, "dependencyMode");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("environment name must not be blank");
        }
        name = name.trim();
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    /** A local environment pointing at a service running on this machine. */
    public static Environment local(String name, TargetUrl baseUrl) {
        return new Environment(
                EnvironmentId.generate(),
                name,
                EnvironmentType.LOCAL_ISOLATED,
                baseUrl,
                EnvironmentCapabilities.localIsolated(),
                DependencyMode.MOCKED,
                Map.of());
    }

    /** What class of question a run against this environment can answer. */
    public TestClassification classification() {
        return capabilities.classify(dependencyMode);
    }

    public boolean requiresExplicitConfirmation() {
        return type.requiresExplicitConfirmation();
    }

    /** Header names only — safe to log and display. */
    public SequencedMap<String, String> headerNames() {
        SequencedMap<String, String> names = new LinkedHashMap<>();
        headers.forEach((key, value) -> names.put(key, SecretReferences.mask(value)));
        return names;
    }

    public boolean hasSecretReferences() {
        return headers.values().stream().anyMatch(SecretReferences::containsReference);
    }
}
