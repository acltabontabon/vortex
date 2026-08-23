package dev.vortex.core.target;

import dev.vortex.core.execution.FailureReason;
import java.util.Objects;

/**
 * Thrown by {@link dev.vortex.core.port.TargetExecutor#prepare} on any failure that should stop the
 * run before it reaches {@code READY} — the target could not be made ready to receive traffic.
 *
 * <p>Carries the {@link FailureReason} the run should be marked with, so a target executor is the
 * one place that knows why its own preparation failed and {@code ExecutionService} does not have to
 * guess from the exception's shape.
 */
public final class TargetPreparationException extends RuntimeException {

    private final FailureReason reason;

    public TargetPreparationException(FailureReason reason, String detail) {
        super(detail);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public FailureReason reason() {
        return reason;
    }
}
