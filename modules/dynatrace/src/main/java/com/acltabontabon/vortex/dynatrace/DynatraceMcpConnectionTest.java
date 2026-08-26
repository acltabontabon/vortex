package com.acltabontabon.vortex.dynatrace;

import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.dynatrace.query.DynatraceQueries;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validates the whole Dynatrace MCP path without changing anything, one independent stage at a time.
 *
 * <p>A single boolean answers "did it work" but not "which of the four things that can be wrong
 * independently is the one that's wrong here" — so each stage is reported on its own, and a stage
 * that cannot run (no entity to test a real query against yet) says so rather than silently passing.
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

        static StageResult skipped(String stage, String reason) {
            return new StageResult(stage, true, null, reason);
        }
    }

    public record Report(boolean succeeded, List<StageResult> stages) {
        public Report {
            stages = stages == null ? List.of() : List.copyOf(stages);
        }
    }

    /**
     * Runs every stage the given inputs allow. {@code entityId} may be blank — the minimum-query
     * stage is then reported as skipped rather than attempted against nothing.
     */
    public Report run(String uri, Map<String, String> headers, Duration timeout, String entityId) {
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

            if (entityId == null || entityId.isBlank()) {
                stages.add(StageResult.skipped("Telemetry access",
                        "no service is mapped to a Dynatrace entity yet — this stage runs once one is"));
                return new Report(true, stages);
            }

            Instant now = Instant.now();
            var query = DynatraceQueries.THROUGHPUT_V1.queryFor(entityId,
                    new TimeWindow(now.minus(Duration.ofHours(1)), now), Duration.ofMinutes(5));
            var outcome = client.call(query, timeout);
            switch (outcome) {
                case DynatraceTelemetryClient.Answered ignored -> stages.add(StageResult.pass("Telemetry access"));
                case DynatraceTelemetryClient.Failed failed ->
                        stages.add(StageResult.fail("Telemetry access", failed.category(), failed.detail()));
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
