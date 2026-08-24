package com.acltabontabon.vortex.core.recommendation;

import com.acltabontabon.vortex.core.workload.TestType;
import java.util.List;

/**
 * How a workload's stages are patterned over time — presentation vocabulary, not a fifth
 * {@link com.acltabontabon.vortex.core.workload.LoadShape} variant. Every kind still compiles to one
 * of {@code LoadShape}'s four sealed permits. Attached only by a {@link WorkloadRecommendation}, or
 * inferred back from an already-built shape by {@link ShapeKindClassifier} for the editor — never
 * stored on {@code Workload}, which deliberately says nothing about shape (see {@code TestType}'s
 * javadoc).
 */
public enum ShapeKind {
    STEADY, PROGRESSIVE_RAMP, SPIKE, STAGED;

    /**
     * Shapes worth presenting for a test type by default, to declutter the composer — never a
     * restriction. {@code TestType} permits any {@code LoadShape}; "Customize workload" in the
     * composer always offers all four regardless of intent.
     */
    public static List<ShapeKind> relevantFor(TestType type) {
        return switch (type) {
            case SMOKE, AVERAGE_LOAD, SOAK -> List.of(STEADY);
            case STRESS -> List.of(PROGRESSIVE_RAMP, STEADY);
            case SPIKE -> List.of(SPIKE);
            case BREAKPOINT -> List.of(STAGED);
        };
    }
}
