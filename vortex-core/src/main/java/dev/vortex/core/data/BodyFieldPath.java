package dev.vortex.core.data;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Which field of a request body a value is bound to.
 *
 * <h2>The grammar, in full</h2>
 *
 * <pre>
 *   path    ::= segment ( '.' segment )*
 *   segment ::= [A-Za-z_] [A-Za-z0-9_]*
 * </pre>
 *
 * <p>Object property names, separated by dots, at most {@value #MAX_DEPTH} deep. That is the entire
 * language, and it is written down here so that growing it is a decision rather than a drift.
 *
 * <h2>What it deliberately is not</h2>
 *
 * <p>Not JSONPath, and not on the way to becoming JSONPath. There are no array indices, no
 * wildcards, no filters, no predicates, no functions and no root symbol. Each of those is individually
 * defensible and collectively they are an expression language, which is the thing this whole feature
 * exists to keep out of a configuration file — a value a person can read in a pull request is worth
 * more than a value that can address anything.
 *
 * <p>A body shape that genuinely needs an array element addressed is a body that should be supplied
 * whole, as the base document, with the varying scalars bound around it. If that turns out to be
 * insufficient in practice, the answer is a considered extension with an ADR, not a quiet regex
 * change.
 */
public record BodyFieldPath(List<String> segments) implements Comparable<BodyFieldPath> {

    /** Deep enough for real payloads, shallow enough that a mistake is visible. */
    public static final int MAX_DEPTH = 8;

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public BodyFieldPath {
        if (segments == null || segments.isEmpty()) {
            throw new IllegalArgumentException("a body field path must name at least one field");
        }
        segments = List.copyOf(segments);
        if (segments.size() > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "a body field path may be at most " + MAX_DEPTH + " levels deep, but '"
                            + String.join(".", segments) + "' is " + segments.size() + ".");
        }
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException(explain(String.join(".", segments), segment));
            }
        }
    }

    /** Parses {@code customer.address.city}. Rejects anything outside the grammar above. */
    public static BodyFieldPath parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a body field path must name at least one field");
        }
        String trimmed = text.trim();
        if (trimmed.startsWith(".") || trimmed.endsWith(".") || trimmed.contains("..")) {
            throw new IllegalArgumentException(
                    "'" + trimmed + "' is not a body field path: field names are separated by single "
                            + "dots, with no leading or trailing dot.");
        }
        return new BodyFieldPath(List.of(trimmed.split("\\.", -1)));
    }

    private static String explain(String whole, String badSegment) {
        String hint;
        if (badSegment.contains("[") || badSegment.contains("]")) {
            hint = " Array indices are not supported — supply the array in the base body and bind the "
                    + "scalar fields around it.";
        } else if (badSegment.contains("*") || badSegment.contains("?") || badSegment.contains("$")
                || badSegment.contains("@")) {
            hint = " Vortex maps named fields, not expressions: there is no wildcard, filter or root "
                    + "symbol.";
        } else {
            hint = " A field name starts with a letter or underscore and continues with letters, "
                    + "digits or underscores.";
        }
        return "'" + whole + "' is not a body field path: the part '" + badSegment
                + "' is not a field name." + hint;
    }

    /** The dotted rendering, as written in configuration. */
    public String asText() {
        return String.join(".", segments);
    }

    public boolean isNested() {
        return segments.size() > 1;
    }

    @Override
    public int compareTo(BodyFieldPath other) {
        return asText().compareTo(other.asText());
    }

    @Override
    public String toString() {
        return asText();
    }
}
