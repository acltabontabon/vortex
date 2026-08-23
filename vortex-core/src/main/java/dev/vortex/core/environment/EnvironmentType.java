package dev.vortex.core.environment;

import dev.vortex.core.shared.RequestsPerSecond;

/**
 * The class of environment a test targets.
 *
 * <p>This is authoritative configuration, deliberately chosen by a person. Vortex will additionally
 * apply best-effort heuristics to warn about targets that look production-like, but a hostname is
 * never allowed to override what the user declared — guessing environment class from a string is
 * exactly the kind of confident-but-wrong behaviour this product exists to avoid.
 */
public enum EnvironmentType {

    LOCAL_ISOLATED("Local (isolated)",
            "Runs on your machine against controlled or simulated dependencies.",
            false, 2000),

    SHARED_TEST("Shared test",
            "A test environment other people also use. Load here can disrupt their work.",
            true, 100),

    PERFORMANCE("Performance",
            "A dedicated environment intended for load testing.",
            true, 5000),

    STAGING("Staging",
            "A pre-production environment. It may or may not resemble production capacity.",
            true, 200),

    CUSTOM("Custom",
            "An environment whose characteristics you define yourself.",
            true, 100);

    private final String label;
    private final String description;
    private final boolean requiresConfirmation;
    private final double defaultRateCeiling;

    EnvironmentType(String label, String description, boolean requiresConfirmation,
            double defaultRateCeiling) {
        this.label = label;
        this.description = description;
        this.requiresConfirmation = requiresConfirmation;
        this.defaultRateCeiling = defaultRateCeiling;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** Whether starting a test against this class of environment needs explicit confirmation. */
    public boolean requiresExplicitConfirmation() {
        return requiresConfirmation;
    }

    /**
     * The default upper bound on arrival rate for this environment class.
     *
     * <p>A recommendation, not a hard limit: it can be raised per environment, but only through an
     * explicit override that names what is being overridden.
     *
     * <p>Applies to open workloads, which is where a runaway number does the damage. A concurrency
     * workload is bounded by its own virtual-user count, and {@link #defaultConcurrencyCeiling()}
     * bounds that.
     */
    public RequestsPerSecond defaultRateCeiling() {
        return RequestsPerSecond.of(defaultRateCeiling);
    }

    /**
     * The default upper bound on concurrent virtual users for this environment class.
     *
     * <p>Derived from the rate ceiling on the assumption of roughly 100 ms per request, which is a
     * crude but honest stand-in: the point is to stop somebody typing 50000 into a shared
     * environment, not to model the service.
     */
    public dev.vortex.core.shared.Concurrency defaultConcurrencyCeiling() {
        return dev.vortex.core.shared.Concurrency.of(Math.max(1, (int) Math.round(defaultRateCeiling / 10)));
    }
}
