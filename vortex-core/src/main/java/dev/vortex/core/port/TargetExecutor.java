package dev.vortex.core.port;

import dev.vortex.core.application.PreflightCheck;
import dev.vortex.core.target.ExecutionTarget;
import dev.vortex.core.target.PreparedTarget;
import dev.vortex.core.target.TargetCapability;
import dev.vortex.core.target.TargetPreparationException;
import dev.vortex.core.target.TargetPreparationRequest;
import java.util.List;
import java.util.Set;

/**
 * Makes one declared {@link ExecutionTarget} ready to receive traffic, and knows how to release
 * whatever it created to do so.
 *
 * <p>Capability-oriented rather than type-switched: {@code ExecutionService} picks the one executor
 * that {@link #supports} a plan's target and asks it to {@link #prepare}, without ever branching on
 * which concrete target type it got. Adding a target type — Kubernetes, a Coordinator — means adding
 * one more implementation of this interface, not a new {@code instanceof} chain somewhere in
 * application code.
 */
public interface TargetExecutor {

    /** Whether this executor knows how to prepare the given target. */
    boolean supports(ExecutionTarget target);

    /** What this executor can actually do for the target it supports — see {@link TargetCapability}. */
    Set<TargetCapability> capabilities();

    /**
     * Makes the target ready to receive traffic, returning a lease over whatever that took.
     *
     * @throws TargetPreparationException on any failure that should stop the run before READY
     */
    PreparedTarget prepare(TargetPreparationRequest request);

    /**
     * Checks whether this target is ready to be prepared, without creating or starting anything.
     *
     * <p>Used by preflight and by the configuration UI's "Test Connection" action — neither may ever
     * start a long-lived container merely to validate a form. The default (empty list) is exactly
     * right for a target whose readiness is already checked another way (e.g. {@code
     * ExternalEndpointTargetExecutor}'s target is checked over HTTP by {@code PreflightService}
     * itself) — such an executor does not need to override this at all.
     *
     * @param workspacePath the service's repository location, for target kinds (Compose) that need
     *                       to resolve a file against it; irrelevant for others
     */
    default List<PreflightCheck> checkAvailability(ExecutionTarget target, String workspacePath) {
        return List.of();
    }
}
