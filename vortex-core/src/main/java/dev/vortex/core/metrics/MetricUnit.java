package dev.vortex.core.metrics;

/** The unit a measurement is expressed in. Units are never inferred from a metric's name. */
public enum MetricUnit {

    MILLISECONDS("ms"),
    SECONDS("s"),
    REQUESTS_PER_SECOND("requests/sec"),
    VIRTUAL_USERS("VUs"),
    RATIO(""),
    PERCENT("%"),
    COUNT(""),
    BYTES("bytes");

    private final String symbol;

    MetricUnit(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }
}
