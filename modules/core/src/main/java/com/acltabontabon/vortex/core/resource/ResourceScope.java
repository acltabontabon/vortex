package com.acltabontabon.vortex.core.resource;

/**
 * Whose resource a signal describes.
 *
 * <p>The part that is easy to omit and expensive to omit. CPU at 95% means <em>the service is at its
 * limit</em> or <em>the machine generating the traffic is at its limit</em>, and those two readings
 * lead to opposite actions: tune the service, or stop believing the run. Until Phase 4 Vortex only
 * ever looked at one system, so the question never arose; it now looks at two, and a scope that
 * arrived afterwards would arrive after the conclusions that needed it.
 *
 * <p>{@link #LOAD_GENERATOR} and {@link #LOAD_GENERATOR_HOST} exist for the same reason one scope was
 * not enough for "the service" versus "the machine generating traffic": a shared host is not the
 * generator any more than a shared host is the service. Folding the two together made ordinary host
 * memory or CPU pressure — caused by anything sharing that machine — look like the generator's own
 * limit, which invalidated runs for no reason connected to the experiment at all.
 */
public enum ResourceScope {

    /** The service being tested. The only scope a statement about its capacity may rest on. */
    SYSTEM_UNDER_TEST("System under test"),

    /**
     * The load generator's own process or container — the narrowest measurement Vortex could isolate
     * for this execution target. A signal here reaching its limit is trustworthy evidence the
     * generator itself was constrained.
     */
    LOAD_GENERATOR("Load generator"),

    /**
     * The whole machine running the load generator — broader than the generator's own process or
     * container, and never proof that the generator itself was constrained, since anything else
     * sharing that machine could be the actual cause. Supporting context only; see
     * {@link com.acltabontabon.vortex.core.validity.ValidityReason#GENERATOR_HOST_UNDER_PRESSURE} for how this is
     * used without being read as {@link com.acltabontabon.vortex.core.validity.ValidityReason#GENERATOR_SATURATED}.
     */
    LOAD_GENERATOR_HOST("Load generator host"),

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
