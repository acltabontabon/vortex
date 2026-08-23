package dev.vortex.core.comparison;

/**
 * What a delta is about.
 *
 * <p>Exists so a baseline's validity can refuse exactly the conclusions its degradation undermines,
 * rather than the whole comparison. Refusing every degraded baseline would, early on, refuse almost
 * all of them — partial telemetry is the normal case — while accepting them wholesale would compare
 * against evidence that cannot support the comparison.
 */
public enum DeltaKind {

    LATENCY("Latency"),
    RELIABILITY("Reliability"),
    THROUGHPUT("Throughput"),
    CAPACITY("Capacity"),
    BREAKPOINT_MOVEMENT("Breakpoint movement"),
    HEADROOM("Headroom"),
    RESOURCE("Resource"),
    EFFICIENCY("Efficiency"),
    RUN_QUALITY("Run quality");

    private final String label;

    DeltaKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
