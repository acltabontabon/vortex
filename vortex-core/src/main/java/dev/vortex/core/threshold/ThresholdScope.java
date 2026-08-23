package dev.vortex.core.threshold;

import dev.vortex.core.shared.OperationId;
import java.util.Optional;

/**
 * What an objective applies to: the run as a whole, or one operation within it.
 *
 * <p>Per-operation objectives matter as soon as a workload exercises more than one operation. A
 * status check answering in 40 ms and an order submission answering in 900 ms average out to a
 * number that describes neither, and an aggregate p95 can sit comfortably inside its objective while
 * the one operation anybody cares about is failing. Scoping the objective is how that stops being
 * invisible.
 *
 * <p>An overall scope and an operation scope are both first-class; neither is a special case of the
 * other, and a workload normally has some of each.
 *
 * @param operation the operation the objective applies to, or {@code null} for the whole run
 */
public record ThresholdScope(OperationId operation) {

    /** Applies to every request in the run, regardless of operation. */
    public static final ThresholdScope OVERALL = new ThresholdScope(null);

    public static ThresholdScope of(OperationId operation) {
        if (operation == null) {
            throw new IllegalArgumentException(
                    "an operation-scoped objective must name an operation; use ThresholdScope.OVERALL "
                            + "for an objective that applies to the whole run");
        }
        return new ThresholdScope(operation);
    }

    public boolean isOverall() {
        return operation == null;
    }

    public Optional<OperationId> operationIfPresent() {
        return Optional.ofNullable(operation);
    }

    /** Suffix appended to a threshold identifier so scoped objectives have distinct ids. */
    public String idSuffix() {
        return isOverall() ? "" : "." + operation.value();
    }

    /** Phrase appended to a plain-language description: {@code ""} or {@code " for createOrder"}. */
    public String describeSuffix() {
        return isOverall() ? "" : " for " + operation.value();
    }

    @Override
    public String toString() {
        return isOverall() ? "overall" : operation.value();
    }
}
