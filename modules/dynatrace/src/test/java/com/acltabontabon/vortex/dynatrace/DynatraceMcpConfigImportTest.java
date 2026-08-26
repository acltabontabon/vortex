package com.acltabontabon.vortex.dynatrace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DynatraceMcpConfigImportTest {

    @Test
    void aBareUrlIsRecognizedDirectly() {
        var result = DynatraceMcpConfigImport.parse("https://dynatrace-mcp.internal/mcp");
        assertThat(result).isInstanceOfSatisfying(DynatraceMcpConfigImport.Recognized.class,
                recognized -> assertThat(recognized.endpoint())
                        .isEqualTo("https://dynatrace-mcp.internal/mcp"));
    }

    @Test
    void theWrappedNpxMcpRemoteShapeIsRecognized() {
        String pasted = """
                {"dynatrace": {"command": "npx", "args":["mcp-remote", "https://dynatrace-mcp.internal/mcp"]}}""";
        var result = DynatraceMcpConfigImport.parse(pasted);
        assertThat(result).isInstanceOfSatisfying(DynatraceMcpConfigImport.Recognized.class,
                recognized -> {
                    assertThat(recognized.endpoint()).isEqualTo("https://dynatrace-mcp.internal/mcp");
                    assertThat(recognized.suggestedLabel()).isEqualTo("dynatrace");
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
                recognized -> assertThat(recognized.endpoint()).isEqualTo("https://dt.example.com/mcp"));
    }

    @Test
    void aBareUrlFieldInsideAServersWrapperIsRecognized() {
        String pasted = """
                {"servers": {"dynatrace-mcp": {"url": "https://dynatrace-mcp.internal/mcp",
                  "headers": {"Authorization": "Bearer ${DT_TOKEN}"}}}}""";
        var result = DynatraceMcpConfigImport.parse(pasted);
        assertThat(result).isInstanceOfSatisfying(DynatraceMcpConfigImport.Recognized.class,
                recognized -> {
                    assertThat(recognized.endpoint()).isEqualTo("https://dynatrace-mcp.internal/mcp");
                    assertThat(recognized.suggestedLabel()).isEqualTo("dynatrace-mcp");
                });
    }

    @Test
    void mcpServersWrapperWithABareUrlIsRecognized() {
        String pasted = """
                {"mcpServers": {"dynatrace": {"url": "https://dt.example.com/mcp"}}}""";
        var result = DynatraceMcpConfigImport.parse(pasted);
        assertThat(result).isInstanceOfSatisfying(DynatraceMcpConfigImport.Recognized.class,
                recognized -> assertThat(recognized.endpoint()).isEqualTo("https://dt.example.com/mcp"));
    }

    @Test
    void aBareUrlFieldWithATypeHttpEntryIsRecognizedAndTypeIsIgnored() {
        String pasted = """
                {"type": "http", "url": "https://dynatrace-mcp.internal/mcp"}""";
        var result = DynatraceMcpConfigImport.parse(pasted);
        assertThat(result).isInstanceOfSatisfying(DynatraceMcpConfigImport.Recognized.class,
                recognized -> assertThat(recognized.endpoint())
                        .isEqualTo("https://dynatrace-mcp.internal/mcp"));
    }

    @Test
    void theNpxMcpRemoteShapeTakesPrecedenceOverACoexistingBareUrlEntry() {
        String pasted = """
                {"dynatrace": {"command": "npx", "args": ["mcp-remote", "https://npx-shape.internal/mcp"]},
                 "other": {"url": "https://bare-url-shape.internal/mcp"}}""";
        var result = DynatraceMcpConfigImport.parse(pasted);
        assertThat(result).isInstanceOfSatisfying(DynatraceMcpConfigImport.Recognized.class,
                recognized -> assertThat(recognized.endpoint())
                        .isEqualTo("https://npx-shape.internal/mcp"));
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
