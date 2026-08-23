package dev.vortex.core.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vortex.core.metrics.ObservationProvenance;
import dev.vortex.core.shared.OperationId;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.workload.Observation;
import dev.vortex.core.workload.OperationMix;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** What a production observation will and will not claim about itself. */
class ProductionObservationTest {

    private static final Instant FROM = Instant.parse("2026-07-22T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-21T00:00:00Z");

    private static ProductionObservation fetched() {
        return new ProductionObservation(
                RequestsPerSecond.of(40), RequestsPerSecond.of(95), RequestsPerSecond.of(182.4),
                OperationMix.single(OperationId.of("getOrder")),
                new OperationMixCoverage(100_000, 80_000),
                Duration.ofHours(1),
                "Prometheus (checkout-service)",
                Observation.over(FROM, TO),
                new ObservationProvenance("prometheus", "max_over_time(...)", "checkout-service",
                        "http://prometheus.internal:9090/graph"),
                "");
    }

    @Nested
    @DisplayName("what it refuses to hold")
    class Invariants {

        @Test
        void aPeakOfZeroIsNotAnObservation() {
            assertThatThrownBy(() -> new ProductionObservation(null, null,
                    RequestsPerSecond.of(0), null, "", Observation.unknown(), ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not an observation");
        }

        @Test
        void anAverageAboveThePeakIsRejected() {
            assertThatThrownBy(() -> new ProductionObservation(RequestsPerSecond.of(200), null,
                    RequestsPerSecond.of(100), null, "", Observation.unknown(), ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot exceed the observed peak");
        }

        @Test
        void aP95RateAboveThePeakIsRejectedAndSaysItIsARate() {
            assertThatThrownBy(() -> new ProductionObservation(null, RequestsPerSecond.of(200),
                    RequestsPerSecond.of(100), null, "", Observation.unknown(), ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("p95 request rate");
        }

        @Test
        void aResolutionCoveringNoIntervalIsRejected() {
            assertThatThrownBy(() -> new ProductionObservation(null, null,
                    RequestsPerSecond.of(100), null, null, Duration.ZERO, "", Observation.unknown(),
                    null, ""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no interval");
        }
    }

    @Nested
    @DisplayName("hand entry stays possible")
    class HandEntry {

        @Test
        void theShortConstructorLeavesEverythingMachineDerivedAbsent() {
            var typed = new ProductionObservation(RequestsPerSecond.of(40), RequestsPerSecond.of(95),
                    RequestsPerSecond.of(180), null, "A dashboard somebody looked at",
                    Observation.over(FROM, TO), "");

            assertThat(typed.wasFetched()).isFalse();
            assertThat(typed.provenanceIfPresent()).isEmpty();
            assertThat(typed.sampleResolutionIfPresent()).isEmpty();
            assertThat(typed.mixCoverageIfPresent()).isEmpty();
        }

        @Test
        void anEmptyProvenanceIsTreatedAsNoProvenance() {
            // Otherwise a record with four blank strings in it would make a typed number claim to
            // have been fetched from somewhere.
            var typed = new ProductionObservation(null, null, RequestsPerSecond.of(180), null, null,
                    null, "", Observation.unknown(), new ObservationProvenance("", "", "", ""), "");

            assertThat(typed.wasFetched()).isFalse();
        }
    }

    @Nested
    @DisplayName("the quality facts")
    class QualityFacts {

        @Test
        void sayWhereTheNumbersCameFromRatherThanScoringThem() {
            var facts = String.join("\n", fetched().qualityFacts());

            assertThat(facts).contains("fetched from prometheus");
            assertThat(facts).contains(FROM.toString()).contains(TO.toString());
            assertThat(facts).contains("Sample resolution");
            assertThat(facts).contains("Mix coverage");
            assertThat(facts).contains("checkout-service");
        }

        @Test
        void neverReduceToAConfidenceGrade() {
            // A HIGH/MEDIUM/LOW badge would discard exactly the detail an engineer needs in order
            // to disagree with the baseline, which is the only thing that makes it checkable.
            assertThat(fetched().qualityFacts())
                    .noneMatch(fact -> fact.matches(".*\\b(HIGH|MEDIUM|LOW)\\b.*"));
        }

        @Test
        void distinguishAnUnattributedNumberFromASourcedOne() {
            var remembered = new ProductionObservation(null, null, RequestsPerSecond.of(180), null,
                    "", Observation.unknown(), "");

            assertThat(remembered.isAttributed()).isFalse();
            assertThat(String.join("\n", remembered.qualityFacts()))
                    .contains("entered by hand")
                    .contains("no source recorded")
                    .contains("not recorded");
        }
    }

    @Nested
    @DisplayName("rates are rates")
    class Vocabulary {

        @Test
        void theAccessorNamesTheQuantityRatherThanJustThePercentile() {
            // Renamed from p95Rate: this page sits beside a p95 *latency* objective, and a reader
            // who conflates the two calibrates a workload from entirely the wrong number.
            assertThat(fetched().p95ObservedRateIfPresent()).contains(RequestsPerSecond.of(95));
        }
    }
}
