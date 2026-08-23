package dev.vortex.k6;

import dev.vortex.core.metrics.FailureClass;
import dev.vortex.core.metrics.ResponseClass;

/**
 * Translates k6's status and error-code tags into Vortex's outcome vocabulary.
 *
 * <p>This table is the reason {@code vortex-core} can report a status distribution without learning
 * what a status code is. k6 knows it emitted {@code error_code: 1211}; the core knows only that some
 * requests failed to connect. Moving this mapping into the domain would put one engine's wire
 * vocabulary inside the module whose whole point is not having one, and would break the first time
 * k6 renumbered a band.
 *
 * <h2>The bands</h2>
 * k6 groups its error codes by cause, and the grouping is stable across versions even though
 * individual codes are not:
 *
 * <pre>
 *   1000-1099  general        1050 is the request timeout; the rest are unclassified
 *   1100-1199  DNS
 *   1200-1299  TCP            1211 is a dial timeout, which is a timeout before it is a connection
 *   1300-1399  TLS
 *   1400-1499  HTTP/2
 *   1500-1599  operating system
 *   1600-1699  HTTP
 *   1700-1799  certificate
 * </pre>
 *
 * <p>An unrecognised code becomes {@link FailureClass#UNKNOWN} rather than being folded into the
 * nearest plausible band. A failure attributed to the wrong cause is worse than one attributed to
 * none, because only the second is visibly a gap.
 */
final class K6OutcomeClassifier {

    /** k6's code for a request that exceeded its timeout. */
    private static final int REQUEST_TIMEOUT = 1050;

    /** k6's code for a connection attempt that timed out — a timeout before it is a refusal. */
    private static final int DIAL_TIMEOUT = 1211;

    /** What one request did, in the terms the core reasons about. */
    record Outcome(ResponseClass responseClass, FailureClass failureClass, String code) {

        boolean isFailure() {
            return failureClass != null;
        }
    }

    private K6OutcomeClassifier() {
    }

    /**
     * Classifies one request from the tags k6 attached to it.
     *
     * @param status           the {@code status} tag; {@code "0"} or blank when no response arrived
     * @param errorCode        the {@code error_code} tag, present only on failures
     * @param expectedResponse the {@code expected_response} tag. A 404 a workload declared as
     *                         expected is a success: it is what {@code http.expectedStatuses} in the
     *                         generated script means, and treating it as a failure would contradict
     *                         the error rate k6 itself computed from the same tag
     */
    static Outcome classify(String status, String errorCode, boolean expectedResponse) {
        int statusCode = parse(status);
        int code = parse(errorCode);

        if (statusCode <= 0) {
            // No response arrived. The error code is the only thing that says why, and when even
            // that is missing the honest answer is that the failure is unclassified.
            return new Outcome(ResponseClass.UNKNOWN, failureFor(code),
                    code > 0 ? String.valueOf(code) : "unknown");
        }

        ResponseClass responseClass = responseFor(statusCode);
        String label = String.valueOf(statusCode);

        if (expectedResponse || !responseClass.isFailure()) {
            return new Outcome(responseClass, null, label);
        }
        // The service answered, and its answer was an error — an application failure regardless of
        // which error it was. Transport-level codes cannot apply: a response arrived.
        return new Outcome(responseClass, FailureClass.APPLICATION, label);
    }

    private static ResponseClass responseFor(int statusCode) {
        return switch (statusCode / 100) {
            case 1 -> ResponseClass.INFORMATIONAL;
            case 2 -> ResponseClass.SUCCESS;
            case 3 -> ResponseClass.REDIRECT;
            case 4 -> ResponseClass.CLIENT_ERROR;
            case 5 -> ResponseClass.SERVER_ERROR;
            default -> ResponseClass.UNKNOWN;
        };
    }

    private static FailureClass failureFor(int code) {
        if (code == REQUEST_TIMEOUT || code == DIAL_TIMEOUT) {
            return FailureClass.TIMEOUT;
        }
        return switch (code / 100) {
            case 11, 12 -> FailureClass.CONNECTION;          // DNS could not resolve, TCP would not open
            case 13, 14, 15, 16, 17 -> FailureClass.TRANSPORT;   // TLS, HTTP/2, OS, HTTP, certificate
            default -> FailureClass.UNKNOWN;                 // including the 1000-1099 general band
        };
    }

    private static int parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
