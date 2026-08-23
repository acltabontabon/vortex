package com.acltabontabon.vortex.core.resource;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.metrics.MetricUnit;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The projection's job is narrow: never grow past the target, and never let a brief spike vanish
 * between two kept points the way naive every-Nth-point thinning would.
 */
class ResourceSeriesProjectionTest {

    private static final Instant START = Instant.parse("2026-08-23T00:00:00Z");

    @Test
    void aSeriesAtOrUnderTheTargetIsReturnedUnchanged() {
        List<ResourceSample> samples = samplesEvery(5, 100, i -> 50.0);

        List<ResourceSample> projected = ResourceSeriesProjection.project(samples, List.of());

        assertThat(projected).hasSameSizeAs(samples);
    }

    @Test
    void aLongSeriesIsBoundedToTheTargetPointCount() {
        List<ResourceSample> samples = samplesEvery(5, 50_000, i -> 50.0);

        List<ResourceSample> projected = ResourceSeriesProjection.project(samples, List.of());

        assertThat(projected.size())
                .as("must be bounded, not merely smaller")
                .isLessThanOrEqualTo(ResourceSeriesProjection.TARGET_POINTS + 4);
    }

    @Test
    void aBriefSpikeSurvivesEvenInAHeavilyDownsampledSeries() {
        int total = 50_000;
        int spikeIndex = total / 2;
        List<ResourceSample> samples = samplesEvery(5, total,
                i -> i == spikeIndex ? 999.0 : 10.0);

        List<ResourceSample> projected = ResourceSeriesProjection.project(samples, List.of());

        assertThat(projected)
                .as("a single extreme sample must not be averaged or thinned away")
                .anySatisfy(sample -> assertThat(sample.value()).isEqualTo(999.0));
    }

    @Test
    void anchorInstantsAreForcedIntoTheProjectionRegardlessOfBucketing() {
        List<ResourceSample> samples = samplesEvery(5, 50_000, i -> 10.0 + (i % 7));
        Instant anchor = START.plus(Duration.ofSeconds(5).multipliedBy(12_345));

        List<ResourceSample> projected = ResourceSeriesProjection.project(samples, List.of(anchor));

        assertThat(projected)
                .as("the sample nearest an anchor instant must be present even if its bucket's "
                        + "min/max already came from elsewhere")
                .anySatisfy(sample -> assertThat(Duration.between(anchor, sample.at()).abs())
                        .isLessThanOrEqualTo(Duration.ofSeconds(5)));
    }

    @Test
    void firstAndLastSamplesAreAlwaysKept() {
        List<ResourceSample> samples = samplesEvery(5, 50_000, i -> 10.0 + (i % 3));

        List<ResourceSample> projected = ResourceSeriesProjection.project(samples, List.of());

        assertThat(projected.stream().map(ResourceSample::at))
                .contains(samples.get(0).at(), samples.get(samples.size() - 1).at());
    }

    private static List<ResourceSample> samplesEvery(int intervalSeconds, int count,
            java.util.function.IntToDoubleFunction value) {
        List<ResourceSample> samples = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            samples.add(new ResourceSample(START.plusSeconds((long) i * intervalSeconds), "provider",
                    "metric:cpu", ResourceKind.CPU, ResourceScope.SYSTEM_UNDER_TEST,
                    value.applyAsDouble(i), MetricUnit.RATIO, null));
        }
        return samples;
    }
}
