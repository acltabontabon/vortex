package dev.vortex.core.catalog;

import java.util.List;
import java.util.Objects;

/**
 * What an API description says about the shape of one value.
 *
 * <p>Kept because it was already being read and then thrown away. The importer knew a field was
 * declared {@code format: uuid} and that another had five permitted values, used both to invent a
 * sample, and discarded the knowledge — so the interface could not offer a user the one thing the
 * specification genuinely knew.
 *
 * <h2>Shape is not meaning</h2>
 *
 * <p>This describes how a value <em>looks</em>. It never describes what the value should
 * <em>be</em>, and the distinction is the whole reason this is a hint rather than a decision.
 * {@code format: uuid} on {@code customerId} tells Vortex the field holds a UUID. It does not tell
 * anyone whether that UUID should be freshly generated, drawn from a dataset of customers who
 * actually exist, or held constant across a run — and those produce three different tests, only one
 * of which is the one somebody wanted.
 *
 * <p>So a hint becomes a suggestion a person accepts, never a default Vortex applies. Guessing at
 * business meaning from a schema is exactly the confidently-wrong behaviour this product exists to
 * avoid.
 *
 * @param field       the value this describes — a parameter name, or a dotted body field path
 * @param type        the declared type, e.g. {@code string}; may be empty
 * @param format      the declared format, e.g. {@code uuid} or {@code date-time}; may be empty
 * @param enumValues  the permitted values, when the specification constrains them
 */
public record SchemaHint(String field, String type, String format, List<String> enumValues) {

    /** Bounded: an enum of two thousand members is a data set, and belongs in one. */
    public static final int MAX_ENUM_VALUES = 64;

    public SchemaHint {
        Objects.requireNonNull(field, "field");
        type = type == null ? "" : type;
        format = format == null ? "" : format;
        enumValues = enumValues == null ? List.of()
                : List.copyOf(enumValues.stream().limit(MAX_ENUM_VALUES).toList());
        if (field.isBlank()) {
            throw new IllegalArgumentException("a schema hint must name the value it describes");
        }
    }

    public boolean hasFormat() {
        return !format.isBlank();
    }

    /** Whether the specification constrains this value to a fixed set. */
    public boolean isConstrained() {
        return !enumValues.isEmpty();
    }

    public boolean isNumeric() {
        return type.equals("integer") || type.equals("number");
    }
}
