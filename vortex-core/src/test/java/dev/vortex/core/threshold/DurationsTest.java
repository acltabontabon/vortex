package dev.vortex.core.threshold;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Duration rendering matters more than it looks.
 *
 * <p>{@link Durations#compact} is what writes latency objectives into {@code vortex.yaml} and into
 * the engine's options. Losing precision here does not throw or warn — it quietly changes the test.
 * An earlier version of this method dropped the millisecond component, turning
 * "p95 below 500 ms" into "p95 below 0 s" the first time configuration was saved.
 */
class DurationsTest {

    @ParameterizedTest
    @DisplayName("compact form preserves every unit a Vortex duration can carry")
    @CsvSource({
            "500,   500ms",
            "1,     1ms",
            "999,   999ms",
            "1000,  1s",
            "1500,  1s500ms",
            "30000, 30s",
            "60000, 1m",
            "90000, 1m30s",
            "600000, 10m",
            "3600000, 1h",
            "5400000, 1h30m",
            "0,     0s"
    })
    void compactPreservesPrecision(long millis, String expected) {
        assertThat(Durations.compact(Duration.ofMillis(millis))).isEqualTo(expected);
    }

    @Test
    @DisplayName("the most common latency objective survives being written out")
    void subSecondThresholdsSurvive() {
        assertThat(Durations.compact(Duration.ofMillis(500)))
                .as("a 500 ms objective must never render as 0s")
                .isEqualTo("500ms");
    }

    @ParameterizedTest
    @CsvSource({
            "500,   500 ms",
            "1000,  1 s",
            "1500,  1.5 s",
            "90000, 1m 30s",
            "600000, 10m",
            "5400000, 1h 30m"
    })
    void displayFormIsReadable(long millis, String expected) {
        assertThat(Durations.display(Duration.ofMillis(millis))).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("windows measured in days")
    class Days {

        @org.junit.jupiter.api.Test
        @org.junit.jupiter.api.DisplayName("round-trip in the units they were written in")
        void wholeDaysStayDays() {
            // An observation window typed as 30d used to be written back to vortex.yaml as 720h.
            // Both are the same duration; only one of them is what the person put there.
            assertThat(Durations.display(java.time.Duration.ofDays(30))).isEqualTo("30d");
            assertThat(Durations.parse("30d")).isEqualTo(java.time.Duration.ofDays(30));
            assertThat(Durations.display(Durations.parse("30d"))).isEqualTo("30d");
        }

        @org.junit.jupiter.api.Test
        void aPartialDayStaysInHours() {
            assertThat(Durations.display(java.time.Duration.ofHours(36))).isEqualTo("36h");
        }

        @org.junit.jupiter.api.Test
        void shorterDurationsAreUnaffected() {
            assertThat(Durations.display(java.time.Duration.ofMinutes(10))).isEqualTo("10m");
            assertThat(Durations.display(java.time.Duration.ofMillis(500))).isEqualTo("500 ms");
        }
    }
}
