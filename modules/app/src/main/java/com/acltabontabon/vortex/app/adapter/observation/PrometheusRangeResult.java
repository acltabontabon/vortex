package com.acltabontabon.vortex.app.adapter.observation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The answer to one {@code /api/v1/query_range}, translated out of Prometheus's matrix envelope.
 *
 * <p>Samples stay as a time series here — {@code (timestamp, value)} pairs, sanitized one at a time —
 * so peak and p95 are computed by Vortex's own code from real samples rather than trusted from an
 * opaque PromQL aggregate function. See {@link PrometheusQuantile}.
 */
record PrometheusRangeResult(boolean success, List<MatrixSeries> matrix, String errorType, String error) {

    PrometheusRangeResult {
        matrix = matrix == null ? List.of() : List.copyOf(matrix);
        errorType = errorType == null ? "" : errorType;
        error = error == null ? "" : error;
    }

    static PrometheusRangeResult success(List<MatrixSeries> matrix) {
        return new PrometheusRangeResult(true, matrix, "", "");
    }

    static PrometheusRangeResult error(String errorType, String error) {
        return new PrometheusRangeResult(false, List.of(), errorType, error);
    }

    /** Every query this adapter issues sums to a single series (an outer {@code sum(...)}), so the
     *  usable values are simply every non-gap sample of the first series — absent when the matrix is
     *  empty. */
    List<Double> firstSeriesValues() {
        if (matrix.isEmpty()) {
            return List.of();
        }
        return matrix.get(0).samples().stream()
                .map(Sample::value)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    record MatrixSeries(Map<String, String> labels, List<Sample> samples) {
        MatrixSeries {
            labels = labels == null ? Map.of() : Map.copyOf(labels);
            samples = samples == null ? List.of() : List.copyOf(samples);
        }
    }

    /** One point of a series. {@code value} is {@code null} for NaN/+Inf/-Inf or a missing field — a
     *  gap, never a zero. */
    record Sample(Instant timestamp, Double value) {
        Optional<Double> valueIfPresent() {
            return Optional.ofNullable(value);
        }
    }
}
