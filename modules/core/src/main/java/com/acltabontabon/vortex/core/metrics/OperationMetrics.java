package com.acltabontabon.vortex.core.metrics;

import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.LoadLevel;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import java.util.Objects;
import java.util.Optional;

/**
 * Measurements for a single operation within a workload.
 *
 * <p>Per-operation figures are what turn a mixed-workload result from a single blurred number into
 * something actionable. A run can pass overall while one operation is failing badly, and an
 * aggregate would hide that entirely — which matters most in exactly the situation the aggregate
 * looks healthiest, because the failing operation is usually the low-volume one.
 *
 * @param operationId  which operation these figures describe
 * @param name         display label, e.g. {@code POST /orders}
 * @param targetLoad   the level this operation was driven at, in whichever quantity the workload
 *                     controlled; absent for an imported script Vortex did not plan
 * @param achievedRate requests per second actually observed for this operation
 * @param requests     requests issued
 * @param failures     requests that failed
 * @param latency      latency distribution for this operation alone
 * @param reliability  what kind of outcomes this operation alone produced. Per-operation because
 *                     "the read times out while the write returns 500" is a sentence an aggregate
 *                     cannot say, and one operation's outcomes must never be attributed to another
 */
public record OperationMetrics(
        OperationId operationId,
        String name,
        LoadLevel targetLoad,
        RequestsPerSecond achievedRate,
        long requests,
        long failures,
        LatencyPercentiles latency,
        ReliabilityBreakdown reliability) {

    public OperationMetrics {
        Objects.requireNonNull(operationId, "operationId");
        name = name == null || name.isBlank() ? operationId.value() : name;
        latency = latency == null ? LatencyPercentiles.empty() : latency;
        reliability = reliability == null ? ReliabilityBreakdown.notReported() : reliability;
        if (requests < 0 || failures < 0) {
            throw new IllegalArgumentException("operation counters must not be negative");
        }
        if (failures > requests) {
            throw new IllegalArgumentException(
                    "failures (" + failures + ") cannot exceed requests (" + requests + ") for "
                            + operationId);
        }
    }

    /**
     * Measurements for an operation whose engine reported no outcome classification.
     *
     * <p>Retained at the old arity so widening the record does not mean editing every construction
     * site that has nothing to put in the new field — and, more importantly, so no site is tempted
     * to pass an empty distribution that reads as "everything succeeded".
     */
    public OperationMetrics(OperationId operationId, String name, LoadLevel targetLoad,
            RequestsPerSecond achievedRate, long requests, long failures,
            LatencyPercentiles latency) {
        this(operationId, name, targetLoad, achievedRate, requests, failures, latency,
                ReliabilityBreakdown.notReported());
    }

    public long successes() {
        return requests - failures;
    }

    public ErrorRate errorRate() {
        return ErrorRate.of(failures, requests);
    }

    public Optional<RequestsPerSecond> achievedRateIfPresent() {
        return Optional.ofNullable(achievedRate);
    }

    public Optional<LoadLevel> targetLoadIfPresent() {
        return Optional.ofNullable(targetLoad);
    }

    /**
     * Whether this operation issued any requests at all.
     *
     * <p>Checked before evaluating an objective scoped to it: an operation that produced no traffic
     * cannot have met or missed a latency target, and reporting either would be a fabrication.
     */
    public boolean hasTraffic() {
        return requests > 0;
    }
}
