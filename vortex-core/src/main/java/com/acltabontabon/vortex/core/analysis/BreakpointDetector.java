package com.acltabontabon.vortex.core.analysis;

import com.acltabontabon.vortex.core.shared.LoadLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finds the traffic level at which a service first stopped meeting its objectives.
 *
 * <p>Deterministic and simple by design: the SLO breakpoint is the first stage, in increasing order
 * of offered traffic, whose measurements violated a threshold. There is no curve fitting and no
 * inference, because there does not need to be — the definition of "stopped meeting the objective"
 * is exactly the objective the user wrote down.
 *
 * <p>What does require judgement is how much the answer is worth, and that is reported explicitly
 * as {@link EvidenceStrength} rather than assumed. Two stages of evidence bracket the breakpoint
 * very loosely; ten stages locate it much more tightly. A run with a single stage cannot identify a
 * breakpoint at all, and says so.
 */
public final class BreakpointDetector {

    /** Below this many stages, a breakpoint is bracketed too loosely to state with confidence. */
    private static final int STAGES_FOR_HIGH_CONFIDENCE = 5;
    private static final int STAGES_FOR_MEDIUM_CONFIDENCE = 3;

    /** Buckets per stage below which the stage's own measurements are thin. */
    private static final int SAMPLES_FOR_CONFIDENCE = 3;

    /**
     * Stages compare by magnitude alone.
     *
     * <p>Safe because every stage of one workload measures the same quantity — the workload types
     * enforce that — so there is never a mixture of rates and virtual-user counts to order.
     */
    private static final java.util.Comparator<StageObservation> BY_LEVEL =
            java.util.Comparator.comparingDouble(stage -> stage.targetLoad().asDouble());

    /**
     * Identifies the SLO breakpoint from stage-level observations.
     *
     * @param stages observations in the order the workload ran them
     * @return the breakpoint, or empty when no stage violated an objective
     */
    public Optional<SloBreakpoint> detectSloBreakpoint(List<StageObservation> stages) {
        if (stages == null || stages.size() < 2) {
            return Optional.empty();
        }

        List<StageObservation> ordered = new ArrayList<>(stages);
        ordered.sort(BY_LEVEL);

        StageObservation firstViolating = null;
        LoadLevel highestCompliant = null;
        for (StageObservation stage : ordered) {
            if (stage.isCompliant()) {
                if (firstViolating == null) {
                    highestCompliant = stage.targetLoad();
                }
            } else if (firstViolating == null) {
                firstViolating = stage;
            }
        }

        if (firstViolating == null) {
            return Optional.empty();
        }

        return Optional.of(new SloBreakpoint(
                firstViolating.targetLoad(),
                highestCompliant,
                firstViolating.violatedThresholds(),
                strengthFor(ordered, firstViolating),
                ordered.size()));
    }

    /**
     * The highest offered level at which every objective was still met.
     *
     * <p>This is the figure that becomes a {@code CapacityObservation}. It is empty when no stage
     * was compliant, because "we never met the objectives" is not a capacity.
     */
    public Optional<LoadLevel> highestCompliantLevel(List<StageObservation> stages) {
        if (stages == null || stages.isEmpty()) {
            return Optional.empty();
        }
        return stages.stream()
                .filter(StageObservation::isCompliant)
                .max(BY_LEVEL)
                .map(StageObservation::targetLoad);
    }

    private EvidenceStrength strengthFor(List<StageObservation> stages, StageObservation violating) {
        boolean thinSamples = violating.sampleCount() > 0
                && violating.sampleCount() < SAMPLES_FOR_CONFIDENCE;
        if (stages.size() >= STAGES_FOR_HIGH_CONFIDENCE && !thinSamples) {
            return EvidenceStrength.HIGH;
        }
        if (stages.size() >= STAGES_FOR_MEDIUM_CONFIDENCE) {
            return thinSamples ? EvidenceStrength.LOW : EvidenceStrength.MEDIUM;
        }
        return EvidenceStrength.LOW;
    }
}
