package com.acltabontabon.vortex.dynatrace.query;

import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.dynatrace.DynatraceTelemetryQuery;
import java.time.Duration;

/**
 * A fixed, versioned question Vortex knows how to ask Dynatrace and how to interpret the answer to.
 *
 * <p>The version lives in {@link #id()} itself (e.g. {@code dynatrace.throughput.v1}), because that
 * string is what travels into {@code ObservationProvenance.query()} — a shared, provider-neutral
 * field the REST Dynatrace adapter also writes into unmodified. Bumping a definition's DQL or its
 * expected shape means minting a new id, never silently changing what an existing id means.
 *
 * <p>Every implementation calls the same MCP tool, {@code execute_dql} — Dynatrace's official MCP
 * server executes a Dynatrace Query Language statement and returns its result rows. Vortex writes
 * the DQL by hand for each definition; nothing here asks an MCP tool to compose a query, summarize a
 * service, or decide what "typical traffic" means.
 */
public sealed interface DynatraceQueryDefinition
        permits DynatraceQueryDefinition.Throughput, DynatraceQueryDefinition.RequestLatency,
        DynatraceQueryDefinition.FailureRate {

    /** MCP tool every Dynatrace query definition calls. */
    String EXECUTE_DQL_TOOL = "execute_dql";

    /** Versioned identifier, e.g. {@code dynatrace.throughput.v1}. */
    String id();

    /** The unit a valid response is expected to report, for {@code TelemetryNormalizer} to check. */
    String expectedUnit();

    /** The DQL column alias(es) the normalizer should collect numeric samples from. */
    java.util.List<String> valueFields();

    /** Builds the deterministic MCP tool call for one entity over one window. */
    DynatraceTelemetryQuery queryFor(String entityId, TimeWindow window, Duration resolution);

    /** Requests per second, summed across the window and split by resolution bucket. */
    record Throughput() implements DynatraceQueryDefinition {
        @Override
        public String id() {
            return "dynatrace.throughput.v1";
        }

        @Override
        public String expectedUnit() {
            return "requests/sec";
        }

        @Override
        public java.util.List<String> valueFields() {
            return java.util.List.of("requests");
        }

        @Override
        public DynatraceTelemetryQuery queryFor(String entityId, TimeWindow window, Duration resolution) {
            return Dql.query(id(), Dql.throughput(entityId, window, resolution));
        }
    }

    /** Request duration, split into p50/p95/p99. */
    record RequestLatency() implements DynatraceQueryDefinition {
        @Override
        public String id() {
            return "dynatrace.request-latency.v1";
        }

        @Override
        public String expectedUnit() {
            return "milliseconds";
        }

        @Override
        public java.util.List<String> valueFields() {
            return java.util.List.of("p50", "p95", "p99");
        }

        @Override
        public DynatraceTelemetryQuery queryFor(String entityId, TimeWindow window, Duration resolution) {
            return Dql.query(id(), Dql.requestLatency(entityId, window, resolution));
        }
    }

    /** Failed-request share of total requests, as a fraction between 0 and 1. */
    record FailureRate() implements DynatraceQueryDefinition {
        @Override
        public String id() {
            return "dynatrace.failure-rate.v1";
        }

        @Override
        public String expectedUnit() {
            return "fraction";
        }

        @Override
        public java.util.List<String> valueFields() {
            return java.util.List.of("failed", "total");
        }

        @Override
        public DynatraceTelemetryQuery queryFor(String entityId, TimeWindow window, Duration resolution) {
            return Dql.query(id(), Dql.failureRate(entityId, window, resolution));
        }
    }
}
