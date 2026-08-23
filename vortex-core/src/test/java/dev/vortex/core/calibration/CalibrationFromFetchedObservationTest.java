package dev.vortex.core.calibration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.capacity.OperationMixCoverage;
import dev.vortex.core.capacity.ProductionObservation;
import dev.vortex.core.metrics.ObservationProvenance;
import dev.vortex.core.shared.OperationId;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.workload.Observation;
import dev.vortex.core.workload.OperationMix;
import dev.vortex.core.workload.Workload;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A calibrated workload reads the same however the observation was obtained.
 *
 * <p>This is the property that keeps a query language out of the domain. A workload calibrated from
 * Prometheus, one calibrated from Dynatrace and one built from numbers somebody typed are the same
 * kind of object, and the PromQL that produced the first lives on the observation rather than on the
 * workload — where an engineer editing a rate cannot accidentally destroy it, and where nobody has to
 * read PromQL to understand what the workload does.
 */
class CalibrationFromFetchedObservationTest {

    private static final Instant FROM = Instant.parse("2026-07-22T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-21T00:00:00Z");
    private static final OperationMix MIX = OperationMix.single(OperationId.of("getOrder"));

    private final CalibrationPolicy policy = new CalibrationPolicy();

    private static ProductionObservation typed() {
        return new ProductionObservation(RequestsPerSecond.of(40), RequestsPerSecond.of(95),
                RequestsPerSecond.of(180), MIX, "Grafana · checkout-service",
                Observation.over(FROM, TO), "");
    }

    private static ProductionObservation fetched(OperationMixCoverage coverage) {
        return new ProductionObservation(RequestsPerSecond.of(40), RequestsPerSecond.of(95),
                RequestsPerSecond.of(180), MIX, coverage, Duration.ofHours(1),
                "Grafana · checkout-service", Observation.over(FROM, TO),
                new ObservationProvenance("prometheus",
                        "max_over_time(sum(rate(http_server_requests_seconds_count"
                                + "{application=\"checkout-service\"}[3600s]))[2592000s:3600s])",
                        "checkout-service", "http://prometheus.internal:9090/graph"),
                "");
    }

    @Nested
    @DisplayName("the arithmetic does not change")
    class SameArithmetic {

        @Test
        void aFetchedObservationProposesExactlyWhatATypedOneDoes() {
            List<WorkloadSuggestion> fromTyping = policy.propose(typed());
            List<WorkloadSuggestion> fromFetching =
                    policy.propose(fetched(new OperationMixCoverage(1000, 1000)));

            assertThat(fromFetching).hasSameSizeAs(fromTyping);
            for (int i = 0; i < fromTyping.size(); i++) {
                assertThat(fromFetching.get(i).name()).isEqualTo(fromTyping.get(i).name());
                assertThat(fromFetching.get(i).rate()).isEqualTo(fromTyping.get(i).rate());
                assertThat(fromFetching.get(i).type()).isEqualTo(fromTyping.get(i).type());
                assertThat(fromFetching.get(i).derivation())
                        .isEqualTo(fromTyping.get(i).derivation());
            }
        }
    }

    @Nested
    @DisplayName("what reaches a workload")
    class Provenance {

        @Test
        void noQueryLanguageEverDoes() {
            var observation = fetched(new OperationMixCoverage(1000, 1000));

            for (WorkloadSuggestion suggestion : policy.propose(observation)) {
                Workload workload = WorkloadSuggestions.toWorkload(suggestion, MIX);
                String everythingWritten = workload.name() + workload.description()
                        + workload.source().describe()
                        + workload.source().derivationIfPresent().orElse("");

                assertThat(everythingWritten)
                        .doesNotContain("max_over_time")
                        .doesNotContain("rate(")
                        .doesNotContain("http_server_requests_seconds_count")
                        .doesNotContain("builtin:service");
            }
        }

        @Test
        void butTheObservationKeepsItSoTheNumberCanBeChecked() {
            assertThat(fetched(null).provenanceIfPresent())
                    .hasValueSatisfying(provenance ->
                            assertThat(provenance.query()).contains("max_over_time"));
        }

        @Test
        void everySuggestionRecordsThatItCameFromAnObservation() {
            for (WorkloadSuggestion suggestion : policy.propose(fetched(null))) {
                assertThat(suggestion.source().isProductionInformed()).isTrue();
                assertThat(suggestion.derivation()).isNotBlank();
            }
        }
    }

    @Nested
    @DisplayName("the coverage caveat")
    class Caveat {

        @Test
        void ridesOnTheDerivationWhenTheMixDescribesPartOfProduction() {
            // On the derivation rather than a review screen, because the derivation is what survives
            // onto the workload and into the run. A caveat shown once stops existing when somebody
            // clicks past it.
            var partial = policy.propose(fetched(new OperationMixCoverage(100_000, 80_000)));

            assertThat(partial).allSatisfy(suggestion ->
                    assertThat(suggestion.derivation())
                            .contains("80")
                            .contains("part of what the service receives"));
        }

        @Test
        void isAbsentWhenTheMixAccountsForEverything() {
            var complete = policy.propose(fetched(new OperationMixCoverage(1000, 1000)));

            assertThat(complete).allSatisfy(suggestion ->
                    assertThat(suggestion.derivation()).doesNotContain("part of what the service"));
        }

        @Test
        void isAbsentWhenNobodyMeasuredCoverageAtAll() {
            // Unknown coverage is not the same claim as full coverage, but it is also not evidence
            // of a gap — so nothing is asserted either way.
            assertThat(policy.propose(fetched(null))).allSatisfy(suggestion ->
                    assertThat(suggestion.derivation()).doesNotContain("part of what the service"));
        }
    }

    @Nested
    @DisplayName("the representative rate")
    class RepresentativeRate {

        @Test
        void isNamedAsARateRatherThanABareP95() {
            var averageLoad = policy.propose(typed()).getFirst();

            assertThat(averageLoad.derivation())
                    .contains("95th-percentile request rate")
                    .doesNotContain("observed p95 traffic");
        }

        @Test
        void fallsBackToTheAverageWhenNoP95RateWasObserved() {
            var withoutP95 = new ProductionObservation(RequestsPerSecond.of(40), null,
                    RequestsPerSecond.of(180), MIX, "", Observation.unknown(), "");

            assertThat(policy.propose(withoutP95).getFirst().derivation())
                    .contains("average request rate");
        }
    }
}
