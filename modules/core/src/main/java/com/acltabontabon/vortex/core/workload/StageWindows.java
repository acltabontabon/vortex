package com.acltabontabon.vortex.core.workload;

import com.acltabontabon.vortex.core.metrics.TimeWindow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * When each stage of a workload was in effect.
 *
 * <h2>Why this is one class</h2>
 * The cumulative walk over stage durations previously existed in two places — the analyzer deriving
 * stage observations after a run, and the k6 aggregator labelling live buckets with their target
 * level. Two implementations of the same rule is one implementation more than the rule can survive:
 * they would eventually disagree about which stage a sample belonged to, and the disagreement would
 * show up as a bottleneck attributed to the wrong level of load.
 *
 * <p>Every window produced here carries a {@link StageWindowBasis}, because the difference between
 * boundaries that were measured and boundaries that were computed is not a detail — see that type
 * for why it changes what a finding may claim.
 */
public final class StageWindows {

    private StageWindows() {
    }

    /**
     * One stage's extent, and how its extent was established.
     *
     * @param index  position in the workload, from zero
     * @param target the level the workload was holding
     * @param window when it was holding it
     * @param basis  how the boundaries were arrived at
     */
    public record StageWindow(int index, com.acltabontabon.vortex.core.shared.LoadLevel target, TimeWindow window,
            StageWindowBasis basis) {

        public StageWindow {
            Objects.requireNonNull(window, "window");
            Objects.requireNonNull(basis, "basis");
        }

        public boolean contains(Instant instant) {
            return window.contains(instant);
        }
    }

    /**
     * Stage windows computed from planned durations, anchored at a known start.
     *
     * <p>The anchor should be the first sample the run actually produced rather than the moment
     * Vortex decided to start it: a load generator takes a moment to come up, and anchoring on the
     * intent rather than the evidence shifts every boundary by that moment.
     *
     * <p>The result is {@link StageWindowBasis#DERIVED_FROM_PLAN} even with an observed anchor,
     * because the durations are still what was asked for rather than what happened.
     */
    public static List<StageWindow> fromPlan(List<Stage> stages, Instant anchor) {
        Objects.requireNonNull(anchor, "anchor");
        if (stages == null || stages.isEmpty()) {
            return List.of();
        }
        List<StageWindow> windows = new ArrayList<>(stages.size());
        Instant cursor = anchor;
        for (int index = 0; index < stages.size(); index++) {
            Stage stage = stages.get(index);
            Instant end = cursor.plus(stage.duration());
            windows.add(new StageWindow(index, stage.target(), new TimeWindow(cursor, end),
                    StageWindowBasis.DERIVED_FROM_PLAN));
            cursor = end;
        }
        return List.copyOf(windows);
    }

    /**
     * The intended level at an offset into the run, for labelling a live bucket.
     *
     * <p>Returns null past the end of the last stage: a sample taken during graceful drain belongs to
     * no stage, and inventing one for it would attribute drain traffic to the peak.
     */
    public static com.acltabontabon.vortex.core.shared.LoadLevel levelAt(List<Stage> stages, Duration offset) {
        if (stages == null || stages.isEmpty() || offset == null || offset.isNegative()) {
            return null;
        }
        Duration cursor = Duration.ZERO;
        com.acltabontabon.vortex.core.shared.LoadLevel current = null;
        for (Stage stage : stages) {
            Duration end = cursor.plus(stage.duration());
            if (offset.compareTo(cursor) >= 0 && offset.compareTo(end) < 0) {
                return stage.target();
            }
            current = stage.target();
            cursor = end;
        }
        // Exactly at the final boundary is still the final stage; anything beyond it is drain.
        return offset.equals(cursor) ? current : null;
    }

    /**
     * Stage windows established from the virtual-user counts the load generator actually reported.
     *
     * <p>Only meaningful for a concurrency-model workload, where each stage targets a distinct
     * virtual-user count and k6's own {@code vus} metric tracks the ramp it performed. Reading a
     * metric k6 already publishes needs no custom tag and no custom metric, which is what makes this
     * compatible with ADR-026.
     *
     * <p>Returns empty — rather than something approximate — whenever the evidence does not actually
     * establish the boundaries: no reported counts, an arrival-rate workload whose targets are not
     * virtual users, or a run in which some stage's level was never reached. In every one of those
     * cases the caller should fall back to {@link #fromPlan}, and should say so, because a computed
     * boundary presented as a measured one is exactly the claim this whole distinction exists to
     * prevent.
     *
     * @param stages  the planned stages, in order
     * @param samples buckets carrying observed virtual-user counts, in time order
     */
    public static List<StageWindow> fromObservedVirtualUsers(List<Stage> stages,
            List<com.acltabontabon.vortex.core.metrics.SamplePoint> samples) {

        if (stages == null || stages.isEmpty() || samples == null || samples.isEmpty()) {
            return List.of();
        }
        boolean everyStageTargetsVirtualUsers = stages.stream()
                .allMatch(stage -> stage.target() instanceof com.acltabontabon.vortex.core.shared.Concurrency);
        if (!everyStageTargetsVirtualUsers) {
            return List.of();
        }
        boolean anyReported = samples.stream()
                .anyMatch(sample -> sample.observedVusIfPresent().isPresent());
        if (!anyReported) {
            return List.of();
        }

        List<StageWindow> windows = new ArrayList<>(stages.size());
        int searchFrom = 0;

        for (int index = 0; index < stages.size(); index++) {
            Stage stage = stages.get(index);
            int target = ((com.acltabontabon.vortex.core.shared.Concurrency) stage.target()).vus();

            // The plateau is where the count *equals* the target, not merely where it reaches it:
            // a later, higher stage also satisfies "at least ten virtual users", and matching on
            // that would let the first stage swallow the whole run. Samples between two plateaus
            // are the ramp itself and belong to neither — which is correct, and is why a boundary
            // measured this way is tighter than one computed from planned durations.
            Integer plateauStart = null;
            int plateauEnd = -1;
            for (int i = searchFrom; i < samples.size(); i++) {
                var observed = samples.get(i).observedVusIfPresent().orElse(null);
                if (observed == null) {
                    continue;
                }
                if (observed == target) {
                    if (plateauStart == null) {
                        plateauStart = i;
                    }
                    plateauEnd = i;
                } else if (plateauStart != null) {
                    break;
                }
            }

            if (plateauStart == null) {
                // A stage whose level the run never reached cannot have its extent measured, and
                // the stages after it cannot be trusted either — the whole alignment falls back.
                return List.of();
            }

            var start = samples.get(plateauStart).at();
            var last = samples.get(plateauEnd);
            windows.add(new StageWindow(index, stage.target(),
                    new TimeWindow(start, last.at().plus(last.duration())),
                    StageWindowBasis.OBSERVED));
            searchFrom = plateauEnd + 1;
        }

        return List.copyOf(windows);
    }

    /** The stage a given instant falls in, or empty when it falls outside every stage. */
    public static java.util.Optional<StageWindow> at(List<StageWindow> windows, Instant instant) {
        if (windows == null || instant == null) {
            return java.util.Optional.empty();
        }
        return windows.stream().filter(window -> window.contains(instant)).findFirst();
    }
}
