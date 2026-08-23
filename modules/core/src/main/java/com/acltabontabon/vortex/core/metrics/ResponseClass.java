package com.acltabontabon.vortex.core.metrics;

/**
 * What the service said, at the altitude a conclusion is drawn at.
 *
 * <p>Vortex reports an error rate today, and an error rate is a count of things that are not the
 * same. A service returning 503 under load has run out of something; a service returning 404 is
 * being asked for the wrong thing, which is a workload defect rather than a capacity finding. Both
 * are "failures" and only one of them belongs in a capacity conclusion.
 *
 * <p>Protocol-neutral by construction. These are the classes of answer a request-response system
 * gives, not HTTP's status families — a message consumer that rejects a malformed payload and one
 * that fails to process a valid payload divide the same way. The mapping from a wire status to one
 * of these lives in the engine adapter, never here.
 *
 * @see FailureClass for the outcomes where no answer arrived at all
 */
public enum ResponseClass {

    INFORMATIONAL("Informational", false),
    SUCCESS("Success", false),
    REDIRECT("Redirect", false),

    /** The caller asked for something the service would not serve. Usually a workload defect. */
    CLIENT_ERROR("Client error", true),

    /** The service could not serve a request it accepted. The class a capacity finding rests on. */
    SERVER_ERROR("Server error", true),

    /** An answer arrived and Vortex could not classify it. Never silently counted as success. */
    UNKNOWN("Unclassified", false);

    private final String label;
    private final boolean failure;

    ResponseClass(String label, boolean failure) {
        this.label = label;
        this.failure = failure;
    }

    public String label() {
        return label;
    }

    /** Whether a response in this class counts against the service. */
    public boolean isFailure() {
        return failure;
    }
}
