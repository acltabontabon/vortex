package com.acltabontabon.vortex.dynatrace;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The transport-neutral boundary between Vortex's Dynatrace domain logic and however the telemetry
 * is actually fetched.
 *
 * <p>Today {@link DynatraceMcpBridgeTelemetryClient} is the only implementation, speaking MCP over
 * a locally-spawned {@code npx mcp-remote} bridge. Nothing above this interface —
 * {@link DynatraceMcpObservationSource}, the query definitions, the normalizer — knows that; a
 * future implementation could replace it without touching any of them.
 *
 * <p>One client is opened per call and closed immediately after (see {@link DynatraceMcpClientFactory})
 * rather than held open, so implementations should treat {@link #close()} as final: no reconnect,
 * no reuse.
 */
public interface DynatraceTelemetryClient extends AutoCloseable {

    /** Runs one deterministic query, bounded by {@code timeout}. Never throws for an expected failure. */
    TelemetryOutcome call(DynatraceTelemetryQuery query, Duration timeout);

    /** The MCP tools this server advertises, for connection-test tool discovery. Never throws. */
    ToolsOutcome listTools(Duration timeout);

    @Override
    void close();

    /** Either an answer or a classified failure. Never both, never neither. */
    sealed interface TelemetryOutcome permits Answered, Failed {
    }

    record Answered(DynatraceTelemetryResult result) implements TelemetryOutcome {
        public Answered {
            Objects.requireNonNull(result, "result");
        }
    }

    record Failed(DynatraceMcpFailureCategory category, String detail) implements TelemetryOutcome {
        public Failed {
            Objects.requireNonNull(category, "category");
            detail = detail == null ? "" : detail.trim();
        }
    }

    /** Either the tool list or a classified failure. */
    sealed interface ToolsOutcome permits ToolsListed, ToolsFailed {
    }

    /** One MCP tool the server advertises, with its raw JSON-schema {@code inputSchema} — needed to
     *  discover per-call arguments a tool requires that Vortex cannot hard-code, e.g. {@code
     *  execute_dql}'s account-specific {@code organization} enum. */
    record ToolInfo(String name, Map<String, Object> inputSchema) {
        public ToolInfo {
            Objects.requireNonNull(name, "name");
            inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        }
    }

    record ToolsListed(List<ToolInfo> tools) implements ToolsOutcome {
        public ToolsListed {
            tools = tools == null ? List.of() : List.copyOf(tools);
        }

        public List<String> toolNames() {
            return tools.stream().map(ToolInfo::name).toList();
        }
    }

    record ToolsFailed(DynatraceMcpFailureCategory category, String detail) implements ToolsOutcome {
        public ToolsFailed {
            Objects.requireNonNull(category, "category");
            detail = detail == null ? "" : detail.trim();
        }
    }
}
