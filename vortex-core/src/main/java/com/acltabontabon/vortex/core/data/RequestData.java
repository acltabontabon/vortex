package com.acltabontabon.vortex.core.data;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Everything that varies about one request: its parameters, its headers and its body.
 *
 * <p>A bundle rather than four loose maps, for one reason: request data has to be able to
 * <em>compose</em>. Today it composes twice — the values a specification implies, with the values a
 * person decided layered on top. Both are {@code RequestData}, and {@link #layeredOver} is the whole
 * of the merge.
 *
 * <h2>Where this is going, and what that costs today</h2>
 *
 * <p>An operation's request data is currently the only layer a human writes, and it belongs to the
 * service: {@code POST /applications} sends what it sends, and every workload that exercises it
 * inherits that. This is the right default and it is not obviously the final word — a workload that
 * exists to test a rejection path may one day want to override one field without redefining the
 * operation.
 *
 * <p>That is deliberately not built here. What is built is the shape that makes it additive: a layer
 * is a {@code RequestData}, layering is one associative function, and {@code PlanResolver} folds a
 * list of them. Adding a workload-scoped layer later means producing one more {@code RequestData}
 * and putting it in the list. No {@link RequestValue} case changes, no map type changes, and no
 * caller of the resolved plan can tell the difference — which is the test of whether a seam is real.
 *
 * <h2>Merging</h2>
 *
 * <p>Key by key, not wholesale. A layer that sets one header does not discard the base layer's other
 * headers, because the alternative would make overriding a single field mean restating every field
 * beside it. The body is the exception and is replaced whole when present: it is one document, and
 * half of somebody else's JSON is not a document.
 *
 * @param pathValues  values substituted into {@code {placeholders}} in the path
 * @param queryValues query parameters
 * @param headers     headers for this operation alone, layered over the environment's
 * @param body        the base request document, or empty to inherit
 * @param bodyValues  individual body fields bound over that document
 */
public record RequestData(
        Map<String, RequestValue> pathValues,
        Map<String, RequestValue> queryValues,
        Map<String, RequestValue> headers,
        String body,
        Map<BodyFieldPath, RequestValue> bodyValues) {

    public static final RequestData EMPTY =
            new RequestData(Map.of(), Map.of(), Map.of(), "", Map.of());

    public RequestData {
        pathValues = copyOf(pathValues);
        queryValues = copyOf(queryValues);
        headers = copyOf(headers);
        bodyValues = bodyValues == null ? Map.of()
                : Map.copyOf(new TreeMap<>(bodyValues));
        body = body == null ? "" : body;
    }

    private static Map<String, RequestValue> copyOf(Map<String, RequestValue> values) {
        return values == null ? Map.of() : Map.copyOf(values);
    }

    /** Only a body document, as an operation with a schema-generated payload has. */
    public static RequestData ofBody(String body) {
        return new RequestData(Map.of(), Map.of(), Map.of(), body, Map.of());
    }

    /**
     * This layer applied over {@code base}: this layer wins per key, and inherits everything it does
     * not mention.
     */
    public RequestData layeredOver(RequestData base) {
        if (base == null || base.isEmpty()) {
            return this;
        }
        return new RequestData(
                merge(base.pathValues, pathValues),
                merge(base.queryValues, queryValues),
                merge(base.headers, headers),
                body.isBlank() ? base.body : body,
                mergeBody(base.bodyValues, bodyValues));
    }

    private static Map<String, RequestValue> merge(Map<String, RequestValue> base,
            Map<String, RequestValue> over) {
        Map<String, RequestValue> merged = new LinkedHashMap<>(base);
        merged.putAll(over);
        return merged;
    }

    private static Map<BodyFieldPath, RequestValue> mergeBody(Map<BodyFieldPath, RequestValue> base,
            Map<BodyFieldPath, RequestValue> over) {
        Map<BodyFieldPath, RequestValue> merged = new TreeMap<>(base);
        merged.putAll(over);
        return merged;
    }

    public boolean isEmpty() {
        return pathValues.isEmpty() && queryValues.isEmpty() && headers.isEmpty() && body.isBlank()
                && bodyValues.isEmpty();
    }

    /** Whether anything here has to be produced at run time rather than baked into the script. */
    public boolean hasDynamicValues() {
        return allValues().stream().anyMatch(RequestValue::isDynamic);
    }

    /** Every value, regardless of where it is carried. */
    public java.util.List<RequestValue> allValues() {
        java.util.List<RequestValue> values = new java.util.ArrayList<>();
        values.addAll(pathValues.values());
        values.addAll(queryValues.values());
        values.addAll(headers.values());
        values.addAll(bodyValues.values());
        return java.util.List.copyOf(values);
    }

    /** The environment variables this request data depends on, so preflight can check they exist. */
    public Set<String> referencedEnvironmentNames() {
        Set<String> names = new LinkedHashSet<>();
        for (RequestValue value : allValues()) {
            if (value instanceof EnvironmentValue environment) {
                names.addAll(environment.referencedNames());
            }
        }
        return names;
    }

    /** The datasets this request data reads, so they can be validated and staged. */
    public Set<DatasetRef> referencedDatasets() {
        Set<DatasetRef> datasets = new LinkedHashSet<>();
        for (RequestValue value : allValues()) {
            if (value instanceof DatasetValue dataset) {
                datasets.add(dataset.dataset());
            }
        }
        return datasets;
    }

    /** Every value paired with the position it is carried in, for destination-aware validation. */
    public Map<RequestValueTarget, Map<String, RequestValue>> byTarget() {
        Map<RequestValueTarget, Map<String, RequestValue>> byTarget =
                new LinkedHashMap<>();
        byTarget.put(RequestValueTarget.HEADER, headers);
        byTarget.put(RequestValueTarget.PATH, pathValues);
        byTarget.put(RequestValueTarget.QUERY, queryValues);
        Map<String, RequestValue> body = new LinkedHashMap<>();
        bodyValues.forEach((path, value) -> body.put(path.asText(), value));
        byTarget.put(RequestValueTarget.BODY_FIELD, body);
        return byTarget;
    }

    public RequestData withBody(String newBody) {
        return new RequestData(pathValues, queryValues, headers, newBody, bodyValues);
    }
}
