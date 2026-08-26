package com.acltabontabon.vortex.dynatrace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds structured JSON inside an MCP tool's text content, when the tool didn't populate
 * {@code structuredContent} directly.
 *
 * <p>Some MCP servers answer a data-returning tool call with prose that wraps the actual JSON in a
 * fenced code block (<code>```json ... ```</code>) for a human reader, rather than bare JSON. Finding
 * exactly that block is still deterministic parsing of data the tool returned — never a guess at a
 * number from unstructured prose, the line {@code TelemetryNormalizer} refuses to cross. Text with no
 * JSON anywhere in it (a markdown table, a plain-language summary) still yields nothing here, on
 * purpose: extraction, not interpretation.
 */
final class ToolResultJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Non-greedy so a response with more than one fenced block tries each in turn rather than
     *  swallowing everything between the first and last fence. */
    private static final Pattern FENCED_BLOCK = Pattern.compile("```(?:json)?\\s*\\n?(.*?)```", Pattern.DOTALL);

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
        Matcher matcher = FENCED_BLOCK.matcher(text);
        while (matcher.find()) {
            Optional<JsonNode> fromFence = tryParse(matcher.group(1).trim());
            if (fromFence.isPresent()) {
                return fromFence;
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
