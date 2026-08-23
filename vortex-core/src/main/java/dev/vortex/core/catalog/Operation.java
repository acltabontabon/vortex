package dev.vortex.core.catalog;

import dev.vortex.core.shared.OperationId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One meaningful interaction with the system under test.
 *
 * <p>An operation is a <em>machine-discoverable fact</em>: the specification really does declare
 * {@code POST /orders}. It is re-derived every time an API description is imported, and carries no
 * human decisions — those live in an {@link OperationBinding}, so re-importing a specification never
 * discards somebody's chosen test data.
 *
 * <p>A single operation is a complete performance target. "Can {@code POST /applications} sustain 50
 * submissions per second within its latency objective?" needs no other operation, no synthetic flow
 * and no business framing to be a real and answerable question.
 *
 * <p>The definition is HTTP-shaped because HTTP is what Vortex executes. It is deliberately not
 * generalised further: a protocol-neutral operation with no protocol to be neutral about would be
 * abstraction bought on speculation. A second protocol would arrive here and at
 * {@code dev.vortex.core.plan.PlannedOperation}, which are the two places that know what a request
 * is.
 *
 * @param id              Vortex-assigned stable identifier
 * @param specOperationId the specification's own {@code operationId}, when it declared one
 * @param method          HTTP method
 * @param path            path template, e.g. {@code /orders/{id}}
 * @param summary         short human description from the specification
 * @param tags            specification tags, used to group operations in the UI
 * @param parameters      declared parameters
 * @param requestBody     the body Vortex would send, when the operation takes one
 */
public record Operation(
        OperationId id,
        String specOperationId,
        HttpMethod method,
        String path,
        String summary,
        List<String> tags,
        List<ParameterSpec> parameters,
        RequestBodySpec requestBody) {

    public Operation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(method, "method");
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("operation path must start with '/' but was: " + path);
        }
        tags = tags == null ? List.of() : List.copyOf(tags);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        summary = summary == null ? "" : summary;
    }

    public OperationKind kind() {
        return OperationKind.forMethod(method);
    }

    public Optional<RequestBodySpec> body() {
        return Optional.ofNullable(requestBody).filter(b -> !b.isEmpty());
    }

    public Optional<String> specOperationIdIfPresent() {
        return Optional.ofNullable(specOperationId).filter(s -> !s.isBlank());
    }

    /**
     * Whether a person must approve this operation's request data before it may be executed.
     *
     * <p>True for anything that changes state. A deliberate speed bump: generating traffic that
     * creates or cancels real records is not something a tool should make easy by accident, and
     * Vortex will not execute a mutating operation merely because it managed to generate
     * schema-valid JSON for it.
     */
    public boolean requiresReview() {
        return kind() == OperationKind.MUTATION;
    }

    /**
     * Whether this operation may be executed, given the binding a person has recorded for it.
     *
     * @param binding the recorded binding, or {@code null} when none exists
     */
    public boolean isExecutable(OperationBinding binding) {
        return !requiresReview() || (binding != null && binding.reviewed());
    }

    public Operation withRequestBody(RequestBodySpec newBody) {
        return new Operation(id, specOperationId, method, path, summary, tags, parameters, newBody);
    }

    /** Display label used throughout the UI: {@code POST /orders}. */
    public String label() {
        return method + " " + path;
    }

    /** The primary grouping tag, or {@code "Other"} when the specification declared none. */
    public String primaryTag() {
        return tags.isEmpty() ? "Other" : tags.getFirst();
    }
}
