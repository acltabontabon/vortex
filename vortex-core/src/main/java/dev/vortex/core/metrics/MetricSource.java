package dev.vortex.core.metrics;

/**
 * Where a measurement came from.
 *
 * <p>Provenance is not bookkeeping. When an analysis states "CPU remained below 58%", the value of
 * that statement depends entirely on whether the number came from the load generator, from the
 * service's own metrics endpoint, or from a person typing it into a form.
 */
public enum MetricSource {

    /** Measured by the load generator: what the client observed. */
    K6("k6", "Load generator"),

    /** Collected from the service under test via its metrics endpoint. */
    ACTUATOR("actuator", "Service metrics endpoint"),

    /** Collected from a Prometheus-compatible endpoint. */
    PROMETHEUS("prometheus", "Prometheus"),

    /** Collected from Dynatrace. */
    DYNATRACE("dynatrace", "Dynatrace"),

    /** Entered by a person, for example an observed production peak. */
    USER_PROVIDED("user", "Provided by you"),

    /** Computed by Vortex from other measurements. */
    DERIVED("derived", "Calculated by Vortex");

    private final String id;
    private final String label;

    MetricSource(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }
}
