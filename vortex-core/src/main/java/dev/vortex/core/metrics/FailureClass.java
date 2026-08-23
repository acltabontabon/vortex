package dev.vortex.core.metrics;

/**
 * How a request failed, when the distinction changes the diagnosis.
 *
 * <p>"A 503 at 400 requests/sec and a connection reset at 400 requests/sec are different findings."
 * The first says the service was reached and refused the work. The second says it was not reached at
 * all — which may mean the service fell over, or may mean nothing about the service whatsoever,
 * because a connection failure is also what a saturated load generator produces.
 *
 * <p>That ambiguity is why {@link #indicatesTargetMayBeUnavailable()} exists and why it is phrased
 * as <em>may</em>. Clustered connection-class failures are evidence a validity rule weighs; they are
 * never on their own a statement about the service.
 *
 * <p>Transport-neutral: a publisher that times out, one whose connection drops, and one whose broker
 * rejects a valid message divide exactly the same way.
 */
public enum FailureClass {

    /** The service answered, and its answer was an error. */
    APPLICATION("Application error"),

    /** No answer arrived within the time allowed. */
    TIMEOUT("Timeout"),

    /** The connection could not be established, or was reset. */
    CONNECTION("Connection failure"),

    /** The exchange broke below the application: TLS, protocol framing, a malformed response. */
    TRANSPORT("Transport failure"),

    /** The engine reported a failure Vortex could not classify. Counted, never explained away. */
    UNKNOWN("Unclassified failure");

    private final String label;

    FailureClass(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /**
     * Whether this class of failure is consistent with the target having been unreachable.
     *
     * <p>Deliberately not {@code indicatesTargetUnavailable()}. A saturated load generator produces
     * timeouts and connection resets that have nothing to do with the service, so this answers "is
     * this the shape of evidence that question needs?" and not "was the target down?".
     */
    public boolean indicatesTargetMayBeUnavailable() {
        return this == TIMEOUT || this == CONNECTION;
    }
}
