package com.acltabontabon.vortex.core.target;

/**
 * What happened when a {@link PreparedTarget} was asked to release whatever it created.
 *
 * @param attempted whether cleanup actually tried to release something — {@code false} for a target
 *                  that owned nothing to begin with, such as an external endpoint
 * @param succeeded whether the attempt (if any) succeeded; {@code true} when nothing was attempted,
 *                  since there is nothing to have failed
 * @param detail    diagnostic detail, present when {@code attempted && !succeeded}
 */
public record CleanupOutcome(boolean attempted, boolean succeeded, String detail) {

    public CleanupOutcome {
        detail = detail == null ? "" : detail;
    }

    /** The outcome for a target that never owned anything to release. */
    public static final CleanupOutcome NOTHING_TO_DO = new CleanupOutcome(false, true, "");
}
