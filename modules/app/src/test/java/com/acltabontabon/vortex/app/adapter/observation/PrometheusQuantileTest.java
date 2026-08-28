package com.acltabontabon.vortex.app.adapter.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class PrometheusQuantileTest {

    @Test
    void emptyInputHasNoQuantile() {
        assertThat(PrometheusQuantile.quantile(List.of(), 0.95)).isNull();
        assertThat(PrometheusQuantile.quantile(null, 0.95)).isNull();
    }

    @Test
    void aSingleSampleIsItsOwnQuantileRegardlessOfQ() {
        assertThat(PrometheusQuantile.quantile(List.of(42d), 0.95)).isEqualTo(42d);
        assertThat(PrometheusQuantile.quantile(List.of(42d), 0.0)).isEqualTo(42d);
    }

    @Test
    void unsortedInputIsSortedFirst() {
        // Same four values as PrometheusObservationSourceTest's peak/p95 fixture, given out of order.
        Double result = PrometheusQuantile.quantile(List.of(182.4, 10d, 40d, 25d), 0.95);

        assertThat(result).isCloseTo(161.04, within(0.01));
    }

    @Test
    void medianOfAnEvenCountInterpolatesBetweenTheTwoMiddleValues() {
        Double result = PrometheusQuantile.quantile(List.of(10d, 20d, 30d, 40d), 0.5);

        assertThat(result).isCloseTo(25d, within(0.001));
    }

    @Test
    void medianOfAnOddCountIsTheMiddleValueExactly() {
        Double result = PrometheusQuantile.quantile(List.of(10d, 20d, 30d), 0.5);

        assertThat(result).isEqualTo(20d);
    }

    @Test
    void duplicateValuesParticipateLikeAnyOther() {
        // n=5, rank = 0.8*(5-1) = 3.2 -> interpolate 20% of the way from the 4th value (5) to the
        // 5th (100): 5 + 0.2*(100-5) = 24.
        Double result = PrometheusQuantile.quantile(List.of(5d, 5d, 5d, 5d, 100d), 0.8);

        assertThat(result).isCloseTo(24d, within(0.001));
    }

    @Test
    void zeroPercentileIsTheMinimum() {
        assertThat(PrometheusQuantile.quantile(List.of(30d, 10d, 20d), 0.0)).isEqualTo(10d);
    }

    @Test
    void oneHundredthPercentileIsTheMaximum() {
        assertThat(PrometheusQuantile.quantile(List.of(30d, 10d, 20d), 1.0)).isEqualTo(30d);
    }

    @Test
    void maxIsNullForNoSamplesNeverZero() {
        assertThat(PrometheusQuantile.max(List.of())).isNull();
        assertThat(PrometheusQuantile.max(null)).isNull();
    }

    @Test
    void maxIsTheHighestSample() {
        assertThat(PrometheusQuantile.max(List.of(10d, 182.4, 40d, 25d))).isEqualTo(182.4);
    }
}
