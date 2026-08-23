package dev.vortex.core.application;

import java.util.List;

/**
 * Thrown when a configuration cannot be turned into an executable plan.
 *
 * <p>Carries a list of problems phrased as things a user can act on, rather than a single opaque
 * message. Every user-facing failure in Vortex should answer three questions: what happened, why it
 * might have happened, and what to do next.
 */
public class PlanResolutionException extends RuntimeException {

    private final transient List<String> problems;

    public PlanResolutionException(String message, List<String> problems) {
        super(message);
        this.problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public PlanResolutionException(String message) {
        this(message, List.of(message));
    }

    public List<String> problems() {
        return problems;
    }
}
