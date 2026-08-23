package com.acltabontabon.vortex.core.application;

import com.acltabontabon.vortex.core.catalog.Operation;
import com.acltabontabon.vortex.core.catalog.ParameterLocation;
import com.acltabontabon.vortex.core.catalog.ParameterSpec;
import com.acltabontabon.vortex.core.data.FixedValue;
import com.acltabontabon.vortex.core.data.RequestData;
import com.acltabontabon.vortex.core.data.RequestValue;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The request data an API description implies, before anybody has decided anything.
 *
 * <p>Values come from the specification's own examples where they exist, and from conservative
 * placeholders where they do not. Both are <em>schema-valid</em> and neither is
 * <em>business-valid</em>: an API description can tell Vortex that {@code accountId} is a string,
 * but not which account ids exist, which are in the right state, or whether reusing one across
 * thousands of iterations will behave sensibly.
 *
 * <p>That distinction is carried through the whole product rather than being glossed over, which is
 * why mutating operations need explicit human review of their request data before they can be
 * executed at all.
 *
 * <p>Everything produced here is a {@link FixedValue}: a specification can describe a value's shape
 * but never where it should come from. That a field is declared {@code format: uuid} says how it
 * looks, not whether it should be freshly generated, drawn from a dataset of existing customers, or
 * held constant. Turning that hint into a <em>suggestion</em> a person can accept is a separate job
 * with a separate class; inventing the answer here would be Vortex guessing at business meaning,
 * which is the failure mode this product is built to avoid.
 */
public final class RequestDataResolver {

    /** Placeholder used when the specification offered no example. Recognisable on sight in logs. */
    private static final String PLACEHOLDER_STRING = "vortex-sample";

    /** The layer an operation's specification contributes, before any human decision. */
    public RequestData specificationLayer(Operation operation) {
        return new RequestData(
                resolvePathValues(operation),
                resolveQueryValues(operation),
                Map.of(),
                operation.body().map(body -> body.payload()).orElse(""),
                Map.of());
    }

    public Map<String, RequestValue> resolvePathValues(Operation operation) {
        return resolve(operation, ParameterLocation.PATH, true);
    }

    public Map<String, RequestValue> resolveQueryValues(Operation operation) {
        return resolve(operation, ParameterLocation.QUERY, false);
    }

    private Map<String, RequestValue> resolve(Operation operation, ParameterLocation location,
            boolean includeOptional) {
        Map<String, RequestValue> values = new LinkedHashMap<>();
        for (ParameterSpec parameter : operation.parameters()) {
            if (parameter.location() != location) {
                continue;
            }
            if (!parameter.required() && !includeOptional) {
                continue;
            }
            values.put(parameter.name(), new FixedValue(valueFor(parameter)));
        }
        return values;
    }

    private String valueFor(ParameterSpec parameter) {
        if (parameter.example() != null && !parameter.example().isBlank()) {
            return parameter.example();
        }
        String type = parameter.schemaType() == null ? "string"
                : parameter.schemaType().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "integer", "int32", "int64", "number", "long" -> "1";
            case "boolean" -> "false";
            default -> PLACEHOLDER_STRING;
        };
    }

    /** Whether every value for this operation came from the specification rather than a placeholder. */
    public boolean allValuesFromSpecification(Operation operation) {
        return operation.parameters().stream()
                .filter(p -> p.location() == ParameterLocation.PATH || p.required())
                .allMatch(p -> p.example() != null && !p.example().isBlank());
    }
}
