package dev.vortex.core.calibration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.capacity.ProductionObservation;
import dev.vortex.core.shared.Concurrency;
import dev.vortex.core.shared.OperationId;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.shared.WorkloadId;
import dev.vortex.core.workload.ConstantArrivalRateShape;
import dev.vortex.core.workload.ConstantConcurrencyShape;
import dev.vortex.core.workload.LoadShape;
import dev.vortex.core.workload.Observation;
import dev.vortex.core.workload.OperationMix;
import dev.vortex.core.workload.TestType;
import dev.vortex.core.workload.Workload;
import dev.vortex.core.workload.WorkloadSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A workload's production assumption expires quietly, and noticing must not cost credibility.
 *
 * <p>Most of what follows is about refusing to answer. A false "your workload is out of date" costs
 * somebody an afternoon proving it is not, and it costs the check its credibility permanently — so
 * every case where provenance is missing or the quantities do not match resolves to silence with a
 * reason, never to a verdict.
 */
class WorkloadDriftTest {

    private static final Instant LAST_MONTH = Instant.parse("2026-07-22T00:00:00Z");
    private static final Instant TODAY = Instant.parse("2026-08-21T00:00:00Z");
    private static final OperationMix MIX = OperationMix.single(OperationId.of("getOrder"));

    private final CalibrationPolicy policy = new CalibrationPolicy();
    private final WorkloadDrift drift = new WorkloadDrift(policy);

    private static ProductionObservation peaking(double peak, Observation window) {
        return new ProductionObservation(RequestsPerSecond.of(peak * 0.25),
                RequestsPerSecond.of(peak * 0.5), RequestsPerSecond.of(peak), MIX,
                "Grafana · checkout-service", window, "");
    }

    private static ProductionObservation peaking(double peak) {
        return peaking(peak, Observation.over(LAST_MONTH, TODAY));
    }

    /** A workload as {@code /production/apply} would actually have created it. */
    private Workload derivedFrom(ProductionObservation observation, TestType type) {
        return WorkloadSuggestions.toWorkload(
                policy.propose(observation).stream()
                        .filter(suggestion -> suggestion.type() == type)
                        .findFirst()
                        .orElseThrow(),
                MIX);
    }

    @Nested
    @DisplayName("questions this check does not answer")
    class NotAssessable {

        @Test
        @DisplayName("a hand-written workload has no production assumption to drift from")
        void manualWorkloadsAreNotAssessed() {
            Workload byHand = new Workload(WorkloadId.of("by-hand"), "by-hand", "", "",
                    TestType.AVERAGE_LOAD, MIX, new ConstantArrivalRateShape(RequestsPerSecond.of(40),
                            java.time.Duration.ofMinutes(10)),
                    null, WorkloadSource.manual(), null);

            var assessment = drift.assess(byHand, peaking(180));

            assertThat(assessment).isInstanceOf(WorkloadDrift.NotAssessable.class);
            assertThat(assessment.statement())
                    .contains(WorkloadSource.SourceKind.MANUAL.label());
        }

        /**
         * The guarantee the whole check rests on. A workload edited by hand in YAML may record no
         * window at all, and "we do not know when this came from" must never become "this is out of
         * date" — those are not close to the same claim.
         */
        @Test
        @DisplayName("no recorded observation window is never reported as drift")
        void missingProvenanceIsNeverDrift() {
            Workload derived = derivedFrom(peaking(120), TestType.AVERAGE_LOAD);
            Workload provenanceLost = new Workload(derived.id(), derived.name(),
                    derived.description(), derived.objective(), derived.type(),
                    derived.operations(), derived.shape(), derived.thresholds(),
                    // Same kind, no window: exactly what a hand-edited vortex.yaml produces.
                    new WorkloadSource(derived.source().kind(), derived.source().detail(),
                            Observation.unknown(), derived.source().derivation()),
                    derived.k6Options());

            // Production has since tripled. A check that guessed would call this drift.
            var assessment = drift.assess(provenanceLost, peaking(400));

            assertThat(assessment).isInstanceOf(WorkloadDrift.NotAssessable.class);
            assertThat(assessment.statement()).contains("no observation window");
        }

        @Test
        @DisplayName("virtual users cannot drift against an arrival rate")
        void differentQuantitiesAreNotCompared() {
            Workload derived = derivedFrom(peaking(180), TestType.AVERAGE_LOAD);
            Workload inVirtualUsers = new Workload(derived.id(), derived.name(),
                    derived.description(), derived.objective(), derived.type(),
                    derived.operations(),
                    new ConstantConcurrencyShape(Concurrency.of(50),
                            java.time.Duration.ofMinutes(10)),
                    derived.thresholds(), derived.source(), derived.k6Options());

            var assessment = drift.assess(inVirtualUsers, peaking(400));

            assertThat(assessment).isInstanceOf(WorkloadDrift.NotAssessable.class);
            assertThat(assessment.statement()).contains("different quantities");
        }

        @Test
        @DisplayName("with no production traffic recorded there is nothing to compare against")
        void noProductionObservation() {
            Workload derived = derivedFrom(peaking(180), TestType.AVERAGE_LOAD);

            var assessment = drift.assess(derived, null);

            assertThat(assessment).isInstanceOf(WorkloadDrift.NotAssessable.class);
            assertThat(assessment.statement()).contains("No production traffic is recorded");
        }
    }

    @Nested
    @DisplayName("comparing what was assumed with what production now does")
    class Comparison {

