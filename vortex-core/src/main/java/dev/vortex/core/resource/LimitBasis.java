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
    INHERENT_TO_UNIT("inherent to the unit");

    private final String label;

    LimitBasis(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
