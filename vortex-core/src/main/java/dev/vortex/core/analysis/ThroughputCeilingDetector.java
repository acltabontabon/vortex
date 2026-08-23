package dev.vortex.core.analysis;

import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.validity.RunQualityAssessment;
import java.util.Comparator;
import java.util.List;

/**
 * Finds the level beyond which offering more load stopped producing more throughput.
 *
 * <p>The shape is {@code offered ↑, achieved flat, latency ↑} — a queue forming somewhere. All three
 * are required. Offered rising with achieved rising is a healthy service; achieved flattening while
 * latency stays flat is more often a workload that ran out of work to send than a service that ran
 * out of capacity.
 *
 * <h2>The guard that makes this honest</h2>
 * Exactly the same shape is what a saturated load generator produces. Where run quality says the
 * generator was itself constrained at or below the candidate level, this reports
 * {@link ThroughputCeiling.Status#GENERATOR_BOUND} — evidence about the machine Vortex runs on, and
 * not a property of the service at all. That is the whole reason this detector sequences behind
 * validity rather than beside it.
 */
public final class ThroughputCeilingDetector {

    /**
     * How much of an offered increase must reach throughput before the level counts as responsive.
     *
     * <p>Twenty percent: below that, offering more is producing almost nothing, and above it the
     * service is still absorbing a meaningful share of what it is given.
     */
    public static final double RESPONSIVE_FRACTION = 0.20;

    /**
     * How far latency must rise for the flattening to look like a queue rather than a plateau.
     *
     * <p>A service that stops taking more work and answers just as quickly is usually not saturated;
     * something upstream stopped asking. A service that stops taking more work while getting slower
     * has a queue, and that is the finding.
     */
    public static final double LATENCY_RISE_FACTOR = 1.30;

    /** Two points establish a line, not a curve. Three levels is the floor for a derivative. */
    public static final int MINIMUM_STAGES = 3;

    public ThroughputCeiling detect(List<StageObservation> stages, RunQualityAssessment quality) {
        List<StageObservation> byLevel = arrivalRateStages(stages);

        if (byLevel.size() < MINIMUM_STAGES) {
            return ThroughputCeiling.notEvaluated(
                    "A throughput ceiling is a change in how throughput responds to load, which "
                            + "needs at least " + MINIMUM_STAGES + " levels to observe. This run "
                            + "measured " + byLevel.size() + ".");
        }

        for (int index = 1; index < byLevel.size(); index++) {
            StageObservation below = byLevel.get(index - 1);
            StageObservation stage = byLevel.get(index);

            if (!flattenedBetween(below, stage) || !latencyRose(below, stage)) {
                continue;
            }
            int confirming = confirmingStagesAfter(byLevel, index);
            if (confirming == 0 && index < byLevel.size() - 1) {
                // One flat step followed by a recovery is noise, not a ceiling.
                continue;
            }

            if (quality != null && !quality.permitsCapacityAt(stage.targetLoad())) {
                // The generator was constrained here. The shape is real and it is about Vortex.
                return new ThroughputCeiling(ThroughputCeiling.Status.GENERATOR_BOUND,
                        below.targetLoad(), stage.targetLoad(), EvidenceStrength.INSUFFICIENT,
                        byLevel.size(),
                        "Achieved throughput stopped rising above " + below.targetLoad()
                                .displayWithUnit() + ", and this run's own load generator could not "
                                + "produce the level offered there. No conclusion about the service "
                                + "follows from it.");
            }

            return new ThroughputCeiling(ThroughputCeiling.Status.OBSERVED, below.targetLoad(),
                    stage.targetLoad(), strengthOf(confirming, stage.basis()), byLevel.size(),
                    "Offering " + stage.targetLoad().displayWithUnit() + " produced no more "
                            + "throughput than " + below.targetLoad().displayWithUnit()
                            + " did, while latency rose — work is queueing rather than being served."
                            + (confirming > 0
                                    ? " Confirmed across " + confirming + " further level"
                                            + (confirming == 1 ? "" : "s") + "."
                                    : " Observed at the last level measured, so the plateau is not "
                                            + "yet confirmed above it."));
        }
        return ThroughputCeiling.notObserved(byLevel.size());
    }

    /**
     * Only stages whose offered level is an arrival rate, sorted ascending.
     *
     * <p>A closed workload's throughput is an outcome rather than a target: its virtual users simply
     * went slower, and there is no offered rate for achieved throughput to have stopped tracking.
     */
    private List<StageObservation> arrivalRateStages(List<StageObservation> stages) {
        if (stages == null) {
            return List.of();
        }
        return stages.stream()
                .filter(stage -> stage.targetLoad() instanceof RequestsPerSecond)
                .filter(stage -> stage.achievedRateIfPresent().isPresent())
                .sorted(Comparator.comparingDouble(stage -> stage.targetLoad().asDouble()))
                .toList();
    }

    /** Whether the offered increase between two levels produced almost no extra throughput. */
    private boolean flattenedBetween(StageObservation below, StageObservation stage) {
        double offeredBelow = below.targetLoad().asDouble();
        double offeredHere = stage.targetLoad().asDouble();
        if (offeredHere <= offeredBelow || offeredBelow <= 0) {
            return false;
        }
        double achievedBelow = below.achievedRateIfPresent().orElseThrow().asDouble();
        double achievedHere = stage.achievedRateIfPresent().orElseThrow().asDouble();
        if (achievedBelow <= 0) {
            return false;
        }
        double offeredGrowth = (offeredHere - offeredBelow) / offeredBelow;
        double achievedGrowth = (achievedHere - achievedBelow) / achievedBelow;
        return achievedGrowth <= offeredGrowth * RESPONSIVE_FRACTION;
    }

    private boolean latencyRose(StageObservation below, StageObservation stage) {
        var lower = below.p95IfPresent();
        var upper = stage.p95IfPresent();
        if (lower.isEmpty() || upper.isEmpty() || lower.get().isZero()) {
            return false;
        }
        return (double) upper.get().toNanos() / lower.get().toNanos() >= LATENCY_RISE_FACTOR;
    }

    /** How many further levels kept the plateau, which is what turns one flat step into a ceiling. */
    private int confirmingStagesAfter(List<StageObservation> byLevel, int index) {
        int confirming = 0;
        for (int next = index + 1; next < byLevel.size(); next++) {
            if (!flattenedBetween(byLevel.get(next - 1), byLevel.get(next))) {
                break;
            }
            confirming++;
        }
        return confirming;
    }

    /**
     * How firmly the ceiling is held.
     *
     * <p>Never {@code HIGH} on boundaries Vortex computed from planned durations: those timestamps
     * are its own arithmetic, and letting them raise confidence would manufacture it. Same rule
     * {@code ResourcePressure} applies, for the same reason.
     */
    private EvidenceStrength strengthOf(int confirming,
            dev.vortex.core.workload.StageWindowBasis basis) {

        if (confirming >= 2 && basis != null && basis.canStrengthenAFinding()) {
            return EvidenceStrength.HIGH;
        }
        return confirming >= 1 ? EvidenceStrength.MEDIUM : EvidenceStrength.LOW;
    }
}
