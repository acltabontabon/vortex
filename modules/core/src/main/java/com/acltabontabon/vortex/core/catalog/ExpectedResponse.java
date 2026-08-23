package com.acltabontabon.vortex.core.catalog;

import java.util.List;
import java.util.Objects;

/**
 * What a successful response to an operation looks like.
 *
 * <p>Without this, "did it work?" collapses into "was it a 5xx?", which is too coarse to be useful.
 * A service that answers every request with a fast, well-formed {@code 404} looks flawless by
 * response code and is comprehensively broken; a service returning {@code 201} where the caller
 * expects {@code 200} is a contract change hiding inside a green run.
 *
 * <p>These become k6 {@code check()} calls, which is k6's own mechanism for exactly this, so a
 * failed expectation is visible in the standard summary rather than only inside Vortex.
 *
 * <p>Expectations are about correctness, not performance. A failed check does not by itself fail the
 * run: it raises the check failure rate, which the error-rate objective then judges. Keeping those
 * separate means a run can report "fast, but answering wrongly", which is a real and important
 * result.
 *
 * @param statuses acceptable HTTP status codes; empty means "any response that is not a server
 *                 error", the default Vortex applies when nobody has said otherwise
 */
public record ExpectedResponse(List<Integer> statuses) {

    /** Anything the server actually answered, short of admitting it broke. */
    public static final ExpectedResponse DEFAULT = new ExpectedResponse(List.of());

    public ExpectedResponse {
        statuses = statuses == null ? List.of() : List.copyOf(statuses);
        for (Integer status : statuses) {
            if (status == null || status < 100 || status > 599) {
                throw new IllegalArgumentException(
                        "expected status must be a valid HTTP status code between 100 and 599 but was "
                                + status);
            }
        }
        long distinct = statuses.stream().distinct().count();
        if (distinct != statuses.size()) {
            throw new IllegalArgumentException("expected statuses must not repeat");
        }
    }

    public static ExpectedResponse ofStatuses(Integer... statuses) {
        return new ExpectedResponse(List.of(statuses));
    }

    public boolean isDefault() {
        return statuses.isEmpty();
    }

    public boolean accepts(int status) {
        return isDefault() ? status > 0 && status < 500 : statuses.contains(status);
    }

    /** Plain-language statement of the expectation, for preflight and reports. */
    public String describe() {
        return isDefault()
                ? "any response that is not a server error"
                : "status " + statuses.stream().map(String::valueOf).toList();
    }
}
