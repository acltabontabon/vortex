package dev.vortex.core.target;

import dev.vortex.core.environment.TargetUrl;

/**
 * A lifecycle lease over a resolved runtime target. Deliberately conflates two roles — "here is the
 * address that's actually reachable" and "here is how to release whatever was created to make it
 * so" — because for every target type today the two are decided at the same moment by the same
 * executor call; splitting them into separate types would just make the two halves easy to use
 * inconsistently. If a future target type ever needs the resolved fact to outlive the lease (e.g. a
 * Coordinator-issued handle reused across calls), split then.
 */
public interface PreparedTarget {

    ResolvedTarget resolvedTarget();

    /** Idempotent — safe to call more than once. Never throws; failures are reported in the outcome,
     *  not propagated, because cleanup runs from a {@code finally} block that must not itself become
     *  a new reason a run fails to report its own outcome. */
    CleanupOutcome cleanup();

    /** The unconditional no-op lease every {@link ExternalEndpointTarget} resolves to: nothing was
     *  created, so there is nothing to release. */
    static PreparedTarget external(TargetUrl endpoint) {
        ResolvedTarget resolved = ResolvedTarget.external(endpoint);
        return new PreparedTarget() {
            @Override
            public ResolvedTarget resolvedTarget() {
                return resolved;
            }

            @Override
            public CleanupOutcome cleanup() {
                return CleanupOutcome.NOTHING_TO_DO;
            }
        };
    }
}
