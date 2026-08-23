package com.acltabontabon.vortex.core.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.metrics.Aggregation;
import com.acltabontabon.vortex.core.metrics.MetricObservation;
import com.acltabontabon.vortex.core.metrics.MetricSource;
import com.acltabontabon.vortex.core.metrics.MetricUnit;
import com.acltabontabon.vortex.core.metrics.TimeWindow;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A measurement shown to a person has to be readable by one.
 *
 * <p>These are display rules, not arithmetic: the stored value never changes, and every report and
 * page renders through the same method so a figure cannot read one way on screen and another in a
 * PDF.
 */
class ObservationDisplayTest {

    private static final TimeWindow WINDOW = new TimeWindow(
            Instant.parse("2026-08-21T10:00:00Z"), Instant.parse("2026-08-21T10:10:00Z"));

    @Test
    @DisplayName("bytes are scaled, because nobody compares a heap by counting digits")
    void bytesAreReadable() {
        MetricObservation heap = observation(MetricUnit.BYTES, 5_419_040_765d);

        assertThat(heap.display()).isEqualTo("5.0 GB");
        assertThat(heap.display(93_908_824d)).isEqualTo("89.6 MB");
    }

    @Test
    @DisplayName("small byte counts stay exact, since scaling them would lose the point")
    void smallByteCountsAreNotScaled() {
        assertThat(observation(MetricUnit.BYTES, 512).display()).isEqualTo("512 bytes");
        assertThat(observation(MetricUnit.BYTES, 0).display()).isEqualTo("0 bytes");
    }

    @Test
    @DisplayName("powers of 1024, because that is what a JVM means by a megabyte")
    void binaryScale() {
        assertThat(observation(MetricUnit.BYTES, 1024).display()).isEqualTo("1.0 KB");
        assertThat(observation(MetricUnit.BYTES, 1_048_576).display()).isEqualTo("1.0 MB");
    }

    @Test
    void otherUnitsAreUnchanged() {
        assertThat(observation(MetricUnit.PERCENT, 94).display()).isEqualTo("94 %");
        assertThat(observation(MetricUnit.COUNT, 15).display()).isEqualTo("15");
        assertThat(observation(MetricUnit.MILLISECONDS, 281.5).display()).isEqualTo("281.50 ms");
    }

    private static MetricObservation observation(MetricUnit unit, double value) {
        return MetricObservation.of("metric:test", "test.metric", MetricSource.ACTUATOR, unit,
                Aggregation.MAX, value, WINDOW);
    }
}
