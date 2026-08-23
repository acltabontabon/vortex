package com.acltabontabon.vortex.core.catalog;

/**
 * Whether an operation is expected to read or to change state.
 *
 * <p>Derived deterministically from the HTTP method. This is a <em>best-effort classification</em>:
 * a {@code GET} can still have side effects and a {@code POST} can be a pure search. It is used to
 * decide when a human review gate applies, never to assert what the service actually does.
 */
public enum OperationKind {

    READ,
    MUTATION;

    public static OperationKind forMethod(HttpMethod method) {
        return method.isMutating() ? MUTATION : READ;
    }
}
