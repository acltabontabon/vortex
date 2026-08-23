package dev.vortex.core.data;

import java.util.Optional;

/**
 * Where in a request a value is carried, and what is therefore legal in it.
 *
 * <p>Validation is per destination rather than global, because the destinations genuinely differ. A
 * newline in a header is a request-splitting vector; a newline in a body field is a paragraph.
 * Rejecting both at ingestion would corrupt legitimate data — a dataset column holding a multi-line
 * address is not an attack — and rejecting neither would let a dataset cell terminate a header.
 *
 * <p>So Vortex reads a dataset faithfully and checks each value against the position it is actually
 * bound to. That check runs at configuration time for values known then, and again at preflight for
 * dataset columns, where the rows are what is being judged.
 */
public enum RequestValueTarget {

    /**
     * An HTTP header value.
     *
     * <p>The strict one. RFC 9110 permits visible ASCII, space and horizontal tab; CR, LF and NUL in
     * particular must never appear, because a header value that can contain them can forge headers.
     */
    HEADER("header"),

    /** A path parameter. Percent-encoded when the request is issued, so content is unconstrained. */
    PATH("path parameter"),

    /** A query parameter. Percent-encoded when the request is issued, so content is unconstrained. */
    QUERY("query parameter"),

    /** A field of the request body. JSON-encoded when the request is issued; may be multi-line text. */
    BODY_FIELD("body field");

    private final String label;

    RequestValueTarget(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Whether a value carried here is encoded on the way out, and so may contain anything. */
    public boolean isEncoded() {
        return this != HEADER;
    }

    /**
     * Why this value cannot be carried here, or empty when it can.
     *
     * @param name  what the value is bound to, for the message
     * @param value the literal to check — a fixed value, or one cell of a dataset
     */
    public Optional<String> reject(String name, String value) {
        if (this != HEADER || value == null) {
            return Optional.empty();
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\r' || c == '\n') {
                return Optional.of("the header '" + name + "' would carry a value containing a line "
                        + "break. A line break in a header value can forge additional headers, so "
                        + "Vortex will not send one.");
            }
            if (c == '\0' || (c < 0x20 && c != '\t') || c == 0x7f) {
                return Optional.of("the header '" + name + "' would carry a value containing a "
                        + "control character (0x" + Integer.toHexString(c)
                        + "). Header values may contain visible characters, spaces and tabs.");
            }
        }
        return Optional.empty();
    }
}
