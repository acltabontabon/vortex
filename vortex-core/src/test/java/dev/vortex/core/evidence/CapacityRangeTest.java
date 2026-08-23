package dev.vortex.core.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.analysis.EvidenceStrength;
import dev.vortex.core.capacity.BoundaryEdge;
import dev.vortex.core.capacity.BoundaryStatus;
import dev.vortex.core.capacity.CapacityObservation;
import dev.vortex.core.capacity.ProductionObservation;
import dev.vortex.core.environment.DependencyMode;
import dev.vortex.core.environment.TestClassification;
import dev.vortex.core.evidence.CapacityRange.MarkerKind;
import dev.vortex.core.shared.Concurrency;
import dev.vortex.core.shared.ErrorRate;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.shared.LoadLevel;
import dev.vortex.core.shared.ProjectId;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.workload.WorkloadModel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The picture may only show what the evidence established.
 *
 * <p>Everything here is about a refusal. A range that draws a capacity figure the domain will not
 * quote, or places production traffic on a scale it does not share, is worse than no picture at
 * all — it is a conclusion rendered more persuasively than any of the measured ones.
 */
class CapacityRangeTest {

    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    private static CapacityObservation observation(
            LoadLevel compliant, BoundaryStatus status, BoundaryEdge failing) {
        return new CapacityObservation(ProjectId.of("checkout"), ExecutionId.of("exec-1"), "2.17.0",
                compliant, WorkloadModel.OPEN, "staging", TestClassification.INTEGRATED,
                DependencyMode.REAL, List.of("getOrder 100%"), "capacity", List.of("p95 < 500 ms"),
                Duration.ofMinutes(20), null, NOW, failing, status, EvidenceStrength.MEDIUM,
                List.of());
    }

    private static BoundaryEdge failingAt(double level) {
        return new BoundaryEdge(RequestsPerSecond.of(level), Duration.ofMillis(820),
                ErrorRate.of(21, 1000), List.of("threshold:latency.p95"), List.of());
    }

    private static ProductionObservation productionPeaking(double peak) {
        return new ProductionObservation(RequestsPerSecond.of(peak * 0.6),
                RequestsPerSecond.of(peak * 0.8), RequestsPerSecond.of(peak), null, null, null,
                "prometheus", null, null, "");
    }

    @Nested
    @DisplayName("a boundary that was not established contributes no figure")
    class Unquotable {

        /**
         * The whole reason {@link BoundaryStatus#UNSTABLE} exists. A run that read PASS / FAIL / PASS
         * has a highest passing level, and drawing it would be arithmetic dressed as evidence.
         */
        @Test
        void unstableDrawsNoCapacityMark() {
            CapacityObservation unstable = observation(
                    RequestsPerSecond.of(400), BoundaryStatus.UNSTABLE, failingAt(450));

            CapacityRange range = CapacityRange.from(unstable, null);

            assertThat(range.drawsBoundary()).isFalse();
            assertThat(range.marker(MarkerKind.TESTED_CAPACITY)).isEmpty();
            assertThat(range.marker(MarkerKind.FIRST_FAILING)).isEmpty();
        }

        @Test
        void objectivesNeverEvaluatedDrawsNoCapacityMark() {
            CapacityObservation unevaluated = observation(
                    RequestsPerSecond.of(400), BoundaryStatus.NOT_EVALUATED, null);

            CapacityRange range = CapacityRange.from(unevaluated, null);

            assertThat(range.drawsBoundary()).isFalse();
            assertThat(range.isRenderable()).isFalse();
        }

        /**
         * The reason has to survive to the page verbatim. Two phrasings of "this established nothing"
         * would eventually say two different things about the same run.
         */
        @Test
        @DisplayName("the statement is the observation's own sentence, character for character")
        void statementIsQuotedNotComposed() {
            CapacityObservation unstable = observation(
                    RequestsPerSecond.of(400), BoundaryStatus.UNSTABLE, failingAt(450));

            assertThat(CapacityRange.from(unstable, null).statement())
                    .isEqualTo(unstable.boundary());

            CapacityObservation unevaluated = observation(
                    RequestsPerSecond.of(400), BoundaryStatus.NOT_EVALUATED, null);

            assertThat(CapacityRange.from(unevaluated, null).statement())
                    .isEqualTo(unevaluated.boundary());
        }
    }

    @Nested
    @DisplayName("production traffic shares the scale only when it measures the same thing")
    class Quantities {

        /**
         * Virtual users and arrival rate are different quantities. Placing one on the other's scale
         * would be a picture of a conversion that does not exist — the same refusal
         * {@code HeadroomCalculator} already makes, and the reason it makes it in only one place.
         */
        @Test
        void virtualUsersAndArrivalRateDoNotShareAnAxis() {
            CapacityObservation inVirtualUsers = observation(
                    Concurrency.of(50), BoundaryStatus.FAR_EDGE_NOT_REACHED, null);

            CapacityRange range = CapacityRange.from(inVirtualUsers, productionPeaking(182));

            assertThat(range.drawsProduction()).isFalse();
            assertThat(range.drawsBoundary()).isTrue();
        }

        @Test
        void matchingQuantitiesDoShareAnAxis() {
            CapacityObservation inRequests = observation(
                    RequestsPerSecond.of(390), BoundaryStatus.ESTABLISHED, failingAt(500));

            CapacityRange range = CapacityRange.from(inRequests, productionPeaking(182));

            assertThat(range.drawsProduction()).isTrue();
            assertThat(range.markers()).hasSize(3);
        }

