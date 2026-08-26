package com.acltabontabon.vortex.dynatrace;

import com.acltabontabon.vortex.dynatrace.oauth.DynatraceOAuthTokenProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

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

    /** Unsaved OAuth Client Credentials to test — the token is fetched fresh, never from cache. */
    public record OAuthCredentials(String clientId, String clientSecret, String scope, String resource) {
        public OAuthCredentials {
            Objects.requireNonNull(clientId, "clientId");
            Objects.requireNonNull(clientSecret, "clientSecret");
            scope = scope == null ? "" : scope;
            resource = resource == null ? "" : resource;
        }
    }

    private final DynatraceOAuthTokenProvider oauth;

    public DynatraceMcpConnectionTest() {
        this(new DynatraceOAuthTokenProvider());
    }

    DynatraceMcpConnectionTest(DynatraceOAuthTokenProvider oauth) {
        this.oauth = Objects.requireNonNull(oauth, "oauth");
    }

    /** Runs every stage the given inputs allow, using header auth only. */
    public Report run(String uri, Map<String, String> headers, Duration timeout) {
        return run(uri, headers, timeout, null);
    }

    /**
     * Runs every stage the given inputs allow. {@code oauth} is {@code null} for header auth; when
     * given, a token is fetched as its own leading stage — SSO rejecting the client is a distinct
     * failure from the MCP server rejecting the resulting token, which stays named "Authentication".
     */
    public Report run(String uri, Map<String, String> headers, Duration timeout, OAuthCredentials oauth) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(timeout, "timeout");
        List<StageResult> stages = new ArrayList<>();

        Map<String, String> effectiveHeaders = headers;
        if (oauth != null) {
            try {
                String resolvedSecret = DynatraceMcpSecretResolution.resolveValue(oauth.clientSecret());
                String token = this.oauth.bearerToken(oauth.clientId(), resolvedSecret, oauth.scope(),
                        oauth.resource(), timeout);
                effectiveHeaders = new LinkedHashMap<>(headers);
                effectiveHeaders.put("Authorization", "Bearer " + token);
                stages.add(StageResult.pass("OAuth token obtained"));
            } catch (RuntimeException e) {
                stages.add(StageResult.fail("OAuth token obtained", DynatraceMcpFailureClassifier.classify(e),
                        message(e)));
                return new Report(false, stages);
            }
        }

        DynatraceTelemetryClient client;
        try {
            client = new DynatraceMcpTelemetryClient(new DynatraceMcpEndpoint(uri, effectiveHeaders, timeout));
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

        return discoverToolAndFinish(client, timeout, stages);
    }

    /**
     * Runs the local npx/mcp-remote bridge connection mode instead of the direct-HTTP path — see
     * {@code DynatraceMcpBridgeTelemetryClient}. A dedicated method rather than a mode switch through
     * {@link #run(String, Map, Duration, OAuthCredentials)}: the two paths share almost no stage
     * logic (no header resolution, no token fetch, a different first stage, a different timeout
     * policy for the initial handshake).
     */
    public Report runBridge(String uri, Duration timeout) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(timeout, "timeout");
        List<StageResult> stages = new ArrayList<>();

        AtomicReference<String> authPrompt = new AtomicReference<>("");
        DynatraceTelemetryClient client;
        try {
            client = new DynatraceMcpBridgeTelemetryClient(new DynatraceMcpEndpoint(uri, Map.of(), timeout),
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

        return discoverToolAndFinish(client, timeout, stages);
    }

    private Report discoverToolAndFinish(DynatraceTelemetryClient client, Duration timeout, List<StageResult> stages) {
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
