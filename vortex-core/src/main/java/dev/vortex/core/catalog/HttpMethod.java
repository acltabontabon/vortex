package dev.vortex.core.catalog;

/**
 * HTTP methods Vortex can model.
 *
 * <p>{@link #isMutating()} drives the safety review gate: an operation that can change state is
 * never allowed into an executable workload purely because Vortex managed to generate schema-valid
 * JSON for it.
 */
public enum HttpMethod {

    GET(false),
    HEAD(false),
    OPTIONS(false),
    TRACE(false),
    POST(true),
    PUT(true),
    PATCH(true),
    DELETE(true);

    private final boolean mutating;

    HttpMethod(boolean mutating) {
        this.mutating = mutating;
    }

    public boolean isMutating() {
        return mutating;
    }
}
