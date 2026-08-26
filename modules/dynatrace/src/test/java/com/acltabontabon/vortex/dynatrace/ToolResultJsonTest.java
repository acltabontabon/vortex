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
    void jsonWithABarePrefixAndNoFenceIsExtracted() {
        // The other shape Dynatrace's execute_dql tool actually answers with — no fence at all, just
        // a "DQL Response: " prefix directly followed by the JSON.
        var found = ToolResultJson.extractStructured(
                "DQL Response: [{\"dt.entity.service\":\"SERVICE-1\",\"requests\":[1072,1080,1078]}]");

        assertThat(found).isPresent();
        assertThat(found.get().at("/0/requests/2").asInt()).isEqualTo(1078);
    }

    @Test
    void jsonWithBackslashEscapedQuotesInsteadOfRealOnesIsUnescapedAndExtracted() {
        // Also observed against a real Dynatrace endpoint: the embedded JSON's quotes come through
        // backslash-escaped (\") rather than bare ("), as if the whole thing had once been a JSON
        // string value and lost its own surrounding quotes. Written here as a raw (non-text-block)
        // literal so the source itself contains the literal backslash-quote sequences under test.
        var found = ToolResultJson.extractStructured("DQL Response: [{\\\"dt.entity.service\\\":"
                + "\\\"SERVICE-1\\\",\\\"peak\\\":0.335555555555555,\\\"average\\\":0.301351517953,"
                + "\\\"p95\\\":0.331386887698}]");

        assertThat(found).isPresent();
        assertThat(found.get().at("/0/dt.entity.service").asText()).isEqualTo("SERVICE-1");
        assertThat(found.get().at("/0/peak").asDouble()).isEqualTo(0.335555555555555);
    }

    @Test
    void aStringValueContainingBracesDoesNotConfuseTheBoundaryScan() {
        var found = ToolResultJson.extractStructured(
                "Result: {\"filter\":\"dt.entity.service == \\\"has [brackets] and {braces}\\\"\",\"requests\":7}");

        assertThat(found).isPresent();
        assertThat(found.get().at("/requests").asInt()).isEqualTo(7);
    }

    @Test
    void theLargestCandidateWinsOverAnEarlierSmallUnrelatedJsonFragment() {
        // A small, syntactically-valid JSON object earlier in surrounding prose (e.g. client
        // formatting metadata) must not be mistaken for the real payload, which is virtually always
        // the biggest JSON blob in the text.
        var found = ToolResultJson.extractStructured("""
                Query settings: {"interval": "1h"}

                DQL Response: {"dt.entity.service": "SERVICE-1", "requests": [1080, 1082, 1090]}""");

        assertThat(found).isPresent();
        assertThat(found.get().at("/requests/2").asInt()).isEqualTo(1090);
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
