package com.acltabontabon.vortex.dynatrace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Speaks MCP Streamable-HTTP directly to a Dynatrace MCP endpoint.
 *
 * <p>Deliberately does not go through the {@code npx mcp-remote <url>} bridge an SRE-shared config
 * describes — that bridge exists for MCP clients that only support stdio. Vortex is a full MCP
 * client itself, so it connects straight to the remote HTTPS endpoint and never spawns a local
 * process to get there.
 *
 * <p>One client per call, opened and closed by {@link DynatraceMcpClientFactory}: this class holds
 * no state across {@link #call} invocations beyond the connection it was constructed with.
 */
final class DynatraceMcpTelemetryClient implements DynatraceTelemetryClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final McpSyncClient client;

    DynatraceMcpTelemetryClient(DynatraceMcpEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        Map<String, String> resolved = DynatraceMcpSecretResolution.resolve(endpoint.headers());

        URI parsed = URI.create(endpoint.uri());
        String origin = parsed.getScheme() + "://" + parsed.getAuthority();
        String path = (parsed.getRawPath() == null || parsed.getRawPath().isBlank())
                ? "/mcp" : parsed.getRawPath();

        HttpRequest.Builder requestTemplate = HttpRequest.newBuilder();
        resolved.forEach(requestTemplate::header);

        var transport = HttpClientStreamableHttpTransport.builder(origin)
                .endpoint(path)
                .connectTimeout(Duration.ofSeconds(10))
                .requestBuilder(requestTemplate)
                .build();

        this.client = McpClient.sync(transport)
                .requestTimeout(endpoint.timeout())
                .build();
        this.client.initialize();
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
            List<String> names = new ArrayList<>();
            tools.tools().forEach(tool -> names.add(tool.name()));
            return new ToolsListed(names);
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
        try {
            JsonNode parsed = JSON.readTree(text);
            boolean structured = parsed.isObject() || parsed.isArray();
            return new DynatraceTelemetryResult(parsed, structured);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return new DynatraceTelemetryResult(JSON.getNodeFactory().textNode(text), false);
        }
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
