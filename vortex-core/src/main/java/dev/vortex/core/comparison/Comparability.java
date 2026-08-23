package dev.vortex.core.comparison;

/**
 * How much an AI interpretation of a comparison may trust the pair it was given.
 *
 * <p>Computed deterministically, the same way {@link ExecutionComparison} itself is — the
 * question "can this be interpreted at all" is exactly the kind of fact Vortex should decide
 * before ever spending an inference call on it, not something left to a model to notice partway
 * through.
 */
public enum Comparability {

    /** Same experiment, both sides measured, nothing missing that would limit the comparison. */
    HIGH,

    /** Comparable, but a limitation on one or both sides should qualify any conclusion drawn. */
    PARTIAL,

    /** Not meaningfully comparable at all — different experiments, or nothing was measured. */
    INVALID;

    /**
     * @param comparison        the deterministic comparison
     * @param baselineHasGaps   whether the baseline run is missing telemetry that could matter
     * @param candidateHasGaps  whether the candidate run is missing telemetry that could matter
     */
    public static Comparability classify(ExecutionComparison comparison, boolean baselineHasGaps,
            boolean candidateHasGaps) {

        if (!comparison.supportsRegressionVerdict() || comparison.deltas().isEmpty()) {
            return INVALID;
        }
        if (baselineHasGaps || candidateHasGaps) {
            return PARTIAL;
        }
        return HIGH;
    }
}
