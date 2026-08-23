package com.acltabontabon.vortex.core.evidence;

import com.acltabontabon.vortex.core.capacity.BoundaryEdge;
import com.acltabontabon.vortex.core.capacity.BoundaryStatus;
import com.acltabontabon.vortex.core.capacity.CapacityObservation;
import com.acltabontabon.vortex.core.capacity.ProductionObservation;
import com.acltabontabon.vortex.core.shared.LoadLevel;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a service has been shown to do, on one scale: the traffic production sends it, the load it
 * sustained while meeting its objectives, and the load at which that stopped being true.
 *
 * <p>Where {@link LoadAxis} answers "what did this run establish?", this answers "what does Vortex
 * currently know about this service?" — the standing conclusion rather than one measurement of it.
 * It is the picture Home, the service workspace and the evidence pages all draw, and drawing the
 * same shape in all three is deliberate: this is the form somebody should come to recognise, and a
 * figure that looks different in three places is three figures as far as a reader is concerned.
 *
 * <h2>Why this is not a second factory on LoadAxis</h2>
 *
 * <p>Three reasons, and the third is the one that matters.
 *
 * <p>A {@link LoadAxis.Point} carries a {@link com.acltabontabon.vortex.core.workload.StageWindowBasis} and the
 * thresholds it violated. A {@link CapacityObservation} has two edges and no stages, so every point
 * synthesised from one would have to claim a provenance it does not have.
 *
 * <p>{@code LoadAxis} is part of the exported run-evidence schema. Widening it to mean two things
 * widens that contract for what is, here, a reading of the evidence rather than another piece of it.
 *
 * <p>And {@code LoadAxis.position} normalises against the highest level the run reached, clamping
 * anything above it. A service whose production traffic has grown past its tested capacity would
 * have its production marker clamped silently onto the capacity marker — the picture would conceal
 * the one situation it exists to reveal. Here the scale is the largest marker actually drawn,
 * whichever kind it is, so production overtaking tested capacity is visible as exactly that.
 *
 * <h2>What it refuses to draw</h2>
 *
 * <p>The same refusals as {@link LoadAxis}, for the same reasons, applied through the same domain
 * methods rather than re-derived. No region is shaded and none ever should be: Vortex has no rule
 * that decides when a service is "approaching" its limit, and a gradient between the two marks would
 * render an invented conclusion more persuasively than either measured one.
 *
 * <p>A capacity marker is drawn only where {@link CapacityObservation#isQuotable()} allows it, so a
 * run whose compliance was not monotonic contributes no number here however tidy it would look. A
 * failing marker is drawn only where the boundary is {@link BoundaryStatus#ESTABLISHED} — under
 * {@link BoundaryStatus#FAR_EDGE_NOT_REACHED} nothing failed, and the axis stays open at its
 * right-hand end rather than closing on a ceiling nobody found.
 *
 * <p>A production marker is drawn only where production traffic and the tested level measure the
 * same quantity. Virtual users and arrival rate are different quantities; placing one on the other's
 * scale would be a picture of a conversion that does not exist. That is the same refusal
 * {@link com.acltabontabon.vortex.core.capacity.HeadroomCalculator} already makes, and the sentence explaining it
 * to the reader is that calculator's, not one written again here.
 *
 * @param markers        the levels worth drawing, ascending. May be empty
 * @param scaleTo        the largest drawn marker, which sits at the right-hand end; null when empty
 * @param boundaryStatus what the underlying observation established, or why it established nothing
 * @param statement      {@link CapacityObservation#boundary()} verbatim — a complete sentence for
 *                       every status, including the two that establish nothing. Empty when there is
 *                       no observation at all
 * @param measuredAt     when the capacity evidence was recorded; null for a production-only range
 */
public record CapacityRange(
        List<Marker> markers,
        LoadLevel scaleTo,
        BoundaryStatus boundaryStatus,
        String statement,
        Instant measuredAt) {

    /** Fewer than two marks is a reading, not a range. */
    private static final int MINIMUM_FOR_RANGE = 2;

    /**
     * The label for observed production traffic, fixed so every renderer says the same thing.
     *
     * <p>The sibling of {@link CapacityObservation#FAILING_EDGE_LABEL}, and here rather than on
     * {@link ProductionObservation} because it names this marker's role on this picture — the peak
     * is one of several figures an observation carries, and it is the one a capacity range compares
     * against.
     */
    public static final String PRODUCTION_LABEL = "Observed production peak";

    public enum MarkerKind {

        /** What production actually sends the service. Measured, but never a verdict. */
        PRODUCTION,

        /** The highest tested level at which every objective was met. */
        TESTED_CAPACITY,

        /** The lowest tested level at which one was not. */
        FIRST_FAILING
    }

    /**
     * One level worth drawing.
     *
     * @param label the domain's own fixed wording for this kind, passed in rather than composed, so
     *              a figure and its name cannot drift apart between the picture and the table
     */
    public record Marker(MarkerKind kind, LoadLevel level, String label) {

        public Marker {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(level, "level");
            label = label == null ? "" : label;
        }
    }

    public CapacityRange {
        markers = markers == null ? List.of() : List.copyOf(markers);
        Objects.requireNonNull(boundaryStatus, "boundaryStatus");
        statement = statement == null ? "" : statement;
    }

    /**
     * The range a service's latest capacity evidence describes, with production alongside it when
     * the two can honestly share a scale.
     *
     * @param observation the latest capacity evidence; may be null
     * @param production  what production sends the service; may be null
     */
    public static CapacityRange from(CapacityObservation observation, ProductionObservation production) {
        if (observation == null) {
            return production == null ? empty() : productionOnly(production);
        }

        List<Marker> markers = new ArrayList<>(3);

        // Gated on the domain's own answer. An observation whose compliance did not move
        // monotonically with load has not established a capacity, and the tidiness of the resulting
        // picture is not a reason to draw one.
        LoadLevel compliant = observation.isQuotable() ? observation.compliantLevel() : null;
        if (compliant != null) {
            markers.add(new Marker(MarkerKind.TESTED_CAPACITY, compliant, observation.label()));
        }

        // Only ESTABLISHED has a far edge. Under FAR_EDGE_NOT_REACHED nothing failed at all, and the
        // renderer leaves the axis open there instead.
        if (observation.boundaryStatus() == BoundaryStatus.ESTABLISHED) {
            Optional<BoundaryEdge> edge = observation.firstNonCompliantIfPresent();
            edge.ifPresent(failing -> markers.add(new Marker(
                    MarkerKind.FIRST_FAILING, failing.level(), observation.failingEdgeLabel())));
        }

        // The same quantity check HeadroomCalculator makes. Without a tested level to compare
        // against there is nothing to be incompatible with, so production stands on its own scale.
        if (production != null
                && (compliant == null || production.peakRate().sameQuantityAs(compliant))) {
            markers.add(new Marker(MarkerKind.PRODUCTION, production.peakRate(), PRODUCTION_LABEL));
        }

        return build(markers, observation.boundaryStatus(), observation.boundary(),
                observation.observedAt());
    }

    /**
     * A service with production traffic recorded and nothing measured against it yet.
     *
     * <p>Worth drawing on its own. One mark is not a range and says so, but it is the difference
     * between a service Vortex knows something about and one it knows nothing about.
     */
    public static CapacityRange productionOnly(ProductionObservation production) {
        if (production == null) {
            return empty();
        }
        return build(
                List.of(new Marker(MarkerKind.PRODUCTION, production.peakRate(), PRODUCTION_LABEL)),
                BoundaryStatus.NOT_EVALUATED, "", null);
    }

    public static CapacityRange empty() {
        return new CapacityRange(List.of(), null, BoundaryStatus.NOT_EVALUATED, "", null);
    }

    private static CapacityRange build(List<Marker> markers, BoundaryStatus status, String statement,
            Instant measuredAt) {

        List<Marker> ordered = markers.stream()
                .sorted(Comparator.comparingDouble(marker -> marker.level().asDouble()))
                .toList();

        LoadLevel scaleTo = ordered.isEmpty() ? null : ordered.getLast().level();
        return new CapacityRange(ordered, scaleTo, status, statement, measuredAt);
    }

    /** Whether there is anything on this scale worth putting on a page. */
    public boolean isRenderable() {
        return !markers.isEmpty() && scaleTo != null && scaleTo.asDouble() > 0;
    }

    /**
     * Whether this shows a range rather than a single reading.
     *
     * <p>A caller may want to say "production peaks at 182 requests/sec" as a sentence rather than
     * as a one-dot picture.
     */
    public boolean isRange() {
        return markers.size() >= MINIMUM_FOR_RANGE;
    }

    /**
     * Whether the scale should stay open at its right-hand end.
     *
     * <p>Nothing tested failed, so the boundary is above what was reached and closing the scale would
     * draw a ceiling nobody found.
     *
     * <p>The second condition is less obvious and matters more. An open end is a claim about the far
     * side of the <em>tested capacity</em> mark, and it can only be drawn at the end of the scale. If
     * production traffic is running above tested capacity, production is the last mark — and an arrow
     * past it reads as "and production goes on upward from here", which is a statement about traffic
     * that nothing here measured. In that case the scale closes, and the sentence underneath still
     * says no tested level failed.
     */
    public boolean isOpenEnded() {
        if (boundaryStatus != BoundaryStatus.FAR_EDGE_NOT_REACHED || markers.isEmpty()) {
            return false;
        }
        return markers.getLast().kind() == MarkerKind.TESTED_CAPACITY;
    }

    public boolean drawsBoundary() {
        return marker(MarkerKind.TESTED_CAPACITY).isPresent();
    }

    public boolean drawsProduction() {
        return marker(MarkerKind.PRODUCTION).isPresent();
    }

    public boolean drawsFailing() {
        return marker(MarkerKind.FIRST_FAILING).isPresent();
    }

    public Optional<Marker> marker(MarkerKind kind) {
        return markers.stream().filter(marker -> marker.kind() == kind).findFirst();
    }

    /**
     * Where a level sits along the scale, from 0 to 1.
     *
     * <p>A convenience for renderers, as on {@link LoadAxis#position(LoadLevel)}; the meaning is in
     * the fields. Returns 0 for a level measuring a different quantity, which cannot arise for a
     * marker this record built but can for anything a caller places alongside one.
     */
    public double position(LoadLevel level) {
        if (level == null || !isRenderable() || !level.sameQuantityAs(scaleTo)) {
            return 0;
        }
        return Math.clamp(level.asDouble() / scaleTo.asDouble(), 0d, 1d);
    }

    /** The unit every level on this scale is stated in, e.g. {@code requests/sec}. */
    public String unit() {
        return scaleTo == null ? "" : scaleTo.unit();
    }
}
