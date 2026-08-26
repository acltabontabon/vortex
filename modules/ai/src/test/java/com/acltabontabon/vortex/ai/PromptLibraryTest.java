package com.acltabontabon.vortex.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link PromptLibrary#render} does one literal pass over the template rather than re-scanning
 * substituted text for further placeholders — deliberately, so text imported from a user's API
 * description cannot introduce placeholders of its own (see the class Javadoc). These pin down that
 * one-pass behaviour at its edges.
 */
class PromptLibraryTest {

    @Test
    void substitutesEveryPlaceholder() {
        String rendered = PromptLibrary.render(PromptLibrary.EXPLAIN_WORKLOAD,
                Map.of("observation", "Peak: 20 req/s", "suggestions", "- ramp to 30"));

        assertThat(rendered).contains("Peak: 20 req/s").contains("- ramp to 30");
    }

    private Map<String, String> analyzeValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : new String[] {"testKind", "question", "verdict", "classification",
                "workload", "traffic", "measurements", "thresholds", "stages", "breakpoints",
                "operations", "evidenceIds", "absentTelemetry"}) {
            values.put(key, "-");
        }
        return values;
    }

    @Test
    void aSubstitutedValueIsNotRescannedForPlaceholders() {
        // If the substituted value's own "{evidenceIds}" were re-scanned, it would itself be
        // replaced by the real evidenceIds value — which must not happen.
        Map<String, String> values = analyzeValues();
        values.put("question", "Injected: {evidenceIds}");
        values.put("evidenceIds", "metric:real.one");

        String rendered = PromptLibrary.render(PromptLibrary.ANALYZE_EXECUTION, values);

        assertThat(rendered)
                .as("the literal braces from the injected value must survive untouched")
                .contains("Injected: {evidenceIds}");
        // The real evidenceIds value still appears exactly once, in its own AVAILABLE EVIDENCE
        // section — not a second time inside the question, which a re-scanning pass would produce.
        assertThat(countOccurrences(rendered, "metric:real.one")).isEqualTo(1);
    }

    @Test
    void aLiteralOpenBraceImmediatelyFollowedByARealPlaceholderNameIsNotTreatedAsReopeningAScan() {
        // The prompts contain literal JSON examples, so a "{" that is not a placeholder — followed
        // by text that happens to spell a real placeholder name — must be emitted as-is, not treated
        // as the start of a new substitution.
        String rendered = PromptLibrary.render(PromptLibrary.EXPLAIN_WORKLOAD,
                Map.of("observation", "{testKind} literally, not a placeholder here",
                        "suggestions", "-"));

        assertThat(rendered).contains("{testKind} literally, not a placeholder here");
    }

    @Test
    void aPlaceholderWithNoMatchingValueIsLeftLiteral() {
        // The prompts' own JSON schema examples contain braces like "{ ... }" that are not
        // placeholders at all — render() must not require every brace pair to resolve.
        String rendered = PromptLibrary.render(PromptLibrary.ANALYZE_EXECUTION, analyzeValues());

        assertThat(rendered).contains("\"conclusion\": \"one or two sentences");
    }

    @Test
    void missingPromptResourceThrows() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PromptLibrary.load("no-such-prompt"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-prompt");
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
