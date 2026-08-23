package dev.vortex.core.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.analysis.EvidenceStrength;
import dev.vortex.core.analysis.SloBreakpoint;
import dev.vortex.core.analysis.StageObservation;
import dev.vortex.core.analysis.SystemSaturation;
import dev.vortex.core.capacity.BoundaryStatus;
import dev.vortex.core.shared.Concurrency;
import dev.vortex.core.shared.ErrorRate;
import dev.vortex.core.shared.LoadLevel;
import dev.vortex.core.shared.RequestsPerSecond;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The axis draws what the run established, and nothing else.
 *
 * <p>Most of these are about refusal. The picture is the most persuasive thing on the result page,
 * which makes it the most dangerous place for Vortex to imply a conclusion it did not reach.
 */
@DisplayName("the load axis")
class LoadAxisTest {

    private static StageObservation stage(double rate, boolean compliant) {
        return new StageObservation(RequestsPerSecond.of(rate), RequestsPerSecond.of(rate),
                Duration.ofMillis(100), ErrorRate.ZERO, 12,
                compliant ? List.of() : List.of("latency.p95"));
    }

    @Nested
    @DisplayName("when compliance falls away once and stays away")
    class Established {

        private final List<StageObservation> stages =
                List.of(stage(100, true), stage(200, true), stage(300, false));

        @Test
        @DisplayName("draws the boundary between the last compliant level and the first that failed")
        void drawsTheBoundary() {
            LoadAxis axis = LoadAxis.from(stages, true, null, null);

            assertThat(axis.boundaryStatus()).isEqualTo(BoundaryStatus.ESTABLISHED);
            assertThat(axis.drawsBoundary()).isTrue();
            assertThat(axis.highestCompliantIfPresent()).contains(RequestsPerSecond.of(200));
            assertThat(axis.firstNonCompliantIfPresent()).contains(RequestsPerSecond.of(300));
        }

        @Test
        @DisplayName("places every level it tested")
        void placesEveryLevel() {
            LoadAxis axis = LoadAxis.from(stages, true, null, null);

            assertThat(axis.points()).hasSize(3);
            assertThat(axis.isRenderable()).isTrue();
            assertThat(axis.testedTo()).isEqualTo(RequestsPerSecond.of(300));
            assertThat(axis.position(RequestsPerSecond.of(300))).isEqualTo(1.0);
            assertThat(axis.position(RequestsPerSecond.of(150))).isEqualTo(0.5);
        }

        @Test
        @DisplayName("states every level in the unit it was controlled in")
        void statesItsUnit() {
            assertThat(LoadAxis.from(stages, true, null, null).unit()).isEqualTo("requests/sec");
        }
    }

    /*
     * 100 pass, 200 fail, 300 pass. Joining those points into a boundary would be arithmetic rather
     * than evidence — something in the run was not stable — and a picture is exactly where that
     * would be most persuasive and least true.
     */
    @Nested
    @DisplayName("when compliance came back after failing")
    class Unstable {

        private final List<StageObservation> stages =
                List.of(stage(100, true), stage(200, false), stage(300, true));

        @Test
        @DisplayName("refuses to draw a boundary")
        void drawsNoBoundary() {
            LoadAxis axis = LoadAxis.from(stages, true, null, null);

            assertThat(axis.boundaryStatus()).isEqualTo(BoundaryStatus.UNSTABLE);
            assertThat(axis.drawsBoundary()).isFalse();
            assertThat(axis.highestCompliantIfPresent()).isEmpty();
            assertThat(axis.firstNonCompliantIfPresent()).isEmpty();
        }

        @Test
        @DisplayName("still shows the stages, and says what it could not conclude")
        void stillShowsTheStages() {
            LoadAxis axis = LoadAxis.from(stages, true, null, null);

            assertThat(axis.points()).hasSize(3);
            assertThat(axis.boundaryStatement()).isEqualTo("not established: results were not monotonic");
        }
    }

    @Nested
    @DisplayName("when nothing was violated")
    class FarEdgeNotReached {

        @Test
        @DisplayName("leaves the axis open rather than implying a ceiling")
        void staysOpenEnded() {
            LoadAxis axis = LoadAxis.from(
                    List.of(stage(100, true), stage(200, true)), true, null, null);

            assertThat(axis.boundaryStatus()).isEqualTo(BoundaryStatus.FAR_EDGE_NOT_REACHED);
            assertThat(axis.isOpenEnded()).isTrue();
            assertThat(axis.firstNonCompliantIfPresent()).isEmpty();
        }
    }

    /*
     * An objective that was never checked has not been met. A stage coloured as compliant because
     * nothing contradicted it would turn "we did not look" into "it passed".
     */
    @Nested
    @DisplayName("when there were no objectives to judge against")
    class NotEvaluated {

        private final List<StageObservation> stages = List.of(stage(100, true), stage(200, true));

