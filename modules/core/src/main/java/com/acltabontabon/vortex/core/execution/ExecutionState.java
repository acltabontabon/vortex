package com.acltabontabon.vortex.core.execution;

import java.util.EnumSet;
import java.util.Set;

/**
 * The lifecycle of a performance test run.
 *
 * <pre>
 * CREATED → VALIDATING → READY → STARTING → RUNNING → COLLECTING → EVALUATING → COMPLETED
 *                  ↘ FAILED        ↘ FAILED     ↘ CANCELLED ↙          ↘ FAILED
 * </pre>
 *
 * <p>Note what is absent: there is no {@code ANALYZING} state. AI interpretation is a separate
 * resource with its own lifecycle ({@code com.acltabontabon.vortex.core.analysis.AnalysisState}), attached to a
 * completed execution rather than embedded in it.
 *
 * <p>That separation is deliberate and has three consequences worth stating plainly. A language
 * model being slow, absent or wrong can never turn a successful measurement into a failed one.
 * {@code vortex run peak --headless} finishes deterministically, so continuous integration never
 * depends on an inference service to decide whether a build passed. And an execution can be
 * re-analysed later with a better model without disturbing the measurements, which are immutable.
 */
public enum ExecutionState {

    /** The run has been requested but nothing has been checked yet. */
    CREATED("Created"),

    /** Preflight checks are running: configuration, target reachability, engine availability, policy. */
    VALIDATING("Validating"),

    /** Preflight passed. The run is waiting to start. */
    READY("Ready"),

    /** The engine process is being launched. */
    STARTING("Starting"),

    /** Traffic is being generated. */
    RUNNING("Running"),

    /** Traffic has stopped; results and artifacts are being gathered. */
    COLLECTING("Collecting results"),

    /** Thresholds and breakpoints are being computed deterministically. */
    EVALUATING("Evaluating"),

    /** The run finished and has a verdict. Measurements are now immutable. */
    COMPLETED("Completed"),

    /** The run could not be completed. The reason is recorded on the execution. */
    FAILED("Failed"),

    /** A person stopped the run before it finished. */
    CANCELLED("Cancelled");

    private final String label;

    ExecutionState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /** Whether an engine process may be running while the execution is in this state. */
    public boolean isActive() {
        return this == STARTING || this == RUNNING || this == COLLECTING;
    }

    public Set<ExecutionState> allowedNext() {
        return switch (this) {
            case CREATED -> EnumSet.of(VALIDATING, CANCELLED, FAILED);
            case VALIDATING -> EnumSet.of(READY, FAILED, CANCELLED);
            case READY -> EnumSet.of(STARTING, CANCELLED, FAILED);
            case STARTING -> EnumSet.of(RUNNING, FAILED, CANCELLED);
            case RUNNING -> EnumSet.of(COLLECTING, FAILED, CANCELLED);
            case COLLECTING -> EnumSet.of(EVALUATING, FAILED, CANCELLED);
            case EVALUATING -> EnumSet.of(COMPLETED, FAILED);
            case COMPLETED, FAILED, CANCELLED -> EnumSet.noneOf(ExecutionState.class);
        };
    }

    public boolean canTransitionTo(ExecutionState next) {
        return next != null && allowedNext().contains(next);
    }

    /**
     * @throws IllegalStateException when the transition is not permitted
     */
    public void requireTransitionTo(ExecutionState next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException(
                    "an execution cannot move from " + this + " to " + next
                            + (isTerminal() ? " because " + this + " is a final state" : ""));
        }
    }
}
