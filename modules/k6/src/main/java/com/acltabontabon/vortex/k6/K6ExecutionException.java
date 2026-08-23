package com.acltabontabon.vortex.k6;

/**
 * A failure that a user needs to understand and act on.
 *
 * <p>Carries a short statement of what happened and a longer detail explaining why it might have
 * happened and what to do next, because that is the difference between an error message and a dead
 * end. The underlying cause is preserved for anyone troubleshooting Vortex itself.
 */
public class K6ExecutionException extends RuntimeException {

    private final String detail;

    public K6ExecutionException(String message, String detail) {
        super(message);
        this.detail = detail == null ? "" : detail;
    }

    public K6ExecutionException(String message, String detail, Throwable cause) {
        super(message, cause);
        this.detail = detail == null ? "" : detail;
    }

    /** Why this might have happened, and what to do next. */
    public String detail() {
        return detail;
    }
}
