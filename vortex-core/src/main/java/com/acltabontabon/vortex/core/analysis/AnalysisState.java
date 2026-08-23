package com.acltabontabon.vortex.core.analysis;

import java.util.EnumSet;
import java.util.Set;

/**
 * The lifecycle of an AI interpretation, kept entirely separate from the execution lifecycle.
 *
 * <pre>
 * NOT_REQUESTED → PENDING → RUNNING → COMPLETED
 *                               ↘ FAILED
 * </pre>
 *
 * <p>An execution owns measurements; an analysis owns an interpretation of them. Keeping the two
 * apart means a model timing out is a failed analysis, not a failed test, and it means an execution
 * can carry several analyses produced by different models or prompt versions over time.
 */
public enum AnalysisState {

    NOT_REQUESTED("Not requested"),
    PENDING("Queued"),
    RUNNING("Analysing"),
    COMPLETED("Completed"),
    FAILED("Failed");

    private final String label;

    AnalysisState(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == NOT_REQUESTED;
    }

    public Set<AnalysisState> allowedNext() {
        return switch (this) {
            case NOT_REQUESTED -> EnumSet.of(PENDING);
            case PENDING -> EnumSet.of(RUNNING, FAILED);
            case RUNNING -> EnumSet.of(COMPLETED, FAILED);
            case COMPLETED, FAILED -> EnumSet.noneOf(AnalysisState.class);
        };
    }

    public boolean canTransitionTo(AnalysisState next) {
        return next != null && allowedNext().contains(next);
    }

    public void requireTransitionTo(AnalysisState next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException("an analysis cannot move from " + this + " to " + next);
        }
    }
}
