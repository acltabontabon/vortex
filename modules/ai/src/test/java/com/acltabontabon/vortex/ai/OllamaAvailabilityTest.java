package com.acltabontabon.vortex.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The circuit breaker half of availability: repeated model-call failures should stop Vortex from
 * hammering a misbehaving Ollama with more requests, distinct from the plain reachability probe
 * (never contacted here — {@code http://127.0.0.1:1} fails fast and deterministically, so these
 * tests do not depend on a real Ollama being absent or present).
 */
class OllamaAvailabilityTest {

    private final AiSettings settings =
            new AiSettings("ollama", "http://127.0.0.1:1", "test-model", Duration.ofSeconds(1), false);
    private final OllamaAvailability availability = new OllamaAvailability(settings);

    @Test
    void doesNotBackOffBeforeTheFailureThreshold() {
        for (int i = 0; i < OllamaAvailability.FAILURE_THRESHOLD - 1; i++) {
            availability.recordFailure();
        }

        assertThat(availability.check().problem()).doesNotContain("failed on the last");
    }

    @Test
    void backsOffOnceConsecutiveFailuresReachTheThreshold() {
        for (int i = 0; i < OllamaAvailability.FAILURE_THRESHOLD; i++) {
            availability.recordFailure();
        }

        var result = availability.check();

        assertThat(result.available()).isFalse();
        assertThat(result.problem())
                .contains("failed on the last " + OllamaAvailability.FAILURE_THRESHOLD
                        + " requests in a row");
        assertThat(result.remedy()).isNotBlank();
    }

    @Test
    void aSuccessClearsTheFailureStreak() {
        for (int i = 0; i < OllamaAvailability.FAILURE_THRESHOLD - 1; i++) {
            availability.recordFailure();
        }
        availability.recordSuccess();
        for (int i = 0; i < OllamaAvailability.FAILURE_THRESHOLD - 1; i++) {
            availability.recordFailure();
        }

        assertThat(availability.check().problem())
                .as("the streak that would have tripped the breaker was reset by the success")
                .doesNotContain("failed on the last");
    }
}
