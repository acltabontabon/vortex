package com.acltabontabon.vortex.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * The pure-logic pieces of the hardening around a model call: which failures are worth retrying,
 * how much of a failure's message is safe to log, and keeping a prompt inside {@code NUM_CTX}'s
 * budget. These are exercised directly, without a live or fake model, because none of them depend
 * on {@code chat}/{@code availability} — the retry loop and deadline that wrap an actual call are
 * verified by hand against a real model (see CONTRIBUTING.md), not here.
 */
class OllamaPerformanceAssistantHardeningTest {

    // ------------------------------------------------------------------ isRetryable

    @Test
    void aSocketTimeoutIsRetryable() {
        var failure = new RuntimeException("timed out", new SocketTimeoutException("read timed out"));

        assertThat(OllamaPerformanceAssistant.isRetryable(failure)).isTrue();
    }

    @Test
    void aConnectExceptionIsRetryable() {
        var failure = new RuntimeException("refused", new ConnectException("Connection refused"));

        assertThat(OllamaPerformanceAssistant.isRetryable(failure)).isTrue();
    }

    @Test
    void aResourceAccessExceptionIsRetryable() {
        var failure = new ResourceAccessException("I/O error", new java.io.IOException("reset"));

        assertThat(OllamaPerformanceAssistant.isRetryable(failure)).isTrue();
    }

    @Test
    void retryabilityIsCheckedThroughTheWholeCauseChain() {
        var wrapped = new RuntimeException("outer",
                new IllegalStateException("middle", new SocketTimeoutException("inner")));

        assertThat(OllamaPerformanceAssistant.isRetryable(wrapped)).isTrue();
    }

    @Test
    void aRejectedRequestIsNotRetryable() {
        // A request the server actively rejected — an unknown model name, for instance — would fail
        // the same way on every attempt, so retrying it only delays the same outcome.
        var failure = new RuntimeException("model 'nonexistent' not found");

        assertThat(OllamaPerformanceAssistant.isRetryable(failure)).isFalse();
    }

    // ------------------------------------------------------------------ sanitizeForLog

    @Test
    void aNullFailureSanitizesToAFixedMessage() {
        assertThat(OllamaPerformanceAssistant.sanitizeForLog(null)).isEqualTo("unknown error");
    }

    @Test
    void aFailureWithNoMessageSanitizesToItsClassName() {
        assertThat(OllamaPerformanceAssistant.sanitizeForLog(new IllegalStateException()))
                .isEqualTo("IllegalStateException");
    }

    @Test
    void aShortMessagePassesThroughUnchanged() {
        assertThat(OllamaPerformanceAssistant.sanitizeForLog(new RuntimeException("connection reset")))
                .isEqualTo("connection reset");
    }

    @Test
    void aLongMessageIsTruncated() {
        String longMessage = "x".repeat(500);

        String sanitized = OllamaPerformanceAssistant.sanitizeForLog(new RuntimeException(longMessage));

        assertThat(sanitized).hasSize(201).endsWith("…").startsWith("x".repeat(200));
    }

    // ------------------------------------------------------------------ capExplainWorkloadLength

    @Test
    void aShortExplanationIsReturnedUnchanged() {
        assertThat(OllamaPerformanceAssistant.capExplainWorkloadLength("Short and fine."))
                .isEqualTo("Short and fine.");
    }

    @Test
    void anOverlyLongExplanationIsTruncated() {
        String long_ = "x".repeat(5000);

        String capped = OllamaPerformanceAssistant.capExplainWorkloadLength(long_);

        assertThat(capped).hasSize(1000);
    }

    // ------------------------------------------------------------------ renderWithinBudget

    private Map<String, String> shortAnalyzeValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (String key : new String[] {"testKind", "question", "verdict", "classification",
                "workload", "traffic", "measurements", "thresholds", "stages", "breakpoints",
                "operations", "evidenceIds", "absentTelemetry"}) {
            values.put(key, "-");
        }
        return values;
    }

    @Test
    void aPromptWellUnderBudgetIsReturnedUnchanged() {
        String rendered = OllamaPerformanceAssistant.renderWithinBudget(
                PromptLibrary.ANALYZE_EXECUTION, shortAnalyzeValues());

        assertThat(rendered)
                .isEqualTo(PromptLibrary.render(PromptLibrary.ANALYZE_EXECUTION, shortAnalyzeValues()));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("an oversized operations section is trimmed until the prompt "
            + "fits, rather than left to silently exceed the model's context window")
    void anOversizedOperationsSectionIsTrimmedToFitTheBudget() {
        StringBuilder hugeOperations = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            hugeOperations.append("- Operation ").append(i)
                    .append(": 1% of traffic, 10 req/s, p95 5ms, errors 0% "
                            + "[metric:operation.op").append(i).append(".rate.achieved]\n");
        }
        Map<String, String> values = shortAnalyzeValues();
        values.put("operations", hugeOperations.toString());

        String naive = PromptLibrary.render(PromptLibrary.ANALYZE_EXECUTION, values);
        String trimmed = OllamaPerformanceAssistant.renderWithinBudget(
                PromptLibrary.ANALYZE_EXECUTION, values);

        assertThat(trimmed.length())
                .as("trimming must actually shrink an over-budget prompt")
                .isLessThan(naive.length());
        assertThat(trimmed).contains("further lines omitted to keep this prompt");
        // The rules preamble and the evidence-id section — the load-bearing parts regardless of
        // which end a runtime might truncate from — must survive trimming untouched.
        assertThat(trimmed).contains("Cite evidence by its exact identifier");
        assertThat(trimmed).contains("=== AVAILABLE EVIDENCE");
    }
}
