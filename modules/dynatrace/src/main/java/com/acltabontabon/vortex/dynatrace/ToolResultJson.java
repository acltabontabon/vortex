package com.acltabontabon.vortex.dynatrace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

/**
 * Finds structured JSON embedded inside an MCP tool's text content, when the tool didn't populate
 * {@code structuredContent} directly.
 *
 * <p>Dynatrace's real {@code execute_dql} tool answers with prose wrapped around the actual JSON —
 * observed in three different shapes: a fenced <code>```json ... ```</code> block, a bare prefix
 * like {@code "DQL Response: [...]"} with no fence at all, and (also observed against a real
 * endpoint) the same bare-prefix shape but with the embedded JSON itself backslash-escaped, as if it
 * had first been serialized as a JSON string value and then had its surrounding quotes stripped —
 * {@code peak\":0.33...} instead of {@code peak":0.33...}. Rather than special-case each wrapping
 * style, this scans the text for every syntactically complete top-level JSON object or array and
 * parses the largest one, retrying once against an unescaped copy of the text if nothing was found —
 * the same thing a fence or a "Response: " prefix is doing for a human reader, just without the
 * marker. Preferring the largest match, rather than the first, guards against a small,
 * syntactically-valid-but-irrelevant JSON-looking fragment earlier in surrounding prose (a client's
 * own formatting metadata, a code sample) being mistaken for the real payload, which is virtually
 * always the biggest JSON blob in the text. Still deterministic extraction of data the tool
 * returned, never a guess at a number from unstructured prose, the line {@code TelemetryNormalizer}
 * refuses to cross: text with no syntactically complete JSON value anywhere in it, escaped or not (a
 * markdown table, a plain-language summary), still yields nothing here, on purpose.
 */
final class ToolResultJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolResultJson() {
    }

    static Optional<JsonNode> extractStructured(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Optional<JsonNode> direct = tryParse(text.trim());
        if (direct.isPresent()) {
            return direct;
        }
        Optional<JsonNode> scanned = scanForLargestJson(text);
        if (scanned.isPresent()) {
            return scanned;
        }
        // Backslash-escaped quotes (\") appearing where a bare quote is structurally expected mean
        // this text was itself once a JSON string value whose surrounding quotes were stripped —
        // unescape it once and retry the exact same extraction on the result.
        String unescaped = unescapeBackslashSequences(text);
        if (unescaped.equals(text)) {
            return Optional.empty();
        }
        Optional<JsonNode> direct2 = tryParse(unescaped.trim());
        return direct2.isPresent() ? direct2 : scanForLargestJson(unescaped);
    }

    private static Optional<JsonNode> scanForLargestJson(String text) {
        String bestSpan = null;
        JsonNode best = null;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '{' && c != '[') {
                i++;
                continue;
            }
            Optional<String> balanced = balancedSpan(text, i);
            if (balanced.isEmpty()) {
                i++;
                continue;
            }
            String span = balanced.get();
            Optional<JsonNode> parsed = tryParse(span);
            if (parsed.isPresent() && (bestSpan == null || span.length() > bestSpan.length())) {
                bestSpan = span;
                best = parsed.get();
            }
            // Skip past this span entirely rather than rescanning its interior character by
            // character — any '{'/'[' inside it was already considered as part of this candidate.
            i += span.length();
        }
        return Optional.ofNullable(best);
    }

    /** Undoes standard JSON string escaping ({@code \"}, {@code \\}, {@code \/}, {@code \n}, {@code
     *  \t}, {@code \r}, {@code \b}, {@code \f}, {@code \\uXXXX}) wherever it appears in the text, not
     *  just inside an already-recognized string — the point of this pass is recovering from text that
     *  was escaped as though the surrounding quotes were still there when they aren't. A lone
     *  backslash not starting a recognized escape is left untouched. */
    private static String unescapeBackslashSequences(String text) {
        StringBuilder result = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                result.append(c);
                i++;
                continue;
            }
            char next = text.charAt(i + 1);
            switch (next) {
                case '"' -> { result.append('"'); i += 2; }
                case '\\' -> { result.append('\\'); i += 2; }
                case '/' -> { result.append('/'); i += 2; }
                case 'n' -> { result.append('\n'); i += 2; }
                case 't' -> { result.append('\t'); i += 2; }
                case 'r' -> { result.append('\r'); i += 2; }
                case 'b' -> { result.append('\b'); i += 2; }
                case 'f' -> { result.append('\f'); i += 2; }
                case 'u' -> {
                    if (i + 6 <= text.length()) {
                        try {
                            result.append((char) Integer.parseInt(text.substring(i + 2, i + 6), 16));
                            i += 6;
                        } catch (NumberFormatException e) {
                            result.append(c);
                            i++;
                        }
                    } else {
                        result.append(c);
                        i++;
                    }
                }
                default -> { result.append(c); i++; }
            }
        }
        return result.toString();
    }

    /** The substring from {@code start} (an opening {@code {} or {@code [}) to its matching close,
     *  tracking nesting depth and skipping over the contents of quoted strings (including escaped
     *  quotes) so a brace or bracket character inside a string value never miscounts. Empty if the
     *  text ends before the opening character's depth returns to zero. */
    private static Optional<String> balancedSpan(String text, int start) {
        char open = text.charAt(start);
        char close = open == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return Optional.of(text.substring(start, i + 1));
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<JsonNode> tryParse(String candidate) {
        try {
            JsonNode parsed = JSON.readTree(candidate);
            return parsed.isObject() || parsed.isArray() ? Optional.of(parsed) : Optional.empty();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Optional.empty();
        }
    }
}
