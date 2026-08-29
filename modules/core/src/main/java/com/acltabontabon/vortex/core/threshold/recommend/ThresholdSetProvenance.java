package com.acltabontabon.vortex.core.threshold.recommend;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Where every threshold in a {@code ThresholdSet} came from, keyed by {@code Threshold.id()}.
 *
 * <p>Carried alongside a {@code ThresholdSet} — on {@code Workload}, {@code ProjectConfiguration} and
 * {@code EffectiveTestPlan} — rather than folded into it, because a threshold's value and its
 * provenance have different lifecycles: the value is what runtime evaluation reads, the provenance is
 * what a "why this number?" panel and a later report read, and neither {@code ThresholdEvaluator} nor
 * the recommender should have to reach past the other's concern to do its job.
 *
 * <p>A threshold with no entry here is a plain manual objective with nothing recorded about it — the
 * normal, unremarkable case, not an error. Vortex never manufactures provenance for a threshold
 * nobody supplied any for.
 */
public record ThresholdSetProvenance(Map<String, ThresholdProvenance> byThresholdId) {

    public ThresholdSetProvenance {
        byThresholdId = byThresholdId == null ? Map.of() : Map.copyOf(byThresholdId);
    }

    public static ThresholdSetProvenance empty() {
        return new ThresholdSetProvenance(Map.of());
    }

    public static ThresholdSetProvenance of(Map<String, ThresholdProvenance> byThresholdId) {
        return new ThresholdSetProvenance(byThresholdId);
    }

    public Optional<ThresholdProvenance> forThreshold(String thresholdId) {
        Objects.requireNonNull(thresholdId, "thresholdId");
        return Optional.ofNullable(byThresholdId.get(thresholdId));
    }

    public boolean isEmpty() {
        return byThresholdId.isEmpty();
    }

    /** This provenance with one more (or replaced) entry, leaving every other entry untouched. */
    public ThresholdSetProvenance with(String thresholdId, ThresholdProvenance provenance) {
        var merged = new java.util.LinkedHashMap<>(byThresholdId);
        merged.put(thresholdId, provenance);
        return new ThresholdSetProvenance(merged);
    }
}
