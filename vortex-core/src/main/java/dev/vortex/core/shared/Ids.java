package dev.vortex.core.shared;

import java.util.Locale;
import java.util.UUID;

/**
 * Factory helpers shared by the identifier records.
 *
 * <p>Vortex identifiers are lowercase, hyphen-free UUIDs. They are never parsed for meaning.
 */
public final class Ids {

    private Ids() {
    }

    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Validates an externally supplied identifier value.
     *
     * @throws IllegalArgumentException when the value is null, blank, or contains characters that
     *                                  would be unsafe in a filesystem path or URL segment
     */
    public static String require(String label, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > 128) {
            throw new IllegalArgumentException(label + " must be at most 128 characters");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!safe) {
                throw new IllegalArgumentException(
                        label + " may only contain letters, digits, '-' and '_' but was: " + value);
            }
        }
        return trimmed;
    }
}
