package com.acltabontabon.vortex.app.adapter.observation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The answer to one instant {@code /api/v1/query}, translated out of Prometheus's own JSON envelope.
 *
 * <p>{@code success} distinguishes a Prometheus-side evaluation failure ({@code status:"error"}) from
 * an empty-but-successful vector — the two look identical as "nothing to report" to a caller that
 * only checks whether a value came back, and they call for different {@code NotRetrieved} messages.
 */
record PrometheusQueryResult(boolean success, List<VectorSample> vector, String errorType, String error) {

    PrometheusQueryResult {
        vector = vector == null ? List.of() : List.copyOf(vector);
        errorType = errorType == null ? "" : errorType;
        error = error == null ? "" : error;
    }

    static PrometheusQueryResult success(List<VectorSample> vector) {
        return new PrometheusQueryResult(true, vector, "", "");
    }

    static PrometheusQueryResult error(String errorType, String error) {
        return new PrometheusQueryResult(false, List.of(), errorType, error);
    }

    /** The value of the first (and, for every query this adapter issues, only) series — absent when
     *  the vector is empty or that series had no usable value (NaN/+Inf/-Inf), never zero. */
    Optional<Double> firstValue() {
        return vector.isEmpty() ? Optional.empty() : vector.get(0).valueIfPresent();
    }

    record VectorSample(Map<String, String> labels, Double value) {
        VectorSample {
            labels = labels == null ? Map.of() : Map.copyOf(labels);
        }

        Optional<Double> valueIfPresent() {
            return Optional.ofNullable(value);
        }
    }
}
