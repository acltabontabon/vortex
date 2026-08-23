package com.acltabontabon.vortex.core.evidence;

import com.acltabontabon.vortex.core.catalog.PayloadProvenance;
import com.acltabontabon.vortex.core.data.RequestValueOrigin;
import com.acltabontabon.vortex.core.metrics.OperationMetrics;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.threshold.ThresholdResult;
import com.acltabontabon.vortex.core.threshold.Verdict;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One operation's figures, with the objectives scoped to it joined on.
 *
 * <p>The join happens once, here, because a per-operation verdict assembled independently by each
 * renderer is a per-operation verdict that will eventually differ between the screen and the export.
 *
 * <p>An aggregate hides a badly behaving operation. A mix in which one endpoint answers in 90 ms and
 * another in 3 seconds produces a respectable-looking overall p95, and the whole point of this
 * breakdown is that the second endpoint cannot disappear into it.
 *
 * @param metrics what was measured; null when the operation issued no traffic at all
 * @param payloadProvenance where the request body came from, so a schema-generated payload is never
 *                          mistaken for one a person validated
 * @param requestData where each configured value came from — sources, never values. A capacity
 *                    figure produced by replaying one account id and one produced from a dataset of
 *                    ten thousand are different results, and this is what tells them apart
 */
public record OperationEvidence(
        OperationId operationId,
        String name,
        String methodAndPath,
        BigDecimal share,
        PayloadProvenance payloadProvenance,
        List<RequestValueOrigin> requestData,
        OperationMetrics metrics,
        List<ThresholdResult> scopedResults) {

    public OperationEvidence {
        Objects.requireNonNull(operationId, "operationId");
        name = name == null || name.isBlank() ? operationId.value() : name;
        methodAndPath = methodAndPath == null ? "" : methodAndPath;
        requestData = requestData == null ? List.of() : List.copyOf(requestData);
        scopedResults = scopedResults == null ? List.of() : List.copyOf(scopedResults);
    }

    /**
     * An operation whose request data was not recorded.
     *
     * <p>For evidence read back from a run that predates request-data provenance, and for the many
     * callers that have nothing to say about it. Absent rather than empty-and-claimed.
     */
    public OperationEvidence(OperationId operationId, String name, String methodAndPath,
            BigDecimal share, PayloadProvenance payloadProvenance, OperationMetrics metrics,
            List<ThresholdResult> scopedResults) {
        this(operationId, name, methodAndPath, share, payloadProvenance, List.of(), metrics,
                scopedResults);
    }

    public boolean hasRequestData() {
        return !requestData.isEmpty();
    }

    public Optional<OperationMetrics> metricsIfPresent() {
        return Optional.ofNullable(metrics);
    }

    /**
     * Whether this operation issued any requests.
     *
     * <p>An operation that was planned and never ran is a finding, not a zero. Rendering it as
     * {@code 0 ms, 0% errors} would read as a flawless result.
     */
    public boolean hasTraffic() {
        return metrics != null && metrics.hasTraffic();
    }

    /**
     * This operation's own verdict, from the objectives scoped to it.
     *
     * <p>{@code NOT_EVALUATED} when no objective named it — which is the truthful answer, because
     * the aggregate objectives say nothing about any individual operation.
     */
    public Verdict verdict() {
        if (scopedResults.isEmpty()) {
            return Verdict.NOT_EVALUATED;
        }
        if (scopedResults.stream().anyMatch(result -> result.verdict() == Verdict.FAIL)) {
            return Verdict.FAIL;
        }
        if (scopedResults.stream().anyMatch(result -> result.verdict() == Verdict.NOT_EVALUATED)) {
            return Verdict.NOT_EVALUATED;
        }
        return Verdict.PASS;
    }

    public boolean hasScopedObjectives() {
        return !scopedResults.isEmpty();
    }

    /** The share of total traffic this operation was allocated, e.g. {@code 40}. */
    public String sharePercent() {
        return share == null ? "" : share.stripTrailingZeros().toPlainString();
    }
}
