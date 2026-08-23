package dev.vortex.core.shared;

/**
 * The magnitude of applied load, in whichever quantity the workload model actually controls.
 *
 * <p>Vortex supports two workload models and they control different things. An open workload
 * controls the <em>arrival rate</em>: requests are issued on a schedule regardless of how the
 * service responds. A closed workload controls <em>concurrency</em>: a fixed population of virtual
 * users each issue their next request only once the previous one returns.
 *
 * <p>Everything downstream of the workload — stages, breakpoints, saturation ranges, tested capacity
 * — needs to talk about "the level at which this happened" without caring which quantity was
 * controlled. That is what this type is for. It deliberately carries a {@link #unit()} so no figure
 * can be rendered without saying what it counts: "147" is a rumour, "147 requests/sec" and
 * "147 VUs" are facts, and they are not the same fact.
 *
 * <p>The two implementations never convert into one another. Deriving a request rate from a virtual
 * user count requires knowing the latency of every operation involved, which is a measurement rather
 * than a property of the workload.
 */
public sealed interface LoadLevel permits RequestsPerSecond, Concurrency {

    /** The magnitude as a plain number, for arithmetic and charting. */
    double asDouble();

    /** The magnitude formatted for a human, without its unit: {@code 40.2}, {@code 50}. */
    String display();

    /** What the number counts: {@code requests/sec} or {@code VUs}. */
    String unit();

    /** Magnitude and unit together, as it should appear in any user-facing text. */
    default String displayWithUnit() {
        return display() + " " + unit();
    }

    /** Whether this level and {@code other} measure the same quantity and may be compared. */
    default boolean sameQuantityAs(LoadLevel other) {
        return other != null && unit().equals(other.unit());
    }
}
