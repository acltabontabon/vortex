package dev.vortex.core.metrics;

/**
 * Why a measurement is missing, when it is.
 *
 * <h2>Why absence needs a cause</h2>
 * "JVM memory telemetry unavailable" tells an engineer nothing they can act on. "{@code
 * jvm.memory.used} was not observed because the Prometheus query returned no matching series" tells
 * them their label selector is wrong; "because the token was rejected" tells them to widen a
 * permission; "because the endpoint could not be reached" tells them to check the address. Three
 * different problems, three different afternoons, and collapsing them into one word wastes all of
 * them.
 *
 * <p>Vortex already reports missing telemetry rather than defaulting it to zero, which is the more
 * important half of being honest about a gap. This is the other half: a gap that explains itself.
 */
public enum TelemetryAvailability {

    /** The measurement was taken. */
    AVAILABLE("observed", ""),

    /**
     * The provider answered, and had nothing for this window.
     *
     * <p>Distinct from a zero reading. A service that received no traffic has not been observed to
     * use 0% of its connection pool; nobody looked.
     */
    NO_DATA("no data", "The provider answered but had no samples for this metric in this window."),

    /** The provider does not publish this measurement at all. */
    UNSUPPORTED("not published",
            "This provider does not expose that measurement, so nothing was collected."),

    /** The provider could not be reached. */
    UNREACHABLE("unreachable",
            "The provider could not be reached from the machine running the test."),

    /** The provider refused the credentials it was given. */
    UNAUTHORIZED("not permitted",
            "The provider rejected the credentials, so the measurement was never returned."),

    /** The provider answered with something Vortex could not read. */
    MALFORMED("unreadable",
            "The provider's response could not be understood, so nothing was taken from it.");

    private final String label;
    private final String explanation;

    TelemetryAvailability(String label, String explanation) {
        this.label = label;
        this.explanation = explanation;
    }

    /** Short form, for a table cell or a badge. */
    public String label() {
        return label;
    }

    /** What happened, in a sentence, for a report. */
    public String explanation() {
        return explanation;
    }

    public boolean isAvailable() {
        return this == AVAILABLE;
    }
}
