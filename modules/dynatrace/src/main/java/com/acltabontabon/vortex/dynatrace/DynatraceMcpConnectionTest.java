package com.acltabontabon.vortex.dynatrace;

import com.acltabontabon.vortex.dynatrace.query.DqlToolSchema;
import com.acltabontabon.vortex.dynatrace.query.DynatraceQueryDefinition;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Validates the Dynatrace MCP connection itself, without changing anything, one independent stage
 * at a time.
 *
 * <p>A single boolean answers "did it work" but not "which of the things that can be wrong
 * independently is the one that's wrong here" — so each stage is reported on its own. This is a
 * connection-level check, run from Settings before any service is mapped to a Dynatrace entity, so
 * it stops once {@code execute_dql}'s arguments are known to be resolvable, rather than running a
 * real query.
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

    /** {@code organizationOptions} is non-empty only when the account has more than one Dynatrace
     *  organization and none — or a now-invalid one — was configured, so a caller can offer them for
     *  the user to pick (e.g. as a Settings dropdown) rather than parse a stage's free-text detail. */
    public record Report(boolean succeeded, List<StageResult> stages, List<String> organizationOptions) {
        public Report {
            stages = stages == null ? List.of() : List.copyOf(stages);
            organizationOptions = organizationOptions == null ? List.of() : List.copyOf(organizationOptions);
        }

        public Report(boolean succeeded, List<StageResult> stages) {
            this(succeeded, stages, List.of());
        }
    }

    public DynatraceMcpConnectionTest() {
    }

    /**
     * Runs every stage local-bridge mode allows: spawning {@code npx mcp-remote <uri>}, discovering
     * {@code execute_dql}, and resolving the {@code organization} argument it requires from the
     * server's own tool schema — see {@code DynatraceMcpBridgeTelemetryClient}. {@code organization}
     * is what's in the form (tests what's typed, not what's saved, same contract as the endpoint) —
     * blank if nothing has been picked yet.
     */
    public Report runBridge(String uri, Duration timeout, String organization) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(timeout, "timeout");
        List<StageResult> stages = new ArrayList<>();

        AtomicReference<String> authPrompt = new AtomicReference<>("");
        DynatraceTelemetryClient client;
        try {
            client = new DynatraceMcpBridgeTelemetryClient(new DynatraceMcpEndpoint(uri, timeout),
                    line -> authPrompt.compareAndSet("", line));
        } catch (RuntimeException e) {
            String detail = authPrompt.get().isEmpty() ? message(e) : authPrompt.get();
            stages.add(StageResult.fail("Local bridge started", DynatraceMcpFailureClassifier.classify(e), detail));
            return new Report(false, stages);
        }

        // A prompt seen but then resolved (e.g. an already-cached session still logging its usual
        // startup lines) isn't worth surfacing — only a stalled/failed connect needs the detail, and
        // that path attaches it above instead.
        stages.add(StageResult.pass("Local bridge started"));

        return discoverToolAndFinish(client, timeout, organization, stages);
    }

    private Report discoverToolAndFinish(DynatraceTelemetryClient client, Duration timeout, String organization,
            List<StageResult> stages) {
        try {
            var tools = client.listTools(timeout);
            switch (tools) {
                case DynatraceTelemetryClient.ToolsListed listed -> {
                    var executeDql = listed.tools().stream()
                            .filter(tool -> tool.name().equals(DynatraceQueryDefinition.EXECUTE_DQL_TOOL))
                            .findFirst();
                    if (executeDql.isEmpty()) {
                        stages.add(StageResult.fail("Dynatrace tool discovered",
                                DynatraceMcpFailureCategory.MCP_TOOL_UNAVAILABLE,
                                "the server did not advertise '" + DynatraceQueryDefinition.EXECUTE_DQL_TOOL
                                        + "'. Tools it does advertise: " + listed.toolNames()));
                        return new Report(false, stages);
                    }
                    stages.add(StageResult.pass("Dynatrace tool discovered"));

                    var resolution = DqlToolSchema.resolveOrganization(executeDql.get().inputSchema(), organization);
                    switch (resolution) {
                        case DqlToolSchema.Resolved resolved ->
                                stages.add(StageResult.pass("Resolved organization: " + resolved.organization()));
                        case DqlToolSchema.Ambiguous ambiguous -> {
                            stages.add(StageResult.fail("Resolved organization",
                                    DynatraceMcpFailureCategory.AMBIGUOUS_ORGANIZATION,
                                    "this account has " + ambiguous.options().size()
                                            + " organizations — pick one below."));
                            return new Report(false, stages, ambiguous.options());
                        }
                        case DqlToolSchema.Failed failed -> {
                            stages.add(StageResult.fail("Resolved organization",
                                    DynatraceMcpFailureCategory.AMBIGUOUS_ORGANIZATION, failed.detail()));
                            return new Report(false, stages);
                        }
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
