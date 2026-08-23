package dev.vortex.core.capacity;

import dev.vortex.core.shared.Percentages;
import java.math.BigDecimal;
import java.math.MathContext;

/**
 * How much of the observed production traffic the operation mix actually accounts for.
 *
 * <h2>Why this is kept separately from the mix</h2>
 * A mix normalises: three matched operations carrying 50%, 30% and 20% of <em>matched</em> traffic
 * are stored as 50/30/20 whatever share of production they represent between them. That is correct —
 * a mix describes shape, and shape has to sum to one — but on its own it is also how a partial match
 * silently becomes a claim about the whole service.
 *
 * <p>If Vortex matched 80 of every 100 requests to a known operation, the resulting mix describes 80%
 * of production and the reader has to be told so. Narrowing the evidence is acceptable; overstating
 * its completeness is not.
 *
 * <p>The unmatched share is deliberately <em>not</em> turned into a synthetic "other" operation.
 * Vortex can only issue requests against operations in the service catalog, so an invented entry
 * would produce a workload it cannot run — and quietly imply the traffic was understood.
 *
 * @param totalObservedRequests every request the source counted in the window
 * @param matchedRequests       those attributed to an operation Vortex knows about
 */
public record OperationMixCoverage(long totalObservedRequests, long matchedRequests) {

    /** At or above this, the mix accounts for effectively all observed traffic. */
    public static final double COMPLETE_THRESHOLD = 0.99;

    /**
     * Below this, a calibrated mix is not a fair description of production.
     *
     * <p>Four requests in five is a judgement call rather than a law, and it is stated here so it can
     * be argued with rather than discovered in a conditional.
     */
    public static final double REPRESENTATIVE_THRESHOLD = 0.80;

    public OperationMixCoverage {
        if (totalObservedRequests < 0 || matchedRequests < 0) {
            throw new IllegalArgumentException("request counts cannot be negative");
        }
        if (matchedRequests > totalObservedRequests) {
            throw new IllegalArgumentException(
                    "matched requests (" + matchedRequests + ") cannot exceed the total observed ("
                            + totalObservedRequests + ")");
        }
    }

    /** The matched share, between 0 and 1. Zero when nothing was observed at all. */
    public double coverage() {
        return totalObservedRequests == 0 ? 0.0 : (double) matchedRequests / totalObservedRequests;
    }

    public boolean isComplete() {
        return coverage() >= COMPLETE_THRESHOLD;
    }

    public boolean isRepresentative() {
        return coverage() >= REPRESENTATIVE_THRESHOLD;
    }

    public long unmatchedRequests() {
        return totalObservedRequests - matchedRequests;
    }

    /** The matched share as a display percentage, e.g. {@code 80}. */
    public String display() {
        return Percentages.display(
                BigDecimal.valueOf(coverage()).round(new MathContext(4)));
    }

    /** What a reader needs to know, in one sentence. */
    public String describe() {
        if (totalObservedRequests == 0) {
            return "No requests were observed in this window, so the mix accounts for nothing.";
        }
        if (isComplete()) {
            return "This mix accounts for all observed production traffic.";
        }
        return "This mix accounts for " + display() + "% of observed production traffic; "
                + unmatchedRequests() + " of " + totalObservedRequests
                + " requests could not be attributed to an operation Vortex knows about.";
    }
}