        /**
         * The situation that motivated a separate scale from {@link LoadAxis}: production traffic has
         * grown past what was ever tested. Clamping it onto the tested mark would hide exactly the
         * fact worth seeing.
         */
        @Test
        @DisplayName("production above tested capacity is drawn above it, not clamped onto it")
        void productionMayOvertakeTestedCapacity() {
            CapacityObservation tested = observation(
                    RequestsPerSecond.of(120), BoundaryStatus.FAR_EDGE_NOT_REACHED, null);

            CapacityRange range = CapacityRange.from(tested, productionPeaking(400));

            assertThat(range.scaleTo().asDouble()).isEqualTo(400d);
            assertThat(range.position(range.marker(MarkerKind.PRODUCTION).get().level()))
                    .isEqualTo(1.0d);
            assertThat(range.position(range.marker(MarkerKind.TESTED_CAPACITY).get().level()))
                    .isLessThan(1.0d);
        }
    }

    @Nested
    @DisplayName("the shape of what was found")
    class Shape {

        @Test
        @DisplayName("nothing failed, so the scale stays open rather than closing on a ceiling")
        void farEdgeNotReachedIsOpenEnded() {
            CapacityObservation nothingFailed = observation(
                    RequestsPerSecond.of(390), BoundaryStatus.FAR_EDGE_NOT_REACHED, null);

            CapacityRange range = CapacityRange.from(nothingFailed, productionPeaking(182));

            assertThat(range.isOpenEnded()).isTrue();
            assertThat(range.drawsBoundary()).isTrue();
            assertThat(range.drawsFailing()).isFalse();
        }

        /**
         * The open end is a claim about the far side of the tested capacity, and it can only be drawn
         * at the end of the scale. With production running above tested capacity, production is the
         * last mark, and an arrow past it would say traffic goes on climbing — which is a statement
         * about production that nothing here measured.
         */
        @Test
        @DisplayName("the scale does not open past a mark the claim is not about")
        void openEndIsNotDrawnPastProduction() {
            CapacityObservation nothingFailed = observation(
                    RequestsPerSecond.of(120), BoundaryStatus.FAR_EDGE_NOT_REACHED, null);

            CapacityRange range = CapacityRange.from(nothingFailed, productionPeaking(400));

            assertThat(range.markers().getLast().kind()).isEqualTo(MarkerKind.PRODUCTION);
            assertThat(range.isOpenEnded()).isFalse();
            // The finding itself is unchanged, and the sentence still carries it.
            assertThat(range.boundaryStatus()).isEqualTo(BoundaryStatus.FAR_EDGE_NOT_REACHED);
            assertThat(range.statement()).isEqualTo(nothingFailed.boundary());
        }

        @Test
        void anEstablishedBoundaryDrawsBothEdges() {
            CapacityRange range = CapacityRange.from(
                    observation(RequestsPerSecond.of(390), BoundaryStatus.ESTABLISHED,
                            failingAt(500)), null);

            assertThat(range.isOpenEnded()).isFalse();
            assertThat(range.drawsBoundary()).isTrue();
            assertThat(range.drawsFailing()).isTrue();
        }

        @Test
        @DisplayName("production alone is renderable, and says it is one reading rather than a range")
        void productionOnlyIsAReadingNotARange() {
            CapacityRange range = CapacityRange.productionOnly(productionPeaking(840));

            assertThat(range.isRenderable()).isTrue();
            assertThat(range.isRange()).isFalse();
            assertThat(range.drawsProduction()).isTrue();
            assertThat(range.statement()).isEmpty();
        }

        @Test
        void nothingAtAllIsNotRenderable() {
            assertThat(CapacityRange.empty().isRenderable()).isFalse();
            assertThat(CapacityRange.from(null, null).isRenderable()).isFalse();
        }

        @Test
        @DisplayName("no service is known only by a service with no observation and no traffic")
        void observationAbsentButProductionRecorded() {
            CapacityRange range = CapacityRange.from(null, productionPeaking(840));

            assertThat(range.isRenderable()).isTrue();
            assertThat(range.drawsProduction()).isTrue();
        }

        @Test
        @DisplayName("every mark lands on the scale, and the largest lands at its end")
        void positionsAreNormalisedAndOrdered() {
            CapacityRange range = CapacityRange.from(
                    observation(RequestsPerSecond.of(390), BoundaryStatus.ESTABLISHED,
                            failingAt(500)), productionPeaking(182));

            assertThat(range.markers())
                    .extracting(marker -> marker.level().asDouble())
                    .containsExactly(182d, 390d, 500d);

            assertThat(range.markers())
                    .allSatisfy(marker -> assertThat(range.position(marker.level()))
                            .isBetween(0d, 1d));

            assertThat(range.position(range.scaleTo())).isEqualTo(1.0d);
            assertThat(range.unit()).isEqualTo("requests/sec");
        }

        /**
         * Labels are the domain's, passed through rather than written here. A figure and its name
         * drifting apart is how "tested capacity" quietly becomes "capacity" on a slide.
         */
        @Test
        void labelsComeFromTheDomain() {
            CapacityObservation established = observation(
                    RequestsPerSecond.of(390), BoundaryStatus.ESTABLISHED, failingAt(500));

            CapacityRange range = CapacityRange.from(established, productionPeaking(182));

            assertThat(range.marker(MarkerKind.TESTED_CAPACITY).get().label())
                    .isEqualTo(established.label());
            assertThat(range.marker(MarkerKind.FIRST_FAILING).get().label())
                    .isEqualTo(CapacityObservation.FAILING_EDGE_LABEL);
            assertThat(range.marker(MarkerKind.PRODUCTION).get().label())
                    .isEqualTo(CapacityRange.PRODUCTION_LABEL);
        }
    }
}