        @Test
        @DisplayName("no stage is marked compliant")
        void noStageIsMarkedCompliant() {
            LoadAxis axis = LoadAxis.from(stages, false, null, null);

            assertThat(axis.points()).allSatisfy(point -> {
                assertThat(point.compliance()).isEqualTo(LoadAxis.Compliance.NOT_EVALUATED);
                assertThat(point.isCompliant()).isFalse();
                assertThat(point.wasEvaluated()).isFalse();
            });
        }

        @Test
        @DisplayName("no boundary is drawn")
        void drawsNoBoundary() {
            LoadAxis axis = LoadAxis.from(stages, false, null, null);

            assertThat(axis.boundaryStatus()).isEqualTo(BoundaryStatus.NOT_EVALUATED);
            assertThat(axis.drawsBoundary()).isFalse();
            assertThat(axis.boundaryStatement())
                    .isEqualTo("not established: objectives were not evaluated");
        }
    }

    @Nested
    @DisplayName("the saturation range")
    class Saturation {

        private final List<StageObservation> stages =
                List.of(stage(100, true), stage(200, true), stage(300, false));

        @Test
        @DisplayName("is drawn as a range when it was observed in the same quantity")
        void drawsAnObservedRange() {
            SystemSaturation saturation = new SystemSaturation(
                    SystemSaturation.Status.OBSERVED, RequestsPerSecond.of(250),
                    RequestsPerSecond.of(300), List.of("a", "b"), EvidenceStrength.MEDIUM, "");

            LoadAxis axis = LoadAxis.from(stages, true, null, saturation);

            assertThat(axis.drawsSaturation()).isTrue();
            assertThat(axis.position(RequestsPerSecond.of(250))).isEqualTo(250d / 300d);
        }

        @Test
        @DisplayName("is not drawn when the run did not establish one")
        void drawsNothingWhenNotEstablished() {
            LoadAxis axis = LoadAxis.from(stages, true, null,
                    SystemSaturation.notEstablished("The service absorbed every level offered."));

            assertThat(axis.drawsSaturation()).isFalse();
            assertThat(axis.saturationIfPresent()).isPresent();
            assertThat(axis.saturationIfPresent().get().describe())
                    .isEqualTo("Not established by this test");
        }

        /*
         * Virtual users and requests per second are the same number and different facts. Placing a
         * VU-bounded range on a requests/sec axis would draw a conversion that does not exist.
         */
        @Test
        @DisplayName("is not placed when it was bounded in a different quantity from the axis")
        void refusesToMixQuantities() {
            SystemSaturation saturation = new SystemSaturation(
                    SystemSaturation.Status.OBSERVED, Concurrency.of(40), Concurrency.of(50),
                    List.of("a", "b"), EvidenceStrength.MEDIUM, "");

            LoadAxis axis = LoadAxis.from(stages, true, null, saturation);

            assertThat(axis.drawsSaturation()).isFalse();
            assertThat(axis.position(Concurrency.of(40))).isZero();
        }
    }

    @Nested
    @DisplayName("with too little to show")
    class TooLittle {

        @Test
        @DisplayName("a single level is a measurement, not a range")
        void oneStageIsNotAnAxis() {
            assertThat(LoadAxis.from(List.of(stage(100, true)), true, null, null).isRenderable())
                    .isFalse();
        }

        @Test
        @DisplayName("no stages at all renders nothing and claims nothing")
        void noStages() {
            LoadAxis axis = LoadAxis.from(List.of(), true, null, null);

            assertThat(axis.isRenderable()).isFalse();
            assertThat(axis.drawsBoundary()).isFalse();
            assertThat(axis.points()).isEmpty();
        }
    }

    @Nested
    @DisplayName("stage alignment")
    class Alignment {

        @Test
        @DisplayName("is reported when a stage's interval was computed rather than measured")
        void reportsDerivedAlignment() {
            // The ordinary case for an arrival-rate workload: k6 does not emit the configured target
            // rate, so the boundaries come from planned durations.
            LoadAxis axis = LoadAxis.from(
                    List.of(stage(100, true), stage(200, true)), true, null, null);

            assertThat(axis.hasDerivedAlignment()).isTrue();
            assertThat(axis.points().getFirst().isObserved()).isFalse();
        }
    }

    @Nested
    @DisplayName("when a breakpoint was already computed")
    class WithBreakpoint {

        @Test
        @DisplayName("the axis uses it rather than deriving a second answer")
        void prefersTheComputedBreakpoint() {
            LoadLevel first = RequestsPerSecond.of(300);
            LoadLevel highest = RequestsPerSecond.of(200);
            SloBreakpoint breakpoint = new SloBreakpoint(first, highest,
                    List.of("latency.p95"), EvidenceStrength.HIGH, 3);

            LoadAxis axis = LoadAxis.from(
                    List.of(stage(100, true), stage(200, true), stage(300, false)),
                    true, breakpoint, null);

            assertThat(axis.highestCompliantIfPresent()).contains(highest);
            assertThat(axis.firstNonCompliantIfPresent()).contains(first);
        }
    }
}
