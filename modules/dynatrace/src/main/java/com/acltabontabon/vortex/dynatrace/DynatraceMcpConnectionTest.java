package com.acltabontabon.vortex.dynatrace;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validates the Dynatrace MCP connection itself, without changing anything, one independent stage
 * at a time.
 *
 * <p>A single boolean answers "did it work" but not "which of the three things that can be wrong
 * independently is the one that's wrong here" — so each stage is reported on its own. This is a
 * connection-level check, run from Settings before any service is mapped to a Dynatrace entity, so
 * it stops at tool discovery rather than exercising a real telemetry query.
 */
public final class DynatraceMcpConnectionTest {

    public record StageResult(String stage, boolean succeeded, DynatraceMcpFailureCategory category,
            String detail) {
        static StageResult pass(String stage) {
            return new StageResult(stage, true, null, "");
        }

        static StageResult fail(String stage, DynatraceMcpFailureCategory category, String detail) {
            return new StageResult(stage, false, category, detail);
        }
    }

    public record Report(boolean succeeded, List<StageResult> stages) {
        public Report {
            stages = stages == null ? List.of() : List.copyOf(stages);
        }
    }

    /** Runs every stage the given inputs allow. */
    public Report run(String uri, Map<String, String> headers, Duration timeout) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(timeout, "timeout");
        List<StageResult> stages = new ArrayList<>();

        DynatraceTelemetryClient client;
        try {
            client = new DynatraceMcpTelemetryClient(new DynatraceMcpEndpoint(uri, headers, timeout));
        } catch (RuntimeException e) {
            DynatraceMcpFailureCategory category = DynatraceMcpFailureClassifier.classify(e);
            boolean isAuthFailure = category == DynatraceMcpFailureCategory.AUTHENTICATION_FAILED
                    || category == DynatraceMcpFailureCategory.PERMISSION_DENIED;
            if (isAuthFailure) {
                stages.add(StageResult.pass("MCP server reachable"));
                stages.add(StageResult.fail("Authentication", category, message(e)));
            } else {
                stages.add(StageResult.fail("MCP server reachable", category, message(e)));
            }
            return new Report(false, stages);
        }

        stages.add(StageResult.pass("MCP server reachable"));
        stages.add(StageResult.pass("Authentication"));

        try {
            var tools = client.listTools(timeout);
            switch (tools) {
                case DynatraceTelemetryClient.ToolsListed listed -> {
                    if (listed.toolNames().contains(com.acltabontabon.vortex.dynatrace.query.DynatraceQueryDefinition.EXECUTE_DQL_TOOL)) {
                        stages.add(StageResult.pass("Dynatrace tool discovered"));
                    } else {
                        stages.add(StageResult.fail("Dynatrace tool discovered",
                                DynatraceMcpFailureCategory.MCP_TOOL_UNAVAILABLE,
                                "the server did not advertise '"
                                        + com.acltabontabon.vortex.dynatrace.query.DynatraceQueryDefinition.EXECUTE_DQL_TOOL
                                        + "'. Tools it does advertise: " + listed.toolNames()));
                        return new Report(false, stages);
                    }
                }
                case DynatraceTelemetryClient.ToolsFailed failed -> {
                    stages.add(StageResult.fail("Dynatrace tool discovered", failed.category(), failed.detail()));
                    return new Report(false, stages);
                }
            }
        } finally {
            client.close();
        }

        boolean succeeded = stages.stream().allMatch(StageResult::succeeded);
        return new Report(succeeded, stages);
    }

    private String message(RuntimeException e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
}
