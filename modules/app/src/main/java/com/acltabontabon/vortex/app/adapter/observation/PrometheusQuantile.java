package com.acltabontabon.vortex.app.adapter.observation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client-side percentile over a set of samples, matching Prometheus's own {@code quantile()}/
 * {@code quantile_over_time()} interpolation — sort ascending, linear-interpolate between the two
 * nearest ranks — so a percentile Vortex computes from raw {@code query_range} samples means the same
 * thing the subquery-based {@code quantile_over_time} it replaces used to.
 *
 * <p>Kept local to this adapter rather than promoted to {@code core}: nothing else in the codebase
 * computes a percentile client-side — Dynatrace's is computed server-side, by Dynatrace.
 */
final class PrometheusQuantile {

    private PrometheusQuantile() {
    }

    /**
     * @param values not required to be sorted; not mutated
     * @param q      in [0, 1]
     * @return the interpolated value, or {@code null} for an empty input — there is no percentile of
     *         no samples, and that is not the same claim as a percentile of zero
     */
    static Double quantile(List<Double> values, double q) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        if (n == 1) {
            return sorted.get(0);
        }
        double rank = q * (n - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double fraction = rank - lower;
        return sorted.get(lower) + fraction * (sorted.get(upper) - sorted.get(lower));
    }

    /** {@code null} for an empty input, for the same reason {@link #quantile} is — a peak is not
     *  defined over zero samples, and treating it as zero would be exactly the fabrication this
     *  adapter refuses elsewhere. */
    static Double max(List<Double> values) {
        return values == null || values.isEmpty() ? null
                : Collections.max(values);
    }
}
