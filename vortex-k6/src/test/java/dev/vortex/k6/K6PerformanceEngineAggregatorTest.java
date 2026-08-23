package dev.vortex.k6;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.shared.ExecutionId;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The join that waits for the raw-metric aggregator to finish before its result is trusted.
 *
 * <p>A timed-out join used to be indistinguishable from a completed one: {@code execute()} would
 * silently substitute an empty time series with nothing in the logs to explain why. These pin down
 * the signal that now makes that visible, using an artificially short timeout against a
 * deliberately slow thread rather than waiting out the real 30-second production value.
 */
class K6PerformanceEngineAggregatorTest {

    private Thread thread;

    @AfterEach
    void tearDown() throws InterruptedException {
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            thread.join(Duration.ofSeconds(1));
        }
    }

    @Test
    @DisplayName("a thread that finishes within the timeout is reported as finished")
    void aFinishedAggregatorIsReportedAsFinished() throws InterruptedException {
        thread = Thread.ofVirtual().start(() -> { });
        thread.join();

        boolean finished = K6PerformanceEngine.awaitAggregator(
                thread, Duration.ofSeconds(1), ExecutionId.of("exec1"));

        assertThat(finished).isTrue();
    }

    @Test
    @DisplayName("a thread still running past the timeout is reported as not finished")
    void aSlowAggregatorIsReportedAsNotFinished() {
        thread = Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        boolean finished = K6PerformanceEngine.awaitAggregator(
                thread, Duration.ofMillis(50), ExecutionId.of("exec1"));

        assertThat(finished).isFalse();
    }
}
