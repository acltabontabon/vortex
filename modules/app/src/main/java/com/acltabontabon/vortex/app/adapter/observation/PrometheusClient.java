package com.acltabontabon.vortex.app.adapter.observation;

import java.time.Duration;
import java.time.Instant;

/**
 * Prometheus's native HTTP query API, and nothing else — no scraping, no remote-write, no MCP.
 *
 * <p>Kept to exactly the two endpoints Vortex needs. {@code time}/{@code start}/{@code end} are
 * always passed explicitly rather than left to Prometheus's own "now", so every query in one
 * observation is anchored to the same instant recorded in its provenance.
 *
 * <p>The seam a future non-Prometheus-native backend (Mimir, Thanos, Cortex, Amazon Managed Service
 * for Prometheus) would implement, since all of them speak this same query API — not built now, only
 * left room for.
 */
interface PrometheusClient {

    /** {@code GET /api/v1/query} — an instant vector at one point in time. */
    PrometheusQueryResult query(String promql, Instant time);

    /** {@code GET /api/v1/query_range} — a matrix of samples, one per {@code step}, over
     *  {@code [start, end]}. */
    PrometheusRangeResult queryRange(String promql, Instant start, Instant end, Duration step);
}
