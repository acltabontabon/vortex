package com.acltabontabon.vortex.core.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.workload.Observation;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** What a production service-level observation will and will not claim about itself. */
class ProductionServiceLevelTest {

    @Test
    void aServiceLevelWithNothingAtAllIsNotAnObservation() {
        assertThatThrownBy(() -> new ProductionServiceLevel(null, null, null, null, Observation.unknown(), "", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not an observation");
    }

    @Test
    void p95CannotExceedP99() {
        assertThatThrownBy(() -> new ProductionServiceLevel(
                Duration.ofMillis(900), Duration.ofMillis(500), null, null, Observation.unknown(), "", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed");
    }

    @Test
    void aZeroLatencyIsNotAnObservation() {
        assertThatThrownBy(() -> new ProductionServiceLevel(
                Duration.ZERO, null, null, null, Observation.unknown(), "", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void errorRateAloneIsAValidObservation() {
        ProductionServiceLevel level = new ProductionServiceLevel(
                null, null, null, ErrorRate.ofPercent(0.5), Observation.unknown(), "", null);

        assertThat(level.p95LatencyIfPresent()).isEmpty();
        assertThat(level.errorRateIfPresent()).contains(ErrorRate.ofPercent(0.5));
    }

    @Test
    void wasFetchedIsFalseForAHandTypedObservation() {
        ProductionServiceLevel level = new ProductionServiceLevel(
                Duration.ofMillis(500), null, null, null, Observation.unknown(), "dashboard", null);

        assertThat(level.wasFetched()).isFalse();
    }

    @Test
    void productionObservationWidensWithoutBreakingExistingCallers() {
        ProductionObservation withoutServiceLevel = new ProductionObservation(
                RequestsPerSecond.of(40), RequestsPerSecond.of(95), RequestsPerSecond.of(182.4),
                null, "dashboard", Observation.unknown(), "");

        assertThat(withoutServiceLevel.serviceLevelIfPresent()).isEmpty();
    }

    @Test
    void productionObservationCarriesAServiceLevelWhenGiven() {
        ProductionServiceLevel serviceLevel = new ProductionServiceLevel(
                Duration.ofMillis(620), Duration.ofMillis(900), Duration.ofMillis(180),
                ErrorRate.ofPercent(0.08), Observation.unknown(), "prometheus", null);
        ProductionObservation observation = new ProductionObservation(
                RequestsPerSecond.of(40), RequestsPerSecond.of(95), RequestsPerSecond.of(182.4),
                null, null, null, "dashboard", Observation.unknown(), null, "", serviceLevel);

        assertThat(observation.serviceLevelIfPresent()).contains(serviceLevel);
        assertThat(observation.serviceLevelIfPresent().orElseThrow().p95LatencyIfPresent())
                .contains(Duration.ofMillis(620));
    }
}
