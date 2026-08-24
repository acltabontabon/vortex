package com.acltabontabon.vortex.core.recommendation;

import com.acltabontabon.vortex.core.workload.LoadShape;
import com.acltabontabon.vortex.core.workload.Stage;
import java.util.List;

/**
 * Infers a {@link ShapeKind} from an already-built {@link LoadShape}, so the editor knows which
 * controls to prefill when it opens an existing test. Display-only: every {@code LoadShape} that
 * already exists is legal regardless of what it classifies as.
 */
public final class ShapeKindClassifier {

    private ShapeKindClassifier() {
    }

    public static ShapeKind classify(LoadShape shape) {
        List<Stage> stages = shape.stages();
        if (stages.size() <= 1) {
            return ShapeKind.STEADY;
        }
        List<Double> levels = stages.stream().map(s -> s.target().asDouble()).toList();
        if (isSpikePattern(levels)) {
            return ShapeKind.SPIKE;
        }
        return isMonotonicNonDecreasing(levels) && stages.size() <= 4
                ? ShapeKind.PROGRESSIVE_RAMP
                : ShapeKind.STAGED;
    }

    /** The exact [baseline, peak, peak, baseline] pattern
     *  {@link com.acltabontabon.vortex.core.workload.SpikeShapes} produces. */
    private static boolean isSpikePattern(List<Double> levels) {
        return levels.size() == 4
                && levels.get(1) > levels.get(0)
                && levels.get(1).doubleValue() == levels.get(2).doubleValue()
                && levels.get(3) <= levels.get(0) + 1e-9;
    }

    private static boolean isMonotonicNonDecreasing(List<Double> levels) {
        for (int i = 1; i < levels.size(); i++) {
            if (levels.get(i) < levels.get(i - 1)) {
                return false;
            }
        }
        return true;
    }
}
