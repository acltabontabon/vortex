package com.acltabontabon.vortex.dynatrace;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.transport.ServerParameters;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DynatraceMcpBridgeTelemetryClientTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://dynatrace-mcp.internal/mcp",
            "https://env.apps.dynatrace.com/platform-reserved/mcp-gateway/v0.1/servers/dynatrace-mcp/mcp",
            "https://dynatrace-mcp.internal/mcp; rm -rf /",
            "https://dynatrace-mcp.internal/mcp$(whoami)",
            "https://dynatrace-mcp.internal/mcp --header X-Evil:1",
            "https://dynatrace-mcp.internal/mcp` id `",
    })
    void buildServerParametersNeverInterpretsTheUriAsAnythingButOneInertArgument(String uri) {
        ServerParameters params = DynatraceMcpBridgeTelemetryClient.buildServerParameters(uri);

        assertThat(params.getCommand()).isEqualTo("npx");
        assertThat(params.getArgs()).containsExactly("-y", "mcp-remote@0.2.5", uri);
    }

    @Test
    void onStderrLineIgnoresOrdinaryLogNoise() {
        List<String> captured = new ArrayList<>();
        DynatraceMcpBridgeTelemetryClient.onStderrLine("Starting mcp-remote proxy", captured::add);
        DynatraceMcpBridgeTelemetryClient.onStderrLine("Connected to remote server", captured::add);
        DynatraceMcpBridgeTelemetryClient.onStderrLine("", captured::add);
        DynatraceMcpBridgeTelemetryClient.onStderrLine(null, captured::add);

        assertThat(captured).isEmpty();
    }

    @Test
    void onStderrLineSurfacesAnAuthorizationPromptWithItsUrl() {
        List<String> captured = new ArrayList<>();
        DynatraceMcpBridgeTelemetryClient.onStderrLine(
                "Please authorize this client by visiting: https://sso.dynatrace.com/authorize?client_id=abc",
                captured::add);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0))
                .contains("Open this URL in a browser to authorize Dynatrace access:")
                .contains("https://sso.dynatrace.com/authorize?client_id=abc");
    }

    @Test
    void onStderrLineRedactsASecretLookingValueEmbeddedInTheCapturedUrl() {
        // The surfaced message is built only from the URL the regex captures (see onStderrLine) — a
        // secret elsewhere on the line never reaches it at all. The case redact() actually guards is
        // a secret-shaped value inside the URL itself, e.g. an authorize link carrying a token param.
        List<String> captured = new ArrayList<>();
        DynatraceMcpBridgeTelemetryClient.onStderrLine(
                "Please authorize by visiting https://sso.dynatrace.com/authorize?token=sk-live-abcdef123456",
                captured::add);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0)).doesNotContain("sk-live-abcdef123456");
        assertThat(captured.get(0)).contains("token=<redacted>");
    }

    @Test
    void redactMasksMultipleSecretShapedValuesInOneLine() {
        String redacted = DynatraceMcpBridgeTelemetryClient.redact(
                "token=abc123 and also password: hunter2 stay here but authorize=fine");

        assertThat(redacted).doesNotContain("abc123", "hunter2");
        assertThat(redacted).contains("token=<redacted>", "password=<redacted>");
    }

    @Test
    void redactLeavesAnOrdinaryLineUntouched() {
        String line = "Connecting to https://dynatrace-mcp.internal/mcp";
        assertThat(DynatraceMcpBridgeTelemetryClient.redact(line)).isEqualTo(line);
    }
}
