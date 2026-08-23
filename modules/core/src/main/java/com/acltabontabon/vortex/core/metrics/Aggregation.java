package com.acltabontabon.vortex.core.metrics;

/**
 * How a measurement was reduced over its time window.
 *
 * <p>A maximum and a mean over the same window can differ by an order of magnitude, so the
 * aggregation travels with the value rather than being assumed.
 */
public enum Aggregation {

    MEAN("mean"),
    MIN("min"),
    MAX("max"),
    P50("p50"),
    P90("p90"),
    P95("p95"),
    P99("p99"),
    SUM("sum"),
    LAST("last"),
    RATE("rate");

    private final String label;

    Aggregation(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
