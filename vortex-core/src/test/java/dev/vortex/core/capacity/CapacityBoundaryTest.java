package dev.vortex.core.capacity;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.analysis.EvidenceStrength;
import dev.vortex.core.analysis.StageObservation;
import dev.vortex.core.environment.DependencyMode;
import dev.vortex.core.environment.TestClassification;
import dev.vortex.core.shared.ErrorRate;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.shared.ProjectId;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.workload.Observation;
import dev.vortex.core.workload.WorkloadModel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A boundary is two edges and a claim that they are one.
 *
 * <p>The claim is the part worth testing. A run that reads PASS / FAIL / PASS has a highest passing
 * level and has not established a capacity, and reporting the higher number would be arithmetic
 * dressed as evidence.
 */
class CapacityBoundaryTest {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    private static StageObservation stage(double level, boolean compliant) {
        return new StageObservation(RequestsPerSecond.of(level), RequestsPerSecond.of(level),
                Duration.ofMillis(200), ErrorRate.ZERO, 10,
                compliant ? List.of() : List.of("threshold:latency.p95"));
    }

    private static CapacityObservation observation(BoundaryStatus status, BoundaryEdge failing) {
        return new CapacityObservation(ProjectId.of("checkout"), ExecutionId.of("exec-1"), "2.17.0",
                RequestsPerSecond.of(400), WorkloadModel.OPEN, "staging",
                TestClassification.INTEGRATED, DependencyMode.REAL, List.of("getOrder 100%"),
                "capacity", List.of("p95 < 500 ms"), Duration.ofMinutes(20), null, NOW,
                failing, status, EvidenceStrength.MEDIUM, List.of());
    }

    @Nested
    @DisplayName("classifying a run's stages")
    class Classification {

        @Test
        void oneCleanTransitionIsABoundary() {
            var status = BoundaryStatus.of(List.of(
                    stage(100, true), stage(200, true), stage(300, false), stage(400, false)));

            assertThat(status).isEqualTo(BoundaryStatus.ESTABLISHED);
            assertThat(status.isQuotable()).isTrue();
        }

        @Test
        @DisplayName("nothing violated means the far edge is above what was tested")
        void nothingViolatedIsNotAFailure() {
            var status = BoundaryStatus.of(List.of(stage(100, true), stage(200, true)));

            assertThat(status).isEqualTo(BoundaryStatus.FAR_EDGE_NOT_REACHED);
            assertThat(status.isQuotable()).isTrue();
        }

        @Test
        @DisplayName("PASS / FAIL / PASS is not a boundary, however quotable its highest level is")
        void nonMonotonicComplianceIsUnstable() {
            // Something in this run was not stable — a noisy neighbour, a cold cache, a dependency
            // that recovered. Reporting 300 as tested capacity would manufacture certainty from it.
            var status = BoundaryStatus.of(List.of(
                    stage(100, true), stage(200, false), stage(300, true)));

            assertThat(status).isEqualTo(BoundaryStatus.UNSTABLE);
            assertThat(status.isQuotable()).isFalse();
        }

        @Test
        void ordersByLoadRatherThanByTheSequenceStagesRanIn() {
            // A workload may ramp down as well as up. The question is about level, not about time.
            var descending = BoundaryStatus.of(List.of(
                    stage(300, false), stage(200, false), stage(100, true)));

            assertThat(descending).isEqualTo(BoundaryStatus.ESTABLISHED);
        }

        @Test
        void noStagesEstablishesNothing() {
            assertThat(BoundaryStatus.of(List.of())).isEqualTo(BoundaryStatus.NOT_EVALUATED);
            assertThat(BoundaryStatus.of(null)).isEqualTo(BoundaryStatus.NOT_EVALUATED);
        }
    }

    @Nested
    @DisplayName("how the boundary reads")
    class Wording {

        @Test
        void anEstablishedBoundaryNamesBothEdges() {
            var failing = new BoundaryEdge(RequestsPerSecond.of(450), Duration.ofMillis(1200),
                    ErrorRate.of(4, 100), List.of("threshold:latency.p95"), List.of());

            assertThat(observation(BoundaryStatus.ESTABLISHED, failing).boundary())
                    .contains("400")
                    .contains("450")
                    .contains("compliant")
                    .contains("non-compliant");
        }

        @Test
        void anUnstableRunSaysSoRatherThanQuotingALevel() {
            var text = observation(BoundaryStatus.UNSTABLE, null).boundary();

            assertThat(text).contains("stable tested capacity boundary was not established");
            assertThat(observation(BoundaryStatus.UNSTABLE, null).isQuotable()).isFalse();
        }

