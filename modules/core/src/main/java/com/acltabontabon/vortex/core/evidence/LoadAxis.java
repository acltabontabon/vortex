package com.acltabontabon.vortex.core.evidence;

import com.acltabontabon.vortex.core.analysis.SloBreakpoint;
import com.acltabontabon.vortex.core.analysis.StageObservation;
import com.acltabontabon.vortex.core.analysis.SystemSaturation;
import com.acltabontabon.vortex.core.capacity.BoundaryStatus;
import com.acltabontabon.vortex.core.shared.LoadLevel;
import com.acltabontabon.vortex.core.workload.StageWindowBasis;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a run established across the range of load it actually tested.
 *
 * <p>This is the model behind Vortex's one genuinely distinctive picture: the levels a workload
 * held, whether each one met its objectives, and where — if anywhere — that stopped being true.
 * Nothing else in the product's category can draw it honestly, because nothing else keeps the two
 * limits apart: an <em>SLO breakpoint</em> is a statement about an agreement and is deterministic,
 * while <em>system saturation</em> is a statement about the machine, is inferred from noisy signals,
 * and is very often not established at all.
 *
 * <h2>This model is semantic, not geometric</h2>
 *
 * <p>Unlike {@link SeriesPlot}, which is deliberately just normalised coordinates, the concepts here
 * are the concepts: stages, compliance, a boundary and its status, a saturation range, and whether
 * objectives were evaluated in the first place. {@link #position(LoadLevel)} exists so a renderer
 * does not have to redo the arithmetic, but it is a convenience on top of the meaning rather than
 * the point of the type.
 *
 * <p>The split matters because renderers multiply. An SVG axis in the browser, a drawn one in the
 * PDF, a comparison of two runs, a terminal summary — each should project this, not reconstruct what
 * a breakpoint means from raw stages and arrive at a slightly different answer.
 *
 * <h2>What it refuses to draw</h2>
 *
 * <p>There is no "approaching the limit" region here, and there must never be one. Vortex has no
 * deterministic rule that says a service is nearing its limit — it knows the levels that met their
 * objectives and the levels that did not, and a gradient between them would be a picture of a
 * conclusion nobody computed.
 *
 * <p>The boundary is drawn only when {@link BoundaryStatus#isQuotable()} allows it. A run whose
 * compliance came back after failing did not establish a boundary, however tidy the points would
 * look joined up, and one whose objectives were never evaluated did not establish one either. In
 * both cases the points are still worth showing — evidence accumulates — and the axis says what it
 * could not conclude rather than quietly drawing a line through it.
 *
 * @param points              one per stage, ordered by level
 * @param testedTo            the highest level this run actually reached
 * @param boundaryStatus      whether a boundary was established, and why not when it was not
 * @param highestCompliant    the highest level that met every objective; null when there was none
 * @param firstNonCompliant   the lowest level that violated one; null when nothing was violated
 * @param saturation          where the system itself stopped coping, or that this was not
 *                            established
 * @param objectivesEvaluated whether there were objectives to judge these stages against at all
 */
public record LoadAxis(
        List<Point> points,
        LoadLevel testedTo,
        BoundaryStatus boundaryStatus,
        LoadLevel highestCompliant,
        LoadLevel firstNonCompliant,
        SystemSaturation saturation,
        boolean objectivesEvaluated) {

    /** Fewer than two levels is a measurement, not a range, and there is no axis to draw. */
    private static final int MINIMUM_POINTS = 2;

    public LoadAxis {
        points = points == null ? List.of() : List.copyOf(points);
        Objects.requireNonNull(boundaryStatus, "boundaryStatus");
    }

    /** Whether a stage met every objective, or whether that question was even asked. */
    public enum Compliance {

        /** Every objective held at this level. */
        COMPLIANT,

        /** At least one objective was violated at this level. */
        NON_COMPLIANT,

        /**
         * No objective could be judged here.
         *
         * <p>Rendered without a verdict colour. An unevaluated stage is not a passing stage, and
         * colouring it as one is the single easiest way for this picture to lie.
         */
        NOT_EVALUATED
    }

    /**
     * One held level.
     *
     * @param basis how this stage's interval was established. A stage aligned from planned durations
     *              rather than observed samples is still evidence, but a renderer should say so —
     *              those boundaries are Vortex's own arithmetic, not corroboration
     */
    public record Point(int index, LoadLevel level, Compliance compliance, StageWindowBasis basis,
            List<String> violatedThresholds) {

        public Point {
            violatedThresholds =
                    violatedThresholds == null ? List.of() : List.copyOf(violatedThresholds);
        }

        public boolean isCompliant() {
            return compliance == Compliance.COMPLIANT;
        }

        public boolean isNonCompliant() {
            return compliance == Compliance.NON_COMPLIANT;
        }

        public boolean wasEvaluated() {
            return compliance != Compliance.NOT_EVALUATED;
        }

        /** Whether this stage's interval was measured rather than computed from the plan. */
        public boolean isObserved() {
            return basis == StageWindowBasis.OBSERVED;
        }
    }

    /**
     * Builds an axis from what a run measured.
     *
     * @param stages              every level the workload held
     * @param objectivesEvaluated whether the run had objectives that could be judged
     * @param breakpoint          the deterministic SLO breakpoint, or null when none was found
     * @param saturation          the system-saturation finding; may be null
     */
    public static LoadAxis from(List<StageObservation> stages, boolean objectivesEvaluated,
            SloBreakpoint breakpoint, SystemSaturation saturation) {

        if (stages == null || stages.isEmpty()) {
            return empty();
        }

        List<StageObservation> byLoad = stages.stream()
                .sorted(Comparator.comparingDouble(stage -> stage.targetLoad().asDouble()))
                .toList();

        List<Point> points = new java.util.ArrayList<>(byLoad.size());
        for (int i = 0; i < byLoad.size(); i++) {
            StageObservation stage = byLoad.get(i);
            points.add(new Point(i, stage.targetLoad(), complianceOf(stage, objectivesEvaluated),
                    stage.basis(), stage.violatedThresholds()));
        }

        // The monotonicity rule lives in BoundaryStatus and is applied rather than re-derived: two
        // implementations of "did this run establish a boundary?" would eventually disagree, and the
        // disagreement would surface as a capacity figure one screen quotes and another refuses.
        BoundaryStatus status = objectivesEvaluated
                ? BoundaryStatus.of(byLoad)
                : BoundaryStatus.NOT_EVALUATED;

        LoadLevel highestCompliant = null;
        LoadLevel firstNonCompliant = null;
        if (status.isQuotable()) {
            highestCompliant = breakpoint != null
                    ? breakpoint.highestCompliantLevelIfPresent().orElse(highestCompliantIn(byLoad))
                    : highestCompliantIn(byLoad);
            firstNonCompliant = breakpoint != null ? breakpoint.level() : firstNonCompliantIn(byLoad);
        }

        return new LoadAxis(points, byLoad.getLast().targetLoad(), status,
                highestCompliant, firstNonCompliant, saturation, objectivesEvaluated);
    }

    public static LoadAxis empty() {
        return new LoadAxis(List.of(), null, BoundaryStatus.NOT_EVALUATED, null, null, null, false);
    }

    private static Compliance complianceOf(StageObservation stage, boolean objectivesEvaluated) {
        if (!objectivesEvaluated) {
            return Compliance.NOT_EVALUATED;
        }
        return stage.isCompliant() ? Compliance.COMPLIANT : Compliance.NON_COMPLIANT;
    }

    private static LoadLevel highestCompliantIn(List<StageObservation> byLoad) {
        return byLoad.stream().filter(StageObservation::isCompliant)
                .map(StageObservation::targetLoad)
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private static LoadLevel firstNonCompliantIn(List<StageObservation> byLoad) {
        return byLoad.stream().filter(stage -> !stage.isCompliant())
                .map(StageObservation::targetLoad)
                .findFirst()
                .orElse(null);
    }

    /** Whether there is a range here worth drawing at all. */
    public boolean isRenderable() {
        return points.size() >= MINIMUM_POINTS && testedTo != null && testedTo.asDouble() > 0;
    }

    /**
     * Whether a boundary marker may be drawn.
     *
     * <p>The gate for the whole picture. False for a run whose compliance was not monotonic and for
     * one whose objectives were never evaluated — in both cases the stages are still shown, and no
     * line is drawn through them.
     */
    public boolean drawsBoundary() {
        return boundaryStatus.isQuotable() && highestCompliant != null;
    }

    /**
     * Whether the axis should stay open at its right-hand end.
     *
     * <p>True when nothing was violated: the boundary is somewhere above what this run reached, and
     * closing the axis would imply a ceiling that was never found.
     */
    public boolean isOpenEnded() {
        return boundaryStatus == BoundaryStatus.FAR_EDGE_NOT_REACHED;
    }

    public Optional<LoadLevel> highestCompliantIfPresent() {
        return Optional.ofNullable(highestCompliant);
    }

    public Optional<LoadLevel> firstNonCompliantIfPresent() {
        return Optional.ofNullable(firstNonCompliant);
    }

    /** What this run concluded about its boundary, in the vocabulary the rest of Vortex uses. */
    public String boundaryStatement() {
        return boundaryStatus.label();
    }

    public Optional<SystemSaturation> saturationIfPresent() {
        return Optional.ofNullable(saturation);
    }

    /**
     * Whether the saturation range can be placed on this axis.
     *
     * <p>Requires both bounds and requires them to be the same quantity as the axis. A saturation
     * bounded in virtual users cannot be drawn against an arrival-rate axis: the two are different
     * quantities and placing one on the other's scale would be a picture of a conversion that does
     * not exist.
     */
    public boolean drawsSaturation() {
        if (saturation == null || !saturation.wasObserved() || !isRenderable()) {
            return false;
        }
        LoadLevel lower = saturation.lowerBound();
        LoadLevel upper = saturation.upperBound();
        return lower != null && upper != null
                && lower.sameQuantityAs(testedTo) && upper.sameQuantityAs(testedTo);
    }

    /**
     * Where a level sits along the axis, from 0 to 1.
     *
     * <p>Clamped, so a saturation range whose upper bound sits above the tested extent draws to the
     * end of the axis rather than off it. A convenience for renderers; the meaning is in the fields.
     */
    public double position(LoadLevel level) {
        if (level == null || !isRenderable() || !level.sameQuantityAs(testedTo)) {
            return 0;
        }
        double fraction = level.asDouble() / testedTo.asDouble();
        return Math.clamp(fraction, 0d, 1d);
    }

    /** The unit every level on this axis is stated in, e.g. {@code requests/sec}. */
    public String unit() {
        return testedTo == null ? "" : testedTo.unit();
    }

    /**
     * Whether any stage was aligned from planned durations rather than observed samples.
     *
     * <p>Surfaced so the picture can say so. It does not weaken the compliance verdicts, which come
     * from the load generator's own measurements, but it does bear on any claim that a service-side
     * signal coincided with a particular level.
     */
    public boolean hasDerivedAlignment() {
        return points.stream().anyMatch(point -> !point.isObserved());
    }
}
