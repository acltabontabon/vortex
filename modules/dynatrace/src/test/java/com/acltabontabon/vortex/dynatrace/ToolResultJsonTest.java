package com.acltabontabon.vortex.dynatrace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToolResultJsonTest {

    @Test
    void bareJsonIsParsedDirectly() {
        var found = ToolResultJson.extractStructured("""
                {"records": [{"requests": 42}]}""");

        assertThat(found).isPresent();
        assertThat(found.get().at("/records/0/requests").asInt()).isEqualTo(42);
    }

    @Test
    void jsonWrappedInProseAndAFencedCodeBlockIsExtracted() {
        // The shape Dynatrace's execute_dql tool actually answers with: an explanation, then the
        // real result inside a ```json fence — a bare JSON.readTree of the whole text fails on the
        // leading prose, which is exactly the bug this class exists to fix.
        var found = ToolResultJson.extractStructured("""
                Here's the raw response from the execute_dql call:

                ```json
                [
                  {
                    "dt.entity.service": "SERVICE-1",
                    "requests": [26003, 27972, 25378]
                  }
                ]
                ```

                Notes on structure: this is grouped by service.""");

        assertThat(found).isPresent();
        assertThat(found.get().isArray()).isTrue();
        assertThat(found.get().at("/0/requests/1").asInt()).isEqualTo(27972);
    }

    @Test
    void aFenceWithNoLanguageTagIsStillRecognized() {
        var found = ToolResultJson.extractStructured("""
                Result:
                ```
                {"requests": 5}
                ```""");

        assertThat(found).isPresent();
        assertThat(found.get().at("/requests").asInt()).isEqualTo(5);
    }

    @Test
    void genuineProseWithNoJsonAnywhereYieldsNothing() {
        var found = ToolResultJson.extractStructured(
                "The service saw about 120 requests per second on average over the window.");

        assertThat(found).isEmpty();
    }

    @Test
    void aMarkdownTableWithNoFencedJsonYieldsNothing() {
        // Deliberately not "smart" about this shape — extraction, not interpretation. A caller that
        // wants markdown-table support is a separate, explicit decision.
        var found = ToolResultJson.extractStructured("""
                | timestamp | requests |
                |-----------|----------|
                | 12:00     | 42       |""");

        assertThat(found).isEmpty();
    }

    @Test
    void blankOrNullTextYieldsNothing() {
        assertThat(ToolResultJson.extractStructured(null)).isEmpty();
        assertThat(ToolResultJson.extractStructured("")).isEmpty();
        assertThat(ToolResultJson.extractStructured("   ")).isEmpty();
    }

    @Test
    void aFencedBareScalarIsNotTreatedAsStructured() {
        // Only object/array counts as "structured" — a fenced number or string is still not data
        // TelemetryNormalizer can collect samples from.
        var found = ToolResultJson.extractStructured("""
                ```json
                42
                ```""");

        assertThat(found).isEmpty();
    }
}
