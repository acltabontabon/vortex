package com.acltabontabon.vortex.dynatrace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;

/**
 * Reads a provided MCP configuration and extracts a remote endpoint from it — without ever running
 * what it describes.
 *
 * <p>The configuration most teams are given is shaped like {@code {"command": "npx", "args":
 * ["-y", "mcp-remote", "https://mcp-server.example/mcp"]}}: the same local stdio bridge Vortex itself
 * spawns to reach it — see {@code DynatraceMcpBridgeTelemetryClient}. This class's only job is to find
 * the URL that command connects to, and discard the command itself; Vortex never runs a pasted
 * command verbatim, even one it would otherwise run itself.
 *
 * <p>Anything this class cannot confidently reduce to "here is the remote URL" is refused with an
 * explanation, never silently accepted. There is no code path here that constructs a
 * {@code ProcessBuilder} or otherwise executes anything: parsing text is the whole job.
 */
public final class DynatraceMcpConfigImport {

    private static final ObjectMapper JSON = new ObjectMapper();

    public sealed interface Result permits Recognized, Unrecognized {
    }

    /** What Vortex would configure, for the caller to show for review — nothing is saved yet. */
    public record Recognized(String endpoint, String suggestedLabel) implements Result {
        public Recognized {
            Objects.requireNonNull(endpoint, "endpoint");
            suggestedLabel = suggestedLabel == null ? "" : suggestedLabel;
        }
    }

    /** Refused. {@code reason} is shown to the user; nothing was executed to reach this conclusion. */
    public record Unrecognized(String reason) implements Result {
        public Unrecognized {
            Objects.requireNonNull(reason, "reason");
        }
    }

    private DynatraceMcpConfigImport() {
    }

    public static Result parse(String pasted) {
        if (pasted == null || pasted.isBlank()) {
            return new Unrecognized("Nothing was pasted.");
        }
        String trimmed = pasted.trim();

        if (looksLikeBareUrl(trimmed)) {
            return new Recognized(trimmed, "");
        }

        JsonNode root;
        try {
            root = JSON.readTree(trimmed);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return new Unrecognized(
                    "This is neither a URL nor valid JSON. Paste the endpoint directly, or the MCP "
                            + "configuration block you were given.");
        }

        NamedEntry entry = findNpxMcpRemoteEntry(root, "");
        if (entry == null) {
            entry = findBareUrlEntry(root, "");
        }
        if (entry == null) {
            return new Unrecognized(
                    "Vortex only recognises a remote MCP endpoint, or a config shaped like "
                            + "{\"command\":\"npx\",\"args\":[\"-y\",\"mcp-remote\",\"<url>\"]} — and it "
                            + "never runs a pasted command. If you have something else, ask whoever "
                            + "provided it for the plain HTTPS endpoint instead.");
        }
        return entry.result;
    }

    private static boolean looksLikeBareUrl(String text) {
        return (text.startsWith("http://") || text.startsWith("https://")) && !text.contains("\n")
                && !text.contains("{");
    }

    private record NamedEntry(Result result) {
    }

    /** Walks the parsed JSON for any object with an {@code npx}/{@code mcp-remote} shape. */
    private static NamedEntry findNpxMcpRemoteEntry(JsonNode node, String keyHint) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Result direct = tryAsMcpRemoteCommand(node, keyHint);
            if (direct != null) {
                return new NamedEntry(direct);
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                NamedEntry found = findNpxMcpRemoteEntry(field.getValue(), field.getKey());
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                NamedEntry found = findNpxMcpRemoteEntry(element, keyHint);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Result tryAsMcpRemoteCommand(JsonNode candidate, String keyHint) {
        JsonNode commandNode = candidate.path("command");
        JsonNode argsNode = candidate.path("args");
        if (!commandNode.isTextual() || !"npx".equals(commandNode.asText()) || !argsNode.isArray()) {
            return null;
        }
        List<String> args = new java.util.ArrayList<>();
        argsNode.forEach(n -> args.add(n.asText("")));

        if (args.stream().noneMatch("mcp-remote"::equals)) {
            return null;
        }

        String url = args.stream().filter(a -> a.startsWith("http://") || a.startsWith("https://"))
                .findFirst().orElse(null);
        if (url == null) {
            return new Unrecognized(
                    "Found an npx mcp-remote command but no https:// URL among its arguments — "
                            + "check the configuration is complete.");
        }

        return new Recognized(url, keyHint);
    }

    /**
     * Walks the parsed JSON for any object naming a remote endpoint directly — the shape VS Code and
     * Claude Code use natively for a remote MCP server, e.g. {@code {"url": "https://...", "headers":
     * {...}}}, optionally under a {@code mcpServers}/{@code servers} wrapper. Tried only after the
     * npx/mcp-remote shape fails to match, so a config carrying both interpretations still prefers the
     * explicit bridge command.
     */
    private static NamedEntry findBareUrlEntry(JsonNode node, String keyHint) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Result direct = tryAsBareUrlEntry(node, keyHint);
            if (direct != null) {
                return new NamedEntry(direct);
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                NamedEntry found = findBareUrlEntry(field.getValue(), field.getKey());
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                NamedEntry found = findBareUrlEntry(element, keyHint);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** A {@code "type"} field (e.g. {@code "http"}) may be present but is not diagnostic — ignored.
     *  Any accompanying {@code "headers"} are discarded too: Vortex's bridge performs its own OAuth
     *  and never sends a header from a pasted config. */
    private static Result tryAsBareUrlEntry(JsonNode candidate, String keyHint) {
        JsonNode urlNode = candidate.path("url");
        if (!urlNode.isTextual()) {
            return null;
        }
        String url = urlNode.asText("");
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return null;
        }
        return new Recognized(url, keyHint);
    }
}
