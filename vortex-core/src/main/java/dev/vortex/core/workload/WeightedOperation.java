package dev.vortex.core.workload;

import dev.vortex.core.shared.OperationId;
import dev.vortex.core.shared.Weight;
import java.util.Objects;

/** One operation's relative share of a workload's traffic composition. */
public record WeightedOperation(OperationId operationId, Weight weight) {

    public WeightedOperation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(weight, "weight");
    }

    public static WeightedOperation of(OperationId operationId, int weight) {
        return new WeightedOperation(operationId, Weight.of(weight));
    }
}
