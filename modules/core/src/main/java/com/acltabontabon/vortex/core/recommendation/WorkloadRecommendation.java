package com.acltabontabon.vortex.core.recommendation;

import com.acltabontabon.vortex.core.threshold.Durations;
import com.acltabontabon.vortex.core.workload.LoadShape;
import com.acltabontabon.vortex.core.workload.TestType;
import com.acltabontabon.vortex.core.workload.WorkloadModel;
import com.acltabontabon.vortex.core.workload.WorkloadSource;
import java.util.Objects;

/**
 * A proposed workload for a test type — a default, never a restriction. Mirrors
 * {@link com.acltabontabon.vortex.core.calibration.WorkloadSuggestion} in spirit (same
 * {@link WorkloadSource} provenance vocabulary) but carries a real, constructible {@link LoadShape} —
 * the same kind of object a saved {@code Workload} would carry — plus the {@link ShapeKind} the
 * composer needs to decide which controls to show.
 *
 * @param type                 the test type this recommends a workload for
 * @param shapeKind            how the shape is patterned, for the composer's Load-shape selector
 * @param shape                the actual, buildable shape
 * @param purpose              one-line "why", e.g. "A very small, steady check that Vortex can reach
 *                             the service."
 * @param source               where the numbers came from
 * @param safetyCeilingApplied whether the peak was capped by {@code SafetyLimits} rather than
 *                             reaching the value production evidence alone would suggest
 */
public record WorkloadRecommendation(
        TestType type,
        ShapeKind shapeKind,
        LoadShape shape,
        String purpose,
        WorkloadSource source,
        boolean safetyCeilingApplied) {

    public WorkloadRecommendation {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(shapeKind, "shapeKind");
        Objects.requireNonNull(shape, "shape");
        purpose = purpose == null ? "" : purpose.trim();
        source = source == null ? WorkloadSource.manual() : source;
    }

    public WorkloadModel model() {
        return shape.model();
    }

    public boolean isProductionInformed() {
        return source.isProductionInformed();
    }

    /**
     * The ready-made sentence for the recommendation card and the right rail. Delegates to
     * {@link #headlineFor} so {@code /tests/preview} can build the identical sentence for a
     * configuration nobody has "recommended" — the recommendation and the live preview must never
     * disagree about how the same numbers read in English.
     */
    public String headline() {
        return headlineFor(type, shapeKind, shape, safetyCeilingApplied);
    }

    public static String headlineFor(TestType type, ShapeKind shapeKind, LoadShape shape,
            boolean safetyCeilingApplied) {
        if (type == TestType.BREAKPOINT) {
            return "Increase traffic progressively until an objective fails or "
                    + (safetyCeilingApplied
                        ? "the configured safety limit (" + shape.peakLevel().displayWithUnit() + ") is reached."
                        : "the configured safety limit is reached.");
        }
        return switch (shapeKind) {
            case STEADY -> shape.startLevel().displayWithUnit() + " for "
                    + Durations.display(shape.totalDuration());
            case SPIKE -> "Jump from " + shape.startLevel().displayWithUnit() + " to "
                    + shape.peakLevel().displayWithUnit() + " and back over "
                    + Durations.display(shape.totalDuration());
            case PROGRESSIVE_RAMP, STAGED -> "Increase traffic across " + shape.stages().size()
                    + " stages from " + shape.startLevel().displayWithUnit() + " → "
                    + shape.peakLevel().displayWithUnit() + " over " + Durations.display(shape.totalDuration());
        };
    }
}
