package dev.vortex.core.resource;

/**
 * Where a resource limit came from.
 *
 * <p>Carried because the two are not equally strong evidence, and a reader deciding whether to act
 * on "memory reached its limit" deserves to know which one they are looking at. A published
 * container quota is a fact about the deployment. A hundred percent is a fact about arithmetic.
 */
public enum LimitBasis {

    /**
     * The provider published it: a container quota, a JVM maximum heap, a pool's configured size.
     */
    PUBLISHED_BY_PROVIDER("published by the provider"),

    /** The unit defines it — a percentage cannot exceed one hundred. */
    INHERENT_TO_UNIT("inherent to the unit"),

    /**
     * Vortex configured it itself — a Docker container's {@code --cpus}/{@code --memory} limit that
     * Vortex requested at {@code docker create} time and then confirmed, via a post-start {@code
     * docker inspect}, was actually applied. Distinct from {@link #PUBLISHED_BY_PROVIDER}: that value
     * is for an external system telling Vortex about a limit it already knew; this one is Vortex's
     * own experiment configuration, confirmed applied rather than merely requested. An application
     * Vortex could not confirm is a failed preparation, not a signal carrying this basis with weaker
     * confidence — there is no separate "how sure are we" field alongside it.
     */
    VORTEX_CONFIGURED("configured by Vortex");

    private final String label;

    LimitBasis(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
