package dev.vortex.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;

/**
 * Extracts a JSON object from whatever a language model actually returned.
 *
 * <p>Models are asked for JSON and nothing else. Small local models — the ones Vortex is designed
 * around — comply most of the time and not always. They wrap the object in markdown fences, prefix
 * it with "Here is the analysis:", or append a closing remark.
 *
 * <p>Recovering the object from that is worth doing, because the alternative is discarding an
 * otherwise good analysis over a stray sentence. Inventing content is not: if no parsable object is
 * present, this returns empty and the analysis is recorded as failed. A malformed response becomes
 * a visible failure, never a half-understood interpretation presented as a finding.
 */
public final class JsonResponses {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonResponses() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /**
     * Finds the JSON object in a model response.
     *
     * @return the parsed object, or empty when the response contains none
     */
    public static Optional<JsonNode> extractObject(String response) {
        if (response == null || response.isBlank()) {
            return Optional.empty();
        }

        String text = stripFences(response.trim());

        // Some models emit a reasoning preamble in tags before the answer. Only the answer matters.
        int thinkEnd = text.lastIndexOf("</think>");
        if (thinkEnd >= 0) {
            text = text.substring(thinkEnd + "</think>".length()).trim();
        }

        Optional<String> candidate = firstBalancedObject(text);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode parsed = MAPPER.readTree(candidate.get());
            return parsed != null && parsed.isObject() ? Optional.of(parsed) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String stripFences(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstNewline = text.indexOf('\n');
        String withoutOpening = firstNewline < 0 ? text : text.substring(firstNewline + 1);
        int closing = withoutOpening.lastIndexOf("```");
        return (closing < 0 ? withoutOpening : withoutOpening.substring(0, closing)).trim();
    }

    /**
     * Scans for the first balanced {@code { … }} span, ignoring braces inside string literals.
     *
     * <p>A naive first-brace-to-last-brace search breaks as soon as a model writes prose containing
     * a brace after the object, which they do.
     */
    private static Optional<String> firstBalancedObject(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return Optional.empty();
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return Optional.of(text.substring(start, i + 1));
                }
            }
        }
        return Optional.empty();
    }
}
