package com.acltabontabon.vortex.core.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.shared.Concurrency;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MeasuredResults#deliveredFraction(com.acltabontabon.vortex.core.shared.LoadLevel)} — the
 * caller-supplied comparison basis a ramp-aware shortfall check needs, and the no-arg overload
 * that keeps every existing caller comparing against the raw peak.
 */
class MeasuredResultsTest {

    @Test
    @DisplayName("delivered fraction compares achieved rate against the basis the caller supplies")
    void comparesAgainstTheSuppliedBasis() {
        MeasuredResults results = Fixtures.results(180, 0.0); // achievedRate = 19.8

        assertThat(results.deliveredFraction(RequestsPerSecond.of(19.8)))
                .hasValueSatisfying(fraction -> assertThat(fraction).isEqualTo(1.0));
        assertThat(results.deliveredFraction(RequestsPerSecond.of(39.6)))
                .hasValueSatisfying(fraction -> assertThat(fraction).isEqualTo(0.5));
    }

    @Test
    @DisplayName("the no-arg overload still compares against the raw target, unchanged")
    void noArgOverloadStillUsesTheRawTarget() {
        MeasuredResults results = Fixtures.results(180, 0.0); // targetLoad = 20, achievedRate = 19.8

        assertThat(results.deliveredFraction())
                .isEqualTo(results.deliveredFraction(RequestsPerSecond.of(20)));
    }

    @Test
    @DisplayName("no achieved rate means nothing was delivered to compare")
    void emptyWhenAchievedRateIsAbsent() {
        MeasuredResults base = Fixtures.results(180, 0.0);
        MeasuredResults noAchievedRate = new MeasuredResults(base.window(), base.targetLoad(), null,
                base.requests(), base.failures(), base.latency(), Map.of(), MetricSeries.empty(),
                java.util.List.of());

        assertThat(noAchievedRate.deliveredFraction(RequestsPerSecond.of(20))).isEmpty();
    }

    @Test
    @DisplayName("a basis in a different quantity is not comparable")
    void emptyWhenBasisIsNotARequestRate() {
        MeasuredResults results = Fixtures.results(180, 0.0);

        assertThat(results.deliveredFraction(Concurrency.of(50))).isEmpty();
    }

    @Test
    @DisplayName("a zero basis has nothing to divide by")
    void emptyWhenBasisIsZero() {
        MeasuredResults results = Fixtures.results(180, 0.0);

        assertThat(results.deliveredFraction(RequestsPerSecond.of(0))).isEmpty();
    }
}
