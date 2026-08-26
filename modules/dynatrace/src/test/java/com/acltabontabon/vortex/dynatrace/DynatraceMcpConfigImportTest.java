package com.acltabontabon.vortex.dynatrace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DynatraceMcpConfigImportTest {

    @Test
    void aBareUrlIsRecognizedDirectly() {
        var result = DynatraceMcpConfigImport.parse("https://sre-mcp-server.internal/mcp");
        assertThat(result).isInstanceOfSatisfying(DynatraceMcpConfigImport.Recognized.class,
                recognized -> assertThat(recognized.endpoint())
                        .isEqualTo("https://sre-mcp-server.internal/mcp"));
    }

    @Test
    void theExactShapeSreSharesIsRecognized() {
        String pasted = """
                {"our-sre": {"command": "npx", "args":["mcp-remote", "https://sre-mcp-server.internal/mcp"]}}""";
        var result = DynatraceMcpConfigImport.parse(pasted);
        assertThat(result).isInstanceOfSatisfying(DynatraceMcpConfigImport.Recognized.class,
                recognized -> {
                    assertThat(recognized.endpoint()).isEqualTo("https://sre-mcp-server.internal/mcp");
                    assertThat(recognized.suggestedLabel()).isEqualTo("our-sre");
                });
    }

    @Test
    void mcpServersWrapperWithDashYFlagAndHeadersIsRecognized() {
        String pasted = """
                {"mcpServers": {"dynatrace": {"command": "npx",
                  "args": ["-y", "mcp-remote", "https://dt.example.com/mcp",
                    "--header", "Authorization: Bearer ${DT_TOKEN}"]}}}""";
        var result = DynatraceMcpConfigImport.parse(pasted);
        assertThat(result).isInstanceOfSatisfying(DynatraceMcpConfigImport.Recognized.class,
                recognized -> {
                    assertThat(recognized.endpoint()).isEqualTo("https://dt.example.com/mcp");
                    assertThat(recognized.candidateHeaders())
                            .containsEntry("Authorization", "Bearer ${DT_TOKEN}");
                });
    }

    @Test
    void aDifferentCommandIsRefusedNeverExecuted() {
        var result = DynatraceMcpConfigImport.parse("""
                {"command": "rm", "args": ["-rf", "/"]}""");
        assertThat(result).isInstanceOfSatisfying(DynatraceMcpConfigImport.Unrecognized.class,
                unrecognized -> assertThat(unrecognized.reason()).containsIgnoringCase("never"));
    }

    @Test
    void npxWithoutMcpRemoteIsRefused() {
        var result = DynatraceMcpConfigImport.parse("""
                {"command": "npx", "args": ["-y", "some-other-server"]}""");
        assertThat(result).isInstanceOf(DynatraceMcpConfigImport.Unrecognized.class);
    }

    @Test
    void mcpRemoteWithNoUrlIsRefused() {
        var result = DynatraceMcpConfigImport.parse("""
                {"command": "npx", "args": ["-y", "mcp-remote"]}""");
        assertThat(result).isInstanceOf(DynatraceMcpConfigImport.Unrecognized.class);
    }

    @Test
    void garbageInputIsRefused() {
        var result = DynatraceMcpConfigImport.parse("not json and not a url");
        assertThat(result).isInstanceOf(DynatraceMcpConfigImport.Unrecognized.class);
    }

    @Test
    void blankInputIsRefused() {
        assertThat(DynatraceMcpConfigImport.parse("")).isInstanceOf(DynatraceMcpConfigImport.Unrecognized.class);
        assertThat(DynatraceMcpConfigImport.parse(null)).isInstanceOf(DynatraceMcpConfigImport.Unrecognized.class);
    }
}
