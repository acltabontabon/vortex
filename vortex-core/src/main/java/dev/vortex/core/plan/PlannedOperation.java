package dev.vortex.core.plan;

import dev.vortex.core.catalog.ExpectedResponse;
import dev.vortex.core.catalog.HttpMethod;
import dev.vortex.core.catalog.PayloadProvenance;
import dev.vortex.core.data.BodyFieldPath;
import dev.vortex.core.data.DatasetRef;
import dev.vortex.core.data.FixedValue;
import dev.vortex.core.data.RequestData;
import dev.vortex.core.data.RequestValue;
import dev.vortex.core.shared.OperationId;
import dev.vortex.core.shared.Percentages;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.workload.AllocatedRate;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * An operation resolved into everything the load generator needs to issue the request, together with
 * the share of the workload allocated to it.
 *
 * <p>The plan is self-contained by design: it copies the operation details rather than referencing a
 * catalog that may later be re-imported. A report from six months ago must describe the test that
 * actually ran, not the test that today's configuration would produce.
 *
 * <p>The share and rate are plain values here rather than the guarded {@link AllocatedRate}, because
 * a plan is a snapshot that has to survive a round trip to disk and be readable six months later.
 * The guard lives where the mistake is actually made: {@code RateAllocator} is the only thing that
 * can divide a total, {@link #driving} is the only way to put a rate on a planned operation, and it
 * accepts nothing else. By the time a plan exists the division has already happened and been
 * checked — re-guarding it on the way back out of storage would protect nothing.
 *
 * <p>The rate is absent for a concurrency workload, which controls virtual users rather than
 * arrivals and always drives a single operation.
 *
 * @param operationId   catalog identifier, kept for traceability
 * @param name          display label, e.g. {@code POST /orders}
 * @param k6ScenarioKey the k6 scenario this operation is executed under; assigned once during
 *                      resolution and the authoritative way to attribute measurements back
 * @param method        HTTP method
 * @param pathTemplate  path with {@code {placeholders}} intact
 * @param requestData   the resolved request data — path and query values, headers, the base body and
 *                      the fields bound over it. Values may be secret references, never secret values
 * @param body          request body, or empty
 * @param provenance    where the body came from — carried into reports so a schema-generated payload
 *                      is never mistaken for business-validated data
 * @param expect        what a successful response looks like
 * @param share         this operation's fraction of total traffic, in the range (0, 1]
 * @param arrivalRate   its allocated share of the total arrival rate, or {@code null} under a
 *                      concurrency workload
 */
public record PlannedOperation(
        OperationId operationId,
        String name,
        String k6ScenarioKey,
        HttpMethod method,
        String pathTemplate,
        RequestData requestData,
        PayloadProvenance provenance,
        ExpectedResponse expect,
        BigDecimal share,
        RequestsPerSecond arrivalRate) {

    public PlannedOperation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(provenance, "provenance");
        if (pathTemplate == null || !pathTemplate.startsWith("/")) {
            throw new IllegalArgumentException("operation path must start with '/' but was: " + pathTemplate);
        }
        if (k6ScenarioKey == null || k6ScenarioKey.isBlank()) {
            throw new IllegalArgumentException(
                    "operation " + operationId + " has no workload key. Keys are assigned during plan "
                            + "resolution and are how measurements are attributed back to operations.");
        }
        name = name == null ? method + " " + pathTemplate : name;
        requestData = requestData == null ? RequestData.EMPTY : requestData;
        expect = expect == null ? ExpectedResponse.DEFAULT : expect;
        share = share == null ? BigDecimal.ONE : share;
        if (share.signum() <= 0) {
            throw new IllegalArgumentException(
                    "planned operation " + operationId + " has a share of " + share.toPlainString()
                            + ". An operation that receives no traffic does not belong in a plan.");
        }
    }

    /**
     * A planned operation driven at an allocated arrival rate.
     *
     * <p>Takes an {@link AllocatedRate} rather than a bare number, and that is the whole point: the
     * only way to obtain one is to ask {@code RateAllocator} to divide a total, so "every operation
     * runs at the full rate" cannot be expressed on this path.
     */
    public static PlannedOperation driving(OperationId operationId, String name, String k6ScenarioKey,
            HttpMethod method, String pathTemplate, RequestData requestData,
            PayloadProvenance provenance, ExpectedResponse expect, AllocatedRate allocated) {

        if (allocated != null && !allocated.operationId().equals(operationId)) {
            throw new IllegalArgumentException(
                    "planned operation " + operationId + " carries a rate allocated to "
                            + allocated.operationId());
        }
        return new PlannedOperation(operationId, name, k6ScenarioKey, method, pathTemplate,
                requestData, provenance, expect,
                allocated == null ? BigDecimal.ONE : allocated.share(),
                allocated == null ? null : allocated.rate());
    }

    /** The name of the exported JavaScript function that issues this request. */
    public String execFunction() {
        return "op_" + k6ScenarioKey;
    }

    /** This operation's share as a display percentage: {@code 60}, {@code 33.3}, {@code 100}. */
    public String sharePercent() {
        return Percentages.display(share);
    }

    /** This operation's allocated arrival rate, absent under a concurrency workload. */
    public Optional<RequestsPerSecond> arrivalRateIfPresent() {
        return Optional.ofNullable(arrivalRate);
    }

    public Map<String, RequestValue> pathValues() {
        return requestData.pathValues();
    }

    public Map<String, RequestValue> queryValues() {
        return requestData.queryValues();
    }

    public Map<String, RequestValue> headers() {
        return requestData.headers();
    }

    public Map<BodyFieldPath, RequestValue> bodyValues() {
        return requestData.bodyValues();
    }

    public String body() {
        return requestData.body();
    }

    public boolean hasBody() {
        return !body().isBlank();
    }

    public boolean isMutating() {
        return method.isMutating();
    }

    /**
     * Whether any part of this request has to be produced at run time.
     *
     * <p>The generator asks, because an operation whose values are all fixed compiles to exactly the
     * script it always did. Emitting a dataset lookup and a value-binding preamble for a request that
     * sends the same three constants every time would make the simple case pay for the complicated
     * one, in the one artifact this product asks people to read.
     */
    public boolean hasDynamicRequestData() {
        return requestData.hasDynamicValues();
    }

    /** The datasets this operation reads. */
    public java.util.Set<DatasetRef> referencedDatasets() {
        return requestData.referencedDatasets();
    }

    /** The environment variables this operation depends on. */
    public java.util.Set<String> referencedEnvironmentNames() {
        return requestData.referencedEnvironmentNames();
    }

    /**
     * The path with placeholder values substituted, as the request will be issued.
     *
     * <p>Only meaningful when every path value is fixed. When one is not, the substitution cannot
     * happen here — the value does not exist until the request is issued — and the generated script
     * assembles the path instead. {@link #hasFixedPath()} is how the generator decides which.
     */
    public String resolvedPath() {
        String resolved = pathTemplate;
        for (Map.Entry<String, RequestValue> entry : pathValues().entrySet()) {
            if (entry.getValue() instanceof FixedValue fixed) {
                resolved = resolved.replace("{" + entry.getKey() + "}", fixed.literal());
            }
        }
        return resolved;
    }

    /** Whether the path can be resolved now, rather than when the request is issued. */
    public boolean hasFixedPath() {
        return pathValues().values().stream().noneMatch(RequestValue::isDynamic);
    }
}
