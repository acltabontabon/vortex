package com.acltabontabon.vortex.core.catalog;

import java.util.List;
import java.util.Objects;

/**
 * The request body Vortex would send for an operation.
 *
 * @param mediaType  media type, typically {@code application/json}
 * @param payload    the literal body Vortex would send
 * @param provenance where the payload came from — see {@link PayloadProvenance}
 * @param fields     what the schema says about each field's shape, so the interface can offer a
 *                   suggestion rather than only a sample. Empty for a body Vortex has no schema for
 */
public record RequestBodySpec(String mediaType, String payload, PayloadProvenance provenance,
        List<SchemaHint> fields) {

    public RequestBodySpec {
        Objects.requireNonNull(provenance, "provenance");
        if (mediaType == null || mediaType.isBlank()) {
            mediaType = "application/json";
        }
        if (payload == null) {
            payload = "";
        }
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    /** A body whose schema shape was not captured. */
    public RequestBodySpec(String mediaType, String payload, PayloadProvenance provenance) {
        this(mediaType, payload, provenance, List.of());
    }

    public static RequestBodySpec schemaGenerated(String mediaType, String payload) {
        return new RequestBodySpec(mediaType, payload, PayloadProvenance.SCHEMA_GENERATED);
    }

    public static RequestBodySpec schemaGenerated(String mediaType, String payload,
            List<SchemaHint> fields) {
        return new RequestBodySpec(mediaType, payload, PayloadProvenance.SCHEMA_GENERATED, fields);
    }

    public boolean isEmpty() {
        return payload.isBlank();
    }
}
