package dev.vortex.core.safety;

import dev.vortex.core.environment.EnvironmentType;
import dev.vortex.core.shared.Concurrency;
import dev.vortex.core.shared.LoadLevel;
import dev.vortex.core.shared.RequestsPerSecond;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The configurable safety envelope Vortex applies before a run.
 *
 * <p>Defaults are conservative and can be raised, but raising them is always an explicit act.
 * Vortex is an engineering tool, not an unrestricted traffic generator: the assumption is not that
 * the user intends to attack whatever URL they paste.
 *
 * @param rateCeilings   maximum arrival rate per environment class
 * @param maximumDuration longest a single run may last
 * @param allowedHosts   when non-empty, only these hosts may be targeted
 */
public record SafetyLimits(
        Map<EnvironmentType, RequestsPerSecond> rateCeilings,
        Duration maximumDuration,
        List<String> allowedHosts) {

    public SafetyLimits {
        rateCeilings = rateCeilings == null ? Map.of() : Map.copyOf(rateCeilings);
        maximumDuration = maximumDuration == null ? Duration.ofHours(4) : maximumDuration;
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
    }

    /** The shipped defaults: per-environment ceilings, a four-hour cap, no host allowlist. */
    public static SafetyLimits defaults() {
        Map<EnvironmentType, RequestsPerSecond> ceilings = new java.util.EnumMap<>(EnvironmentType.class);
        for (EnvironmentType type : EnvironmentType.values()) {
            ceilings.put(type, type.defaultRateCeiling());
        }
        return new SafetyLimits(ceilings, Duration.ofHours(4), List.of());
    }

    public RequestsPerSecond ceilingFor(EnvironmentType type) {
        return Objects.requireNonNullElseGet(rateCeilings.get(type), type::defaultRateCeiling);
    }

    public Concurrency concurrencyCeilingFor(EnvironmentType type) {
        return type.defaultConcurrencyCeiling();
    }

    /**
     * The ceiling that applies to a level, matched to whichever quantity it measures.
     *
     * <p>Comparing a virtual-user count against a requests-per-second ceiling would be arithmetic
     * across two different units, which is how a safety limit ends up either useless or unusable.
     */
    public LoadLevel ceilingFor(EnvironmentType type, LoadLevel level) {
        return level instanceof Concurrency ? concurrencyCeilingFor(type) : ceilingFor(type);
    }

    public boolean hasHostAllowlist() {
        return !allowedHosts.isEmpty();
    }

    public boolean isHostAllowed(String host) {
        if (allowedHosts.isEmpty()) {
            return true;
        }
        String lower = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        return allowedHosts.stream()
                .map(h -> h.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(allowed -> lower.equals(allowed)
                        || (allowed.startsWith("*.") && lower.endsWith(allowed.substring(1))));
    }
}
