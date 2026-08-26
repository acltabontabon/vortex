package com.acltabontabon.vortex.dynatrace.normalize;

import java.util.List;
import java.util.Objects;

/**
 * A tool result that survived normalization: numeric samples Vortex trusts enough to compute a
 * statistic from.
 *
 * <p>Samples are per-bucket values as reported (not yet turned into a rate) — the caller divides by
 * the resolution it asked for, exactly as {@code DynatraceObservationSource} already does for the
 * REST adapter, so both adapters apply the same conversion rule.
 *
 * @param queryId the query definition id these samples answer
 * @param samples every numeric value found for the query's expected field, in response order
 * @param unit    the unit these samples are in, when the response said so; blank when it did not
 */
public record NormalizedTelemetry(String queryId, List<Double> samples, String unit) {

    public NormalizedTelemetry {
        Objects.requireNonNull(queryId, "queryId");
        samples = samples == null ? List.of() : List.copyOf(samples);
        unit = unit == null ? "" : unit.trim();
    }
}
