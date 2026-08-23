package dev.vortex.core.resource;

/**
 * Whose resource a signal describes.
 *
 * <p>The part that is easy to omit and expensive to omit. CPU at 95% means <em>the service is at its
 * limit</em> or <em>the machine generating the traffic is at its limit</em>, and those two readings
 * lead to opposite actions: tune the service, or stop believing the run. Until Phase 4 Vortex only
 * ever looked at one system, so the question never arose; it now looks at two, and a scope that
 * arrived afterwards would arrive after the conclusions that needed it.
 */
public enum ResourceScope {

    /** The service being tested. The only scope a statement about its capacity may rest on. */
    SYSTEM_UNDER_TEST("System under test"),

    /** The machine producing the traffic — Vortex observing itself. */
    LOAD_GENERATOR("Load generator"),

    /** Something the service under test depends on. */
    DEPENDENCY("Dependency");

    private final String label;

    ResourceScope(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Whether a signal in this scope may support a statement about the service's own limits.
     *
     * <p>Only the system under test can. A generator at its limit is evidence about the experiment,
     * and a dependency at its limit is evidence about something else — reporting either as the
     * service's constraint is the confusion this enum exists to prevent.
     */
    public boolean describesTheServiceUnderTest() {
        return this == SYSTEM_UNDER_TEST;
    }
}
