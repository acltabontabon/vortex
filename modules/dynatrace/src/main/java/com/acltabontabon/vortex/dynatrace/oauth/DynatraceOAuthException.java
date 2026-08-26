package com.acltabontabon.vortex.dynatrace.oauth;

/**
 * The Dynatrace SSO token endpoint refused a request, or could not be reached at all.
 *
 * <p>{@code responseBodySnippet} is safe by construction — it is Dynatrace's own response, which
 * never echoes the client secret back — but is still truncated, since an unexpected response could in
 * principle be arbitrarily large.
 */
public final class DynatraceOAuthException extends RuntimeException {

    private static final int MAX_SNIPPET_LENGTH = 500;

    private final int httpStatus;
    private final String responseBodySnippet;

    /** {@code httpStatus} is {@code 0} when the request never got a response (a network failure). */
    public DynatraceOAuthException(String message, int httpStatus, String responseBodySnippet, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
        this.responseBodySnippet = truncate(responseBodySnippet);
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String responseBodySnippet() {
        return responseBodySnippet;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > MAX_SNIPPET_LENGTH ? value.substring(0, MAX_SNIPPET_LENGTH) : value;
    }
}
