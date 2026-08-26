package com.acltabontabon.vortex.dynatrace.normalize;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A tool result that survived normalization: numeric values Vortex trusts, one list per field name
 * the query definition asked about.
 *
 * <p>What each list holds depends on how the query was written. {@code dynatrace.throughput.v1}'s
 * DQL ends in a {@code summarize} pipeline (see {@code Dql#throughput}), so each of its fields
 * ({@code peak}/{@code average}/{@code p95}) resolves to exactly one Dynatrace-computed value — the
 * caller reads it directly rather than deriving a statistic itself. A query that instead hands back
 * raw per-bucket samples (as {@code dynatrace.request-latency.v1} and {@code dynatrace.failure-rate.v1}
 * still do — see {@code Dql}) would have more than one value per field, for the caller to reduce.
 *
 * @param queryId       the query definition id these values answer
 * @param valuesByField every numeric value found for each of the query's expected field names, in
 *                      response order
 * @param unit          the unit the response volunteered, when it did; blank when it did not
 */
public record NormalizedTelemetry(String queryId, Map<String, List<Double>> valuesByField, String unit) {

    public NormalizedTelemetry {
        Objects.requireNonNull(queryId, "queryId");
        valuesByField = valuesByField == null ? Map.of() : Map.copyOf(valuesByField);
        unit = unit == null ? "" : unit.trim();
    }
}
