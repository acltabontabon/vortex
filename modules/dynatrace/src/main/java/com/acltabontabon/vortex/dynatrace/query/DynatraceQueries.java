package com.acltabontabon.vortex.dynatrace.query;

import java.util.List;

/** The fixed set of deterministic queries Vortex asks Dynatrace over MCP. Nothing else exists. */
public final class DynatraceQueries {

    public static final DynatraceQueryDefinition THROUGHPUT_V1 = new DynatraceQueryDefinition.Throughput();
    public static final DynatraceQueryDefinition REQUEST_LATENCY_V1 = new DynatraceQueryDefinition.RequestLatency();
    public static final DynatraceQueryDefinition FAILURE_RATE_V1 = new DynatraceQueryDefinition.FailureRate();

    private DynatraceQueries() {
    }

    /** The queries a baseline retrieval issues, in the order they are asked. */
    public static List<DynatraceQueryDefinition> baseline() {
        return List.of(THROUGHPUT_V1, REQUEST_LATENCY_V1, FAILURE_RATE_V1);
    }
}
