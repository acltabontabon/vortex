package dev.vortex.persistence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * The JSON mapper used for stored documents.
 *
 * <p>Configured once, here, because the settings are load-bearing. Reading an old row must not fail
 * because a newer Vortex added a field, and a stored document must not change shape just because a
 * value happened to be null — otherwise every schema evolution becomes a migration.
 *
 * <p>Note that the domain model carries no Jackson annotations: {@code vortex-core} has no
 * dependency on Jackson at all. Mapping is done through records' canonical constructors with
 * parameter names, which is why {@code -parameters} is enabled in the build.
 */
public final class JsonDocuments {

    private JsonDocuments() {
    }

    // Immutable once configured, and thread-safe for concurrent reads/writes, so every caller
    // shares this one instance instead of each repository building its own copy of the same config.
    private static final ObjectMapper INSTANCE = build();

    public static ObjectMapper mapper() {
        return INSTANCE;
    }

    private static ObjectMapper build() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                // Sealed hierarchies and typed map keys, taught from outside the domain.
                .registerModule(new VortexJacksonModule())
                // Old rows must remain readable after a field is added.
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                // Timestamps as ISO-8601 strings: readable in a database browser, and stable.
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }
}
