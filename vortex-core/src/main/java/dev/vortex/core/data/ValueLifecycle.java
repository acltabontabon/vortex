package dev.vortex.core.data;

import java.util.Locale;

/**
 * How often a generated value is produced.
 *
 * <p>The distinction is load-bearing rather than decorative. An idempotency key generated once per
 * virtual user makes every request after the first a duplicate submission, which a correct service
 * will reject — producing a run that measured the rejection path and reported it as throughput.
 *
 * <h2>Why the constant is not called {@code PER_REQUEST}</h2>
 *
 * <p>"Per request" is ambiguous in the way that matters: it does not say whether the value is
 * produced once and used wherever it appears, or produced afresh at every point it is read. Vortex
 * means the first, exactly — a value is produced once per <em>execution of its operation</em>,
 * bound, and then used. The generated script makes this visible by assigning each generated value to
 * a constant at the top of the operation's function, so a reader can see that the header and the
 * body field carrying the same idempotency key carry the <em>same</em> key.
 *
 * <p>The interface says "every request", because that is what it means to a person configuring one.
 * The name says what it means to the machine.
 *
 * <h2>Why there is no once-per-run</h2>
 *
 * <p>A third lifecycle — one value shared by every virtual user for the whole run — needs somewhere
 * to execute before the workload begins, which is k6's {@code setup()}. That is the same seam a
 * captured prerequisite value needs, and building it for one generator would be a workflow engine
 * with a single feature. Both arrive together, or neither does. See ADR-036.
 */
public enum ValueLifecycle {

    /**
     * Produced once for each execution of the operation, then used wherever that value appears.
     *
     * <p>The default, and the right answer for keys and identifiers. Two <em>different</em> values
     * both configured as generated UUIDs are two different values and receive two different UUIDs;
     * this lifecycle governs the reuse of one value, not the relationship between two.
     */
    PER_OPERATION_EXECUTION("per-request", "every request"),

    /**
     * Produced once per virtual user, held for that user's whole run.
     *
     * <p>Corresponds to k6's init context, which runs once per VU. Use it when a value identifies
     * the caller rather than the call — a session token stands in for one client, not one request.
     */
    PER_VU("per-vu", "every virtual user");

    private final String key;
    private final String label;

    ValueLifecycle(String key, String label) {
        this.key = key;
        this.label = label;
    }

    /** The token used in {@code vortex.yaml}. */
    public String key() {
        return key;
    }

    /** How this lifecycle is named in the interface. */
    public String label() {
        return label;
    }

    public static ValueLifecycle defaultLifecycle() {
        return PER_OPERATION_EXECUTION;
    }

    public static ValueLifecycle fromKey(String value) {
        if (value == null || value.isBlank()) {
            return defaultLifecycle();
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        for (ValueLifecycle lifecycle : values()) {
            if (lifecycle.key.equals(normalised)) {
                return lifecycle;
            }
        }
        throw new IllegalArgumentException(
                "unknown value lifecycle '" + value + "'. Use 'per-request' (a new value for each "
                        + "request) or 'per-vu' (one value per virtual user).");
    }
}
