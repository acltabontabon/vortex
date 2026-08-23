package com.acltabontabon.vortex.core.capacity;

import com.acltabontabon.vortex.core.analysis.StageObservation;
import java.util.Comparator;
import java.util.List;

/**
 * Whether a run actually established a tested capacity boundary.
 *
 * <h2>Why this is not simply "highest passing level"</h2>
 * Consider a run that reads:
 *
 * <pre>
 * 100 req/s  PASS
 * 200 req/s  FAIL
 * 300 req/s  PASS
 * </pre>
 *
 * Reporting 300 as the tested capacity would be arithmetic rather than evidence. Something in that
 * run was not stable — a noisy neighbour, a cold cache, a dependency that recovered — and the honest
 * answer is that no boundary was established, not that capacity is 300 with an asterisk.
 *
 * <p>This is a monotonicity check and deliberately nothing more. Vortex is not modelling the
 * distribution of a stage's results or deciding how much variance is acceptable; it is asking whether
 * compliance moved in one direction with load, which is the minimum a "boundary" has to mean.
 */
public enum BoundaryStatus {

    /** Compliance fell away once, at one level, and stayed away. */
    ESTABLISHED("established"),

    /** Nothing was violated. The boundary is somewhere above what this run tested. */
    FAR_EDGE_NOT_REACHED("far edge not reached"),

    /**
     * Compliance did not move monotonically with load.
     *
     * <p>Recorded rather than discarded — evidence accumulates (ADR-017) — but never quotable as a
     * capacity figure.
     */
    UNSTABLE("not established: results were not monotonic"),

    /** Objectives could not be evaluated, so nothing was established either way. */
    NOT_EVALUATED("not established: objectives were not evaluated");

    private final String label;

    BoundaryStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Whether the compliant level may be quoted as a tested capacity. */
    public boolean isQuotable() {
        return this == ESTABLISHED || this == FAR_EDGE_NOT_REACHED;
    }

    /**
     * Classifies a run's stages.
     *
     * <p>Ordered by load rather than by the sequence they ran in, because a workload may ramp down as
     * well as up and the question is about level, not about time.
     */
    public static BoundaryStatus of(List<StageObservation> stages) {
        if (stages == null || stages.isEmpty()) {
            return NOT_EVALUATED;
        }

        List<StageObservation> byLoad = stages.stream()
                .sorted(Comparator.comparingDouble(stage -> stage.targetLoad().asDouble()))
                .toList();

        int firstFailure = -1;
        for (int i = 0; i < byLoad.size(); i++) {
            if (!byLoad.get(i).isCompliant()) {
                firstFailure = i;
                break;
            }
        }

        if (firstFailure < 0) {
            return FAR_EDGE_NOT_REACHED;
        }

        // Anything compliant above the first failure means compliance came back, which is what
        // makes the boundary a fiction rather than a measurement.
        for (int i = firstFailure + 1; i < byLoad.size(); i++) {
            if (byLoad.get(i).isCompliant()) {
                return UNSTABLE;
            }
        }
        return ESTABLISHED;
    }
}
