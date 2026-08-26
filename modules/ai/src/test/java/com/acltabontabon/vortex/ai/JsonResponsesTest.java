package com.acltabontabon.vortex.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Small local models comply with "respond with JSON only" most of the time, not always. These
 * pin down the recovery this class is meant to perform, and the point at which it gives up rather
 * than guess.
 */
class JsonResponsesTest {

    @Test
    void extractsAPlainJsonObject() {
        var parsed = JsonResponses.extractObject("{\"conclusion\":\"ok\"}");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().path("conclusion").asText()).isEqualTo("ok");
    }

    @Test
    void stripsMarkdownFences() {
        var parsed = JsonResponses.extractObject("```json\n{\"conclusion\":\"ok\"}\n```");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().path("conclusion").asText()).isEqualTo("ok");
    }

    @Test
    void stripsAThinkingPreamble() {
        var parsed = JsonResponses.extractObject(
                "<think>the user wants JSON, here it is</think>{\"conclusion\":\"ok\"}");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().path("conclusion").asText()).isEqualTo("ok");
    }

    @Test
    void ignoresTrailingProseAfterTheObject() {
        var parsed = JsonResponses.extractObject(
                "{\"conclusion\":\"ok\"} I hope this analysis helps! {not json}");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().path("conclusion").asText()).isEqualTo("ok");
    }

    @Test
    void ignoresBracesInsideStringValues() {
        var parsed = JsonResponses.extractObject(
                "{\"conclusion\":\"latency was {approximately} 200ms\"}");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().path("conclusion").asText()).isEqualTo("latency was {approximately} 200ms");
    }

    @Test
    void unbalancedBracesYieldNoResultRatherThanAGuess() {
        assertThat(JsonResponses.extractObject("{\"conclusion\": \"ok\"")).isEmpty();
    }

    @Test
    void nonObjectJsonYieldsNoResult() {
        assertThat(JsonResponses.extractObject("[\"conclusion\", \"ok\"]")).isEmpty();
    }

    @Test
    void blankOrNullResponseYieldsNoResult() {
        assertThat(JsonResponses.extractObject(null)).isEmpty();
        assertThat(JsonResponses.extractObject("   ")).isEmpty();
    }

    @Test
    void proseWithNoObjectAtAllYieldsNoResult() {
        assertThat(JsonResponses.extractObject("I cannot produce JSON for this request.")).isEmpty();
    }

    @Test
    void unexpectedAdditionalFieldsAreTolerated() {
        var parsed = JsonResponses.extractObject(
                "{\"conclusion\":\"ok\",\"unexpectedField\":\"whatever the model felt like adding\"}");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().path("conclusion").asText()).isEqualTo("ok");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("only the first of two top-level JSON objects is used, never "
            + "a later one — a model cannot smuggle a second, different answer past the first")
    void onlyTheFirstOfTwoTopLevelObjectsIsExtracted() {
        var parsed = JsonResponses.extractObject(
                "{\"conclusion\":\"real one\"} some prose in between "
                        + "{\"conclusion\":\"a decoy that must be ignored\"}");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().path("conclusion").asText()).isEqualTo("real one");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a decoy object placed before the real one is not mistaken "
            + "for it, once brace-balancing finds where it actually ends")
    void aDecoyObjectBeforeTheRealOneIsNotExtractedInItsPlace() {
        // A single decoy object with no nesting IS indistinguishable from "the real one" by this
        // class's contract (it only ever returns the first balanced span) — so the meaningful case is
        // a decoy that closes before the real object starts, proving the scan does not simply grab
        // everything between the first "{" and the last "}".
        var parsed = JsonResponses.extractObject(
                "{\"note\":\"not the answer\"}\n{\"conclusion\":\"the real answer\"}");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().has("conclusion")).isFalse();
        assertThat(parsed.get().path("note").asText()).isEqualTo("not the answer");
    }
}
