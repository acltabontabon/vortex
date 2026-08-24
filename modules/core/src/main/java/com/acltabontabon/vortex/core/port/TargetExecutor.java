package com.acltabontabon.vortex.core.port;

import com.acltabontabon.vortex.core.application.PreflightCheck;
import com.acltabontabon.vortex.core.target.ExecutionTarget;
import com.acltabontabon.vortex.core.target.PreparedTarget;
import com.acltabontabon.vortex.core.target.TargetCapability;
import com.acltabontabon.vortex.core.target.TargetPreparationException;
import com.acltabontabon.vortex.core.target.TargetPreparationRequest;
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

    /**
     * Releases anything this executor left behind for a run that is no longer in flight.
     *
     * <p>{@link #prepare} hands back a lease that {@code ExecutionService} always releases, on every
     * exit path — but only while its own process lives. Kill Vortex mid-run and that {@code finally}
     * never executes: the run is reconciled as interrupted on the next start, while whatever was
     * started to serve it keeps running, holding its port and its share of the machine, invisible
     * until somebody thinks to look. A stale container is not merely untidy — the next run measures a
     * machine that is quietly busier than it looks.
     *
     * <p>Scoped by the caller rather than by wall-clock age: {@code liveExecutionIds} names the runs
     * that are genuinely still in flight, so a second Vortex working out of the same workspace never
     * has a container pulled out from under it. Anything not on that list belongs to a run that has
     * already ended, however recently.
     *
     * <p>The default releases nothing, which is exactly right for an executor that creates nothing to
     * begin with (an external endpoint is somebody else's to run and never Vortex's to stop).
     *
     * @param liveExecutionIds ids of executions still in flight, whose resources must be left alone
     * @return one description per resource actually released, for the log; empty when there was
     *         nothing to release
     */
    default List<String> releaseOrphans(Set<String> liveExecutionIds) {
        return List.of();
    }
}
