package com.acltabontabon.vortex.dynatrace.query;

import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.dynatrace.DynatraceTelemetryQuery;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Builds the {@code execute_dql} tool arguments for each query definition.
 *
 * <p>Isolated in one small class so that correcting a statement against Dynatrace's real DQL grammar
 * — verified by hand against a real endpoint, since no automated test can reach one — never touches
 * the definitions, the normalizer, or the observation source that consume the result.
 */
final class Dql {

    private Dql() {
    }

    static DynatraceTelemetryQuery query(String id, String statement, String organization) {
        return new DynatraceTelemetryQuery(id, DynatraceQueryDefinition.EXECUTE_DQL_TOOL,
                Map.of("dqlStatement", statement, "organization", organization));
    }

    static String throughput(String entityId, TimeWindow window, Duration resolution) {
        return "timeseries requests = sum(dt.service.request.count), by: {dt.entity.service}, "
                + "filter: dt.entity.service == \"" + escape(entityId) + "\", "
                + "interval: " + iso(resolution) + ", "
                + "from: " + instant(window.start()) + ", to: " + instant(window.end());
    }

    static String requestLatency(String entityId, TimeWindow window, Duration resolution) {
        return "timeseries p50 = percentile(dt.service.request.response_time, 50), "
                + "p95 = percentile(dt.service.request.response_time, 95), "
                + "p99 = percentile(dt.service.request.response_time, 99), "
                + "by: {dt.entity.service}, "
                + "filter: dt.entity.service == \"" + escape(entityId) + "\", "
                + "interval: " + iso(resolution) + ", "
                + "from: " + instant(window.start()) + ", to: " + instant(window.end());
    }

    static String failureRate(String entityId, TimeWindow window, Duration resolution) {
        return "timeseries failed = sum(dt.service.request.failure_count), "
                + "total = sum(dt.service.request.count), by: {dt.entity.service}, "
                + "filter: dt.entity.service == \"" + escape(entityId) + "\", "
                + "interval: " + iso(resolution) + ", "
                + "from: " + instant(window.start()) + ", to: " + instant(window.end());
    }

    private static String iso(Duration resolution) {
        return resolution.toSeconds() % 60 == 0 ? (resolution.toMinutes()) + "m" : resolution.toSeconds() + "s";
    }

    /** DQL's {@code from:}/{@code to:} value must be a quoted string literal — a bare ISO-8601
     *  timestamp is not valid DQL grammar there and Dynatrace rejects it with a parse error pointing
     *  at the first digit run it cannot place (e.g. the hour in {@code T18:...}). */
    private static String instant(Instant instant) {
        return "\"" + instant + "\"";
    }

    private static String escape(String entityId) {
        return entityId == null ? "" : entityId.replace("\"", "\\\"");
    }
}
