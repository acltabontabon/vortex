package com.acltabontabon.vortex.dynatrace;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Speaks MCP over the stdio of a locally-spawned {@code npx mcp-remote <url>} process, instead of
 * connecting to the endpoint directly.
 *
 * <p>The one deliberate, narrow exception to this module's "never spawns a process" rule — see
 * {@code DynatraceModuleArchitectureTest} and docs/adr/adr-051-dynatrace-mcp-local-npx-bridge.adoc.
 * Only the endpoint URL, already validated as an absolute {@code https://} URL by
 * {@link DynatraceMcpEndpoint}, ever reaches the child process's argument list; the command is
 * always the literal {@code "npx"} and the package is always the fixed, version-pinned
 * {@link #MCP_REMOTE_PACKAGE} constant.
 *
 * <p>{@code mcp-remote} performs Dynatrace's own interactive OAuth itself: on first use it opens a
 * system browser for the user to sign in, then caches the resulting session (typically under
 * {@code ~/.mcp-auth}) and refreshes it headlessly afterward. Vortex has no header or client
 * credential of its own to consult here — see ADR-052.
 */
final class DynatraceMcpBridgeTelemetryClient implements DynatraceTelemetryClient {

    /**
     * Pinned rather than bare {@code "mcp-remote"}: this module opens a fresh client — and so a
     * fresh {@code npx} resolution — per call, so an unpinned package is a re-fetched, unreviewed
     * supply-chain surface on every single connection, not just the first. Bumping this is a
     * deliberate, reviewed code change; no setting or user input can influence it.
     */
    private static final String MCP_REMOTE_PACKAGE = "mcp-remote@0.2.5";

    /** How long Vortex waits for the initial MCP handshake, distinct from {@code requestTimeout}
     *  (sized for a single query): the first connect may need a human to complete a browser OAuth
     *  login, which takes far longer than any query reasonably should. */
    private static final Duration FIRST_CONNECT_TIMEOUT = Duration.ofMinutes(2);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A stderr line that looks like it is asking the user to open a URL to authorize. Best-effort —
     *  not verified against a real {@code mcp-remote} run; kept deliberately generous (any URL near
     *  wording like this) so a slightly different phrasing still surfaces something rather than
     *  nothing. See the connection test's "Local bridge started" stage. */
    private static final Pattern AUTH_PROMPT = Pattern.compile(
            "(?i)(authoriz|sign.?in|log.?in|visit|open).{0,80}(https?://\\S+)");

    /** Mirrors {@code ProcessExecution.mask} in modules/k6 — not shared because pulling that
     *  module-internal helper across a module boundary for one small regex is a worse trade than a
     *  short, obviously-equivalent duplicate. Applied to every captured stderr line before it is
     *  exposed anywhere Vortex logs or renders it. */
    private static final Pattern SECRET_LOOKING =
            Pattern.compile("(?i)\\b(token|secret|password|authorization|bearer)\\b\\s*[=:]\\s*\\S+");

    private final McpSyncClient client;

    /**
     * {@code onAuthPrompt} is invoked at most once, the moment a matching stderr line is seen — not
     * only after a successful return. If {@code initialize()} below throws (the common case on first
     * use, while a human hasn't finished the browser login yet), this constructor never returns an
     * instance the caller could otherwise ask; the callback is how {@link DynatraceMcpConnectionTest}
     * still learns what to show even when construction fails.
     */
    DynatraceMcpBridgeTelemetryClient(DynatraceMcpEndpoint endpoint, Consumer<String> onAuthPrompt) {
        Objects.requireNonNull(endpoint, "endpoint");
        Consumer<String> notify = onAuthPrompt == null ? line -> { } : onAuthPrompt;

        ServerParameters params = buildServerParameters(endpoint.uri());
        StdioClientTransport transport = new StdioClientTransport(params, McpJsonDefaults.getMapper());
        transport.setStdErrorHandler(line -> onStderrLine(line, notify));

        this.client = McpClient.sync(transport)
                .requestTimeout(endpoint.timeout())
                .initializationTimeout(FIRST_CONNECT_TIMEOUT)
                .build();
        this.client.initialize();
    }

    /**
     * The only place the endpoint URL reaches process construction — {@code command} is always the
     * literal {@code "npx"}, the package always the fixed {@link #MCP_REMOTE_PACKAGE}. {@code uri} is
     * passed through as a single, inert argv element regardless of its content: it is never
     * interpreted by a shell (no shell is invoked — {@link ProcessBuilder}, which the SDK's transport
     * uses internally, execs the program directly), so characters that would matter to a shell (';',
     * '$', '`', ...) are just bytes in one argument here.
     */
    static ServerParameters buildServerParameters(String uri) {
        return ServerParameters.builder("npx")
                .args(List.of("-y", MCP_REMOTE_PACKAGE, uri))
                .build();
    }

    static void onStderrLine(String line, Consumer<String> notify) {
        if (line == null || line.isBlank()) {
            return;
        }
        Matcher prompt = AUTH_PROMPT.matcher(line);
        if (prompt.find()) {
            notify.accept("Open this URL in a browser to authorize Dynatrace access: "
                    + redact(prompt.group(2)));
        }
    }

    static String redact(String line) {
        Matcher matcher = SECRET_LOOKING.matcher(line);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(1) + "=<redacted>"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    @Override
    public TelemetryOutcome call(DynatraceTelemetryQuery query, Duration timeout) {
        try {
            McpSchema.CallToolResult result = client.callTool(
                    McpSchema.CallToolRequest.builder(query.toolName())
                            .arguments(query.arguments())
                            .build());

            if (Boolean.TRUE.equals(result.isError())) {
                return new Failed(DynatraceMcpFailureCategory.QUERY_REJECTED, toolErrorText(result));
            }
            return new Answered(toResult(result));
        } catch (RuntimeException e) {
            return new Failed(DynatraceMcpFailureClassifier.classify(e), messageOf(e));
        }
    }

    @Override
    public ToolsOutcome listTools(Duration timeout) {
        try {
            McpSchema.ListToolsResult tools = client.listTools();
            List<ToolInfo> infos = new ArrayList<>();
            tools.tools().forEach(tool -> infos.add(new ToolInfo(tool.name(), tool.inputSchema())));
            return new ToolsListed(infos);
        } catch (RuntimeException e) {
            return new ToolsFailed(DynatraceMcpFailureClassifier.classify(e), messageOf(e));
        }
    }

    @Override
    public void close() {
        client.close();
    }

    // ------------------------------------------------------------------ result shaping

    private DynatraceTelemetryResult toResult(McpSchema.CallToolResult result) {
        if (result.structuredContent() != null) {
            return new DynatraceTelemetryResult(JSON.valueToTree(result.structuredContent()), true);
        }
        String text = textContentOf(result);
        if (text == null || text.isBlank()) {
            return new DynatraceTelemetryResult(JSON.createObjectNode(), false);
        }
        return ToolResultJson.extractStructured(text)
                .map(parsed -> new DynatraceTelemetryResult(parsed, true))
                .orElseGet(() -> new DynatraceTelemetryResult(JSON.getNodeFactory().textNode(text), false));
    }

    private String textContentOf(McpSchema.CallToolResult result) {
        if (result.content() == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent) {
                text.append(textContent.text());
            }
        }
        return text.toString();
    }

    private String toolErrorText(McpSchema.CallToolResult result) {
        String text = textContentOf(result);
        return text == null || text.isBlank() ? "the tool reported an error with no detail" : text;
    }

    private String messageOf(RuntimeException e) {
        if (e instanceof McpError mcpError) {
            return mcpError.getJsonRpcError() != null ? mcpError.getJsonRpcError().message() : mcpError.getMessage();
        }
        if (e instanceof McpTransportException) {
            return e.getMessage();
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
