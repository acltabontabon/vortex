package dev.vortex.core.catalog;

import java.util.List;
import java.util.Objects;

/**
 * A single request parameter discovered from an API description.
 *
 * @param name        parameter name as declared by the specification
 * @param location    where the parameter is carried
 * @param required    whether the specification marks it required
 * @param schemaType  a short type hint such as {@code string} or {@code integer}; may be {@code null}
 * @param example     an example value taken from the specification, or {@code null} when none was declared
 * @param format      the declared format, such as {@code uuid} or {@code date-time}; empty when none
 * @param enumValues  the values the specification permits, when it constrains them
 */
public record ParameterSpec(
        String name,
        ParameterLocation location,
        boolean required,
        String schemaType,
        String example,
        String format,
        List<String> enumValues) {

    public ParameterSpec {
        Objects.requireNonNull(location, "location");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("parameter name must not be blank");
        }
        name = name.trim();
        format = format == null ? "" : format;
        enumValues = enumValues == null ? List.of()
                : List.copyOf(enumValues.stream().limit(SchemaHint.MAX_ENUM_VALUES).toList());
    }

    /** A parameter whose specification said nothing beyond its type and an example. */
    public ParameterSpec(String name, ParameterLocation location, boolean required,
            String schemaType, String example) {
        this(name, location, required, schemaType, example, "", List.of());
    }

    /** What the specification says about this parameter's shape. */
    public SchemaHint hint() {
        return new SchemaHint(name, schemaType, format, enumValues);
    }
}
