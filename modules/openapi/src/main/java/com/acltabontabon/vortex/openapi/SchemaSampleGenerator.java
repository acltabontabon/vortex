package com.acltabontabon.vortex.openapi;

import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a sample request body from a schema.
 *
 * <p>What this produces is <em>schema-valid</em> and nothing more. It knows that {@code accountId}
 * is a string; it has no idea which account ids exist, which are in the right state, or whether the
 * same one can be reused ten thousand times in a row. That gap is the reason every generated
 * payload is labelled {@link com.acltabontabon.vortex.core.catalog.PayloadProvenance#SCHEMA_GENERATED} and why
 * mutating operations need a person to look at their request data before they can be executed.
 *
 * <p>Values from the specification's own {@code example} fields are preferred, since an author who
 * bothered to write an example usually wrote a realistic one.
 */
final class SchemaSampleGenerator {

    /** Guards against pathological or circular schemas producing unbounded output. */
    private static final int MAX_DEPTH = 6;
    private static final int MAX_PROPERTIES = 40;

    Object sampleFor(Schema<?> schema) {
        return sampleFor(schema, 0, new java.util.HashSet<>());
    }

    private Object sampleFor(Schema<?> schema, int depth, Set<Schema<?>> seen) {
        if (schema == null || depth > MAX_DEPTH || !seen.add(schema)) {
            return null;
        }
        try {
            if (schema.getExample() != null) {
                return schema.getExample();
            }
            if (schema.getDefault() != null) {
                return schema.getDefault();
            }
            if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
                return schema.getEnum().getFirst();
            }

            String type = resolveType(schema);
            return switch (type) {
                case "object" -> objectSample(schema, depth, seen);
                case "array" -> arraySample(schema, depth, seen);
                case "integer" -> 1;
                case "number" -> 1.0;
                case "boolean" -> true;
                default -> stringSample(schema);
            };
        } finally {
            seen.remove(schema);
        }
    }

    private String resolveType(Schema<?> schema) {
        if (schema.getType() != null) {
            return schema.getType();
        }
        if (schema.getTypes() != null && !schema.getTypes().isEmpty()) {
            // OpenAPI 3.1 allows a set of types; the first non-null one is the useful choice.
            return schema.getTypes().stream()
                    .filter(candidate -> !"null".equals(candidate))
                    .findFirst()
                    .orElse("string");
        }
        if (schema.getProperties() != null) {
            return "object";
        }
        if (schema.getItems() != null) {
            return "array";
        }
        return "string";
    }

    private Map<String, Object> objectSample(Schema<?> schema, int depth, Set<Schema<?>> seen) {
        Map<String, Object> sample = new LinkedHashMap<>();
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null) {
            return sample;
        }
        List<String> required = schema.getRequired() == null ? List.of() : schema.getRequired();

        int emitted = 0;
        for (Map.Entry<String, Schema> property : properties.entrySet()) {
            if (emitted++ >= MAX_PROPERTIES) {
                break;
            }
            // Required properties always; optional ones too, because a payload that omits them
            // exercises a different code path than a realistic client would.
            Object value = sampleFor(property.getValue(), depth + 1, seen);
            if (value != null || required.contains(property.getKey())) {
                sample.put(property.getKey(), value);
            }
        }
        return sample;
    }

    private List<Object> arraySample(Schema<?> schema, int depth, Set<Schema<?>> seen) {
        Object item = sampleFor(schema.getItems(), depth + 1, seen);
        List<Object> sample = new ArrayList<>();
        if (item != null) {
            sample.add(item);
        }
        return sample;
    }

    private String stringSample(Schema<?> schema) {
        String format = schema.getFormat() == null ? "" : schema.getFormat();
        return switch (format) {
            case "date" -> "2026-01-01";
            case "date-time" -> "2026-01-01T00:00:00Z";
            case "uuid" -> "00000000-0000-4000-8000-000000000000";
            case "email" -> "sample@example.invalid";
            case "uri", "url" -> "https://example.invalid/sample";
            case "byte" -> "dm9ydGV4";
            default -> "vortex-sample";
        };
    }
}
