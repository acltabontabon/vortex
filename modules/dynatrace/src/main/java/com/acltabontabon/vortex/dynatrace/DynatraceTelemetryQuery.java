package com.acltabontabon.vortex.dynatrace;

import java.util.Map;
import java.util.Objects;

/**
 * One deterministic question to ask an MCP tool, fully formed before the call is made.
 *
 * <p>Never free text. {@code toolName} names an MCP tool Vortex already knows how to interpret the
 * answer from, and {@code arguments} is the exact argument map that tool call sends — the same
 * request every time the same {@link com.acltabontabon.vortex.dynatrace.query.DynatraceQueryDefinition}
 * is asked about the same entity and window.
 *
 * @param id        the query definition's versioned identifier, e.g. {@code dynatrace.throughput.v1}
 * @param toolName  the MCP tool this query calls
 * @param arguments the tool call's arguments
 */
public record DynatraceTelemetryQuery(String id, String toolName, Map<String, Object> arguments) {

    public DynatraceTelemetryQuery {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(toolName, "toolName");
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
