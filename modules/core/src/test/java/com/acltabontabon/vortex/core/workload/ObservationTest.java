package com.acltabontabon.vortex.core.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("when an observation was taken")
class ObservationTest {

    private static final Instant EIGHT_PM = Instant.parse("2026-08-18T20:00:00Z");
    private static final Instant NINE_PM = Instant.parse("2026-08-18T21:00:00Z");

    @Nested
    @DisplayName("a window")
    class Windows {

        @Test
        @DisplayName("keeps both ends, because the period is part of what the number means")
        void bothEndsSurvive() {
            var window = Observation.over(EIGHT_PM, NINE_PM);

            assertThat(window.isWindow()).isTrue();
            assertThat(window.isPoint()).isFalse();
            assertThat(window.fromIfPresent()).hasValue(EIGHT_PM);
            assertThat(window.toIfPresent()).hasValue(NINE_PM);
            assertThat(window.span()).hasValue(Duration.ofHours(1));
        }

        @Test
        @DisplayName("is anchored on its end, which is when it stopped being true")
        void stalenessAnchorsOnTheEnd() {
            assertThat(Observation.over(EIGHT_PM, NINE_PM).anchor()).hasValue(NINE_PM);
        }

        @Test
        void cannotEndBeforeItBegins() {
            assertThatThrownBy(() -> Observation.over(NINE_PM, EIGHT_PM))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot end");
        }
    }

    @Nested
    @DisplayName("a point reading")
    class Points {

        @Test
        @DisplayName("is kept distinct from a zero-length window")
        void aPointIsNotAnEmptyWindow() {
            var point = Observation.at(EIGHT_PM);

            // "sampled once" and "measured over no time at all" are different claims, and only one
            // of them is ever true. Collapsing them would make a gauge reading look like a window
            // somebody forgot to fill in.
            assertThat(point.isPoint()).isTrue();
            assertThat(point.isWindow()).isFalse();
            assertThat(point.span()).isEmpty();
            assertThat(point.anchor()).hasValue(EIGHT_PM);
        }
    }

    @Nested
    @DisplayName("an unrecorded time")
    class Unknown {

        @Test
        @DisplayName("stays absent rather than becoming a placeholder date")
        void absentStaysAbsent() {
            var unknown = Observation.unknown();

            assertThat(unknown.isKnown()).isFalse();
            assertThat(unknown.anchor()).isEmpty();
            assertThat(unknown.span()).isEmpty();
        }
    }
}
