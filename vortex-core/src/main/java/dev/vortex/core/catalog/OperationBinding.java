package dev.vortex.core.catalog;

import dev.vortex.core.data.BodyFieldPath;
import dev.vortex.core.data.DatasetRef;
import dev.vortex.core.data.RequestData;
import dev.vortex.core.data.RequestValue;
import dev.vortex.core.shared.OperationId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * How to actually issue one operation: the request data, the expectation, and whether a person has
 * reviewed it.
 *
 * <p>An {@link Operation} is what an API description <em>declares</em> — a machine-discoverable
 * fact, re-derived every time the specification is imported. A binding is what a human
 * <em>decided</em>: this is the account id to use, this is where the customer ids come from, this is
 * what a good answer looks like. Keeping them apart means re-importing a specification never
 * silently discards somebody's carefully chosen test data.
 *
 * <p>Bindings live in {@code vortex.yaml} rather than only in the local database, because a test
 * that can only be reproduced by clicking through one installation of Vortex is not reproducible.
 * That includes the review flag: whether a mutating operation has been approved for execution is a
 * decision worth reviewing in a pull request.
 *
 * <h2>The scope of a binding</h2>
 *
 * <p>Request data belongs to the <em>service's operation</em>, not to a test. {@code POST /orders}
 * sends what it sends, and every workload that exercises it inherits that — which is why a workload
 * names operations by identifier and says nothing about their contents. Attaching request data to a
 * test would mean restating the account id in every workload that touches the endpoint.
 *
 * <p>This is the right default rather than the last word. A workload built to exercise a rejection
 * path may one day want to override a single field. The shape here is what makes that additive:
 * a binding's contribution is a {@link RequestData} layer, and {@code PlanResolver} folds a list of
 * layers. Adding a workload-scoped layer later is one more element in that list — no value type
 * changes, and nothing downstream of the resolved plan can tell the difference.
 *
 * <p>Values may carry secret <em>references</em> such as {@code ${VORTEX_AUTH_TOKEN}}, which are
 * resolved only when the load generator is launched. A resolved secret never reaches this record.
 *
 * @param operationId the catalog operation this binds
 * @param requestData the parameters, headers and body this operation sends
 * @param expect      what a successful response looks like
 * @param reviewed    whether a person has approved this request data for execution
 */
public record OperationBinding(
        OperationId operationId,
        RequestData requestData,
        ExpectedResponse expect,
        boolean reviewed) {

    public OperationBinding {
        Objects.requireNonNull(operationId, "operationId");
        requestData = requestData == null ? RequestData.EMPTY : requestData;
        expect = expect == null ? ExpectedResponse.DEFAULT : expect;
    }

    /** An empty binding: catalog defaults everywhere, not reviewed. */
    public static OperationBinding of(OperationId operationId) {
        return new OperationBinding(operationId, RequestData.EMPTY, ExpectedResponse.DEFAULT, false);
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

    public Optional<String> bodyIfPresent() {
        return body().isBlank() ? Optional.empty() : Optional.of(body());
    }

    /** The datasets this operation reads, so they can be validated and staged. */
    public Set<DatasetRef> referencedDatasets() {
        return requestData.referencedDatasets();
    }

    /** The environment variables this operation depends on, so preflight can check they exist. */
    public Set<String> referencedEnvironmentNames() {
        return requestData.referencedEnvironmentNames();
    }

    public boolean isEmpty() {
        return requestData.isEmpty() && expect.isDefault() && !reviewed;
    }

    public OperationBinding withReviewed(boolean newReviewed) {
        return new OperationBinding(operationId, requestData, expect, newReviewed);
    }

    public OperationBinding withBody(String newBody) {
        return new OperationBinding(operationId, requestData.withBody(newBody), expect, reviewed);
    }

    public OperationBinding withRequestData(RequestData newRequestData) {
        return new OperationBinding(operationId, newRequestData, expect, reviewed);
    }

    public OperationBinding withExpect(ExpectedResponse newExpect) {
        return new OperationBinding(operationId, requestData, newExpect, reviewed);
    }
}