        @Test
        @DisplayName("nothing anywhere implies a discovered maximum")
        void neverClaimsAMaximum() {
            var failing = new BoundaryEdge(RequestsPerSecond.of(450), null, ErrorRate.ZERO,
                    List.of("threshold:latency.p95"), List.of());
            var subject = observation(BoundaryStatus.ESTABLISHED, failing);

            String everythingRendered = subject.label() + subject.boundaryLabel()
                    + subject.failingEdgeLabel() + subject.boundary()
                    + String.join(" ", subject.conditions());

            assertThat(everythingRendered.toLowerCase(java.util.Locale.ROOT))
                    .doesNotContain("maximum")
                    .doesNotContain("max throughput");
        }

        @Test
        void theVocabularyIsFixedSoRenderersCannotDrift() {
            var subject = observation(BoundaryStatus.ESTABLISHED, null);

            assertThat(subject.label()).isEqualTo("Tested SLO-compliant capacity");
            assertThat(subject.failingEdgeLabel()).isEqualTo("First observed non-compliant load");
            assertThat(subject.boundaryLabel()).isEqualTo("Tested capacity boundary");
        }

        @Test
        void theConditionsAreStillMandatory() {
            assertThat(observation(BoundaryStatus.ESTABLISHED, null).conditions())
                    .anyMatch(condition -> condition.startsWith("Service version"))
                    .anyMatch(condition -> condition.startsWith("Environment"))
                    .anyMatch(condition -> condition.startsWith("Dependencies"));
        }
    }

    @Nested
    @DisplayName("headroom against a boundary")
    class Headroom {

        private final HeadroomCalculator calculator = new HeadroomCalculator();
        private final ProductionObservation production = new ProductionObservation(
                null, null, RequestsPerSecond.of(200), null, "Grafana", Observation.unknown(), "");

        @Test
        void isComputedFromAnEstablishedBoundary() {
            var result = calculator.calculateFromTestedCapacity(RequestsPerSecond.of(400), true,
                    BoundaryStatus.ESTABLISHED, production);

            assertThat(result.isAvailable()).isTrue();
            assertThat(result.value()).isPresent();
        }

        @Test
        @DisplayName("is refused on an unstable one, with the reason")
        void isRefusedOnAnUnstableBoundary() {
            // Headroom is exactly the figure that ends up on a slide with none of its conditions
            // attached. Dividing one out of a level whose compliance did not move consistently with
            // load would be a confident number resting on noise.
            var result = calculator.calculateFromTestedCapacity(RequestsPerSecond.of(400), true,
                    BoundaryStatus.UNSTABLE, production);

            assertThat(result.isAvailable()).isFalse();
            assertThat(result.reason()).hasValueSatisfying(reason ->
                    assertThat(reason).contains("no tested capacity to compare")
                            .contains("varying between stages"));
        }

        @Test
        @DisplayName("a run with no objectives gets advice about objectives, not about stability")
        void theRemedyMatchesWhyItWasRefused() {
            // Telling somebody whose run had no objectives that their environment is varying
            // between stages sends them to look at entirely the wrong thing.
            var unevaluated = calculator.calculateFromTestedCapacity(RequestsPerSecond.of(400), true,
                    BoundaryStatus.NOT_EVALUATED, production);

            assertThat(unevaluated.reason()).hasValueSatisfying(reason -> assertThat(reason)
                    .contains("objectives were not evaluated")
                    .doesNotContain("varying between stages"));
        }

        @Test
        void isAlwaysEitherAValueOrAStatedReason() {
            // The property the whole EvidenceContext change exists to guarantee: never silence.
            for (var status : BoundaryStatus.values()) {
                var result = calculator.calculateFromTestedCapacity(RequestsPerSecond.of(400), true, status,
                        production);
                assertThat(result.isAvailable() || result.reason().isPresent())
                        .as("status " + status)
                        .isTrue();
            }
            assertThat(calculator.calculateFromTestedCapacity(null, false, null).reason()).isPresent();
        }
    }

    @Nested
    @DisplayName("an observation that recorded only the compliant edge")
    class LegacyShape {

        @Test
        void stillConstructsAndSaysNothingWasEstablished() {
            var old = new CapacityObservation(ProjectId.of("checkout"), ExecutionId.of("exec-1"),
                    "2.17.0", RequestsPerSecond.of(400), WorkloadModel.OPEN, "staging",
                    TestClassification.INTEGRATED, DependencyMode.REAL, List.of(), "capacity",
                    List.of(), Duration.ofMinutes(20), null, NOW);

            assertThat(old.firstNonCompliantIfPresent()).isEmpty();
            assertThat(old.boundaryStatus()).isEqualTo(BoundaryStatus.NOT_EVALUATED);
            assertThat(old.boundaryStrength()).isEqualTo(EvidenceStrength.INSUFFICIENT);
            assertThat(old.isQuotable()).isFalse();
        }
    }
}
