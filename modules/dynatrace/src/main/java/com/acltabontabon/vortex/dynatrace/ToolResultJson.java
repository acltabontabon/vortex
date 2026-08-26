package com.acltabontabon.vortex.dynatrace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

/**
 * Finds structured JSON embedded inside an MCP tool's text content, when the tool didn't populate
 * {@code structuredContent} directly.
 *
 * <p>Dynatrace's real {@code execute_dql} tool answers with prose wrapped around the actual JSON —
 * observed in two different shapes: a fenced <code>```json ... ```</code> block, and a bare prefix
 * like {@code "DQL Response: [...]"} with no fence at all. Rather than special-case each wrapping
 * style, this scans the text for the first syntactically complete JSON object or array — the same
 * thing a fence or a "Response: " prefix is doing for a human reader, just without the marker — and
 * parses exactly that substring. Still deterministic extraction of data the tool returned, never a
 * guess at a number from unstructured prose, the line {@code TelemetryNormalizer} refuses to cross:
 * text with no syntactically complete JSON value anywhere in it (a markdown table, a plain-language
 * summary) yields nothing here, on purpose.
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
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '{' && c != '[') {
                continue;
            }
            Optional<String> balanced = balancedSpan(text, i);
            if (balanced.isEmpty()) {
                continue;
            }
            Optional<JsonNode> parsed = tryParse(balanced.get());
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return Optional.empty();
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
