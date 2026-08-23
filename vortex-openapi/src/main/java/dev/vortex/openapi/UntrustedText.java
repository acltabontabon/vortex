package dev.vortex.openapi;

/**
 * Cleans free text taken from an imported document.
 *
 * <p>An API description is untrusted input. It is usually written by a colleague, but it may also
 * come from a URL someone pasted, and its {@code description} and {@code summary} fields are free
 * text that will end up rendered in the UI and, if the user asks for suggestions, placed in front of
 * a language model.
 *
 * <p>Two concerns, handled here rather than at each use site:
 *
 * <ul>
 *   <li><strong>Length.</strong> A description can be arbitrarily long. Truncating keeps a single
 *       imported document from dominating an AI context window or a page layout.</li>
 *   <li><strong>Control characters.</strong> These serve no purpose in a summary and are a cheap way
 *       to disrupt whatever consumes the text downstream.</li>
 * </ul>
 *
 * <p>Note what this does <em>not</em> attempt: detecting prompt injection by pattern matching.
 * That approach does not work, and pretending otherwise would be worse than not trying. Vortex's
 * actual defence is structural — imported text is only ever placed in clearly delimited data
 * sections, never in a system prompt, and is described to the model as data rather than instruction.
 * See {@code docs/02-architecture/security.adoc}.
 */
final class UntrustedText {

    private static final int MAX_SUMMARY = 200;
    private static final int MAX_DESCRIPTION = 500;

    private UntrustedText() {
    }

    static String summary(String raw) {
        return clean(raw, MAX_SUMMARY);
    }

    static String description(String raw) {
        return clean(raw, MAX_DESCRIPTION);
    }

    static String clean(String raw, int maxLength) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(Math.min(raw.length(), maxLength));
        for (int i = 0; i < raw.length() && cleaned.length() < maxLength; i++) {
            char c = raw.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                if (!cleaned.isEmpty() && cleaned.charAt(cleaned.length() - 1) != ' ') {
                    cleaned.append(' ');
                }
            } else if (!Character.isISOControl(c)) {
                cleaned.append(c);
            }
        }
        String result = cleaned.toString().strip();
        return raw.length() > maxLength ? result + "…" : result;
    }
}