        @Test
        @DisplayName("unchanged traffic leaves the workload unchanged")
        void sameObservationDoesNotDrift() {
            ProductionObservation observation = peaking(180);
            Workload derived = derivedFrom(observation, TestType.AVERAGE_LOAD);

            var assessment = drift.assess(derived, observation);

            assertThat(assessment).isInstanceOf(WorkloadDrift.Unchanged.class);
            assertThat(assessment.statement()).contains("would still propose");
        }

        @Test
        @DisplayName("traffic that moved is reported, with the arithmetic that says so")
        void grownTrafficDrifts() {
            Workload derived = derivedFrom(peaking(120), TestType.AVERAGE_LOAD);

            ProductionObservation now = peaking(400, Observation.over(TODAY, TODAY.plusSeconds(3600)));
            var assessment = drift.assess(derived, now);

            assertThat(assessment).isInstanceOf(WorkloadDrift.Drifted.class);
            WorkloadDrift.Drifted drifted = (WorkloadDrift.Drifted) assessment;

            // Asserted against the policy's own proposal rather than a literal, so the check and the
            // suggestion it points somebody towards can never disagree about the number.
            RequestsPerSecond proposed = policy.propose(now).stream()
                    .filter(suggestion -> suggestion.type() == TestType.AVERAGE_LOAD)
                    .findFirst().orElseThrow().rate();

            assertThat(drifted.proposedNow()).isEqualTo(proposed);
            assertThat(drifted.derivation()).isNotBlank();
            assertThat(drifted.statement())
                    .contains(drifted.derivedFrom().displayWithUnit())
                    .contains(proposed.displayWithUnit());
        }

        /**
         * The rule is "Vortex would now propose a different number", measured through the policy's
         * own rounding — so a change too small to survive that rounding is not drift. There is no
         * tolerance constant anywhere, and there should not be one.
         */
        @Test
        @DisplayName("a change the rounding rule absorbs is not drift")
        void roundingIsSharedWithTheProposal() {
            ProductionObservation before = peaking(180);
            Workload derived = derivedFrom(before, TestType.AVERAGE_LOAD);

            RequestsPerSecond proposedBefore = policy.propose(before).stream()
                    .filter(suggestion -> suggestion.type() == TestType.AVERAGE_LOAD)
                    .findFirst().orElseThrow().rate();

            ProductionObservation nudged =
                    peaking(180.4, Observation.over(TODAY, TODAY.plusSeconds(3600)));
            RequestsPerSecond proposedAfter = policy.propose(nudged).stream()
                    .filter(suggestion -> suggestion.type() == TestType.AVERAGE_LOAD)
                    .findFirst().orElseThrow().rate();

            var assessment = drift.assess(derived, nudged);

            if (proposedBefore.equals(proposedAfter)) {
                assertThat(assessment).isInstanceOf(WorkloadDrift.Unchanged.class);
            } else {
                assertThat(assessment).isInstanceOf(WorkloadDrift.Drifted.class);
            }
        }
    }

    @Nested
    @DisplayName("across a whole configuration")
    class WholeConfiguration {

        @Test
        @DisplayName("only the workloads that actually moved are reported")
        void driftedFiltersToTheInterestingOnes() {
            ProductionObservation before = peaking(120);
            var configuration = dev.vortex.core.project.ProjectConfiguration.empty()
                    .withWorkload(derivedFrom(before, TestType.AVERAGE_LOAD))
                    .withWorkload(new Workload(WorkloadId.of("by-hand"), "by-hand", "", "",
                            TestType.SMOKE, MIX,
                            new ConstantArrivalRateShape(RequestsPerSecond.of(1),
                                    java.time.Duration.ofSeconds(30)),
                            null, WorkloadSource.manual(), null))
                    .withProductionObservation(
                            peaking(400, Observation.over(TODAY, TODAY.plusSeconds(3600))));

            assertThat(drift.assess(configuration)).hasSize(2);

            // The hand-written one is not silently counted as fine, and not counted as drifted either.
            assertThat(drift.drifted(configuration))
                    .hasSize(1)
                    .allSatisfy(drifted -> assertThat(drifted.workload().name())
                            .isEqualTo("average-load"));
        }

        @Test
        void noConfigurationIsNotAnError() {
            assertThat(drift.assess(null)).isEmpty();
            assertThat(drift.drifted(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("kinds the policy does not propose")
    class Unproposed {

        /**
         * Smoke, spike and soak have no counterpart in a calibration proposal, so there is no current
         * figure to compare against. That is a different answer from "it is fine".
         */
        @Test
        void aKindWithNoProposalHasNothingToCompareWith() {
            Workload smoke = new Workload(WorkloadId.of("smoke"), "smoke", "", "",
                    TestType.SMOKE, MIX,
                    new ConstantArrivalRateShape(RequestsPerSecond.of(1), java.time.Duration.ofSeconds(30)),
                    null,
                    WorkloadSource.observed("Grafana", Observation.over(LAST_MONTH, TODAY)),
                    null);

            var assessment = drift.assess(smoke, peaking(180));

            assertThat(assessment).isInstanceOf(WorkloadDrift.NotAssessable.class);
            assertThat(assessment.statement()).contains("proposes no workload of this kind");
        }

        @Test
        void everyAssessmentCanBeRendered() {
            List<WorkloadDrift.Assessment> all = List.of(
                    drift.assess(derivedFrom(peaking(180), TestType.AVERAGE_LOAD), peaking(180)),
                    drift.assess(derivedFrom(peaking(120), TestType.AVERAGE_LOAD),
                            peaking(400, Observation.over(TODAY, TODAY.plusSeconds(3600)))),
                    drift.assess(derivedFrom(peaking(180), TestType.AVERAGE_LOAD), null));

            assertThat(all).allSatisfy(assessment ->
                    assertThat(assessment.statement()).isNotBlank());
        }
    }
}
