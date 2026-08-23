package dev.vortex.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.shared.ErrorRate;
import dev.vortex.core.shared.RequestsPerSecond;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class BreakpointDetectionTest {

    private final BreakpointDetector breakpointDetector = new BreakpointDetector();
    private final SystemSaturationDetector saturationDetector = new SystemSaturationDetector();

    private static StageObservation stage(double rate, long p95Millis, double errorFraction,
            double achievedRate, boolean compliant) {
        return new StageObservation(
                RequestsPerSecond.of(rate),
                RequestsPerSecond.of(achievedRate),
                Duration.ofMillis(p95Millis),
                ErrorRate.ofFraction(errorFraction),
                60,
                compliant ? List.of() : List.of("latency.p95"));
    }

    @Nested
    @DisplayName("SLO breakpoint")
    class SloBreakpointDetection {

        @Test
        void identifiesTheFirstStageThatViolatedAnObjective() {
            var breakpoint = breakpointDetector.detectSloBreakpoint(List.of(
                    stage(50, 180, 0.001, 49.5, true),
                    stage(100, 240, 0.001, 99, true),
                    stage(150, 620, 0.002, 148, false),
                    stage(200, 1400, 0.02, 160, false)));

            assertThat(breakpoint).hasValueSatisfying(value -> {
                assertThat(value.level().asDouble()).isEqualTo(150.0);
                assertThat(value.highestCompliantLevelIfPresent())
                        .hasValueSatisfying(level -> assertThat(level.asDouble()).isEqualTo(100.0));
                assertThat(value.violatedThresholdIds()).containsExactly("latency.p95");
            });
        }

        @Test
        void reportsNoBreakpointWhenEveryStageMetItsObjectives() {
            var breakpoint = breakpointDetector.detectSloBreakpoint(List.of(
                    stage(50, 180, 0.001, 49.5, true),
                    stage(100, 240, 0.001, 99, true),
                    stage(150, 300, 0.001, 148, true)));

            assertThat(breakpoint).isEmpty();
        }

        @Test
        void cannotIdentifyABreakpointFromASingleStage() {
            assertThat(breakpointDetector.detectSloBreakpoint(List.of(stage(100, 900, 0.1, 90, false))))
                    .isEmpty();
        }

        @Test
        @DisplayName("evidence strength reflects how tightly the breakpoint is bracketed")
        void evidenceStrengthDependsOnStageCount() {
            var fewStages = breakpointDetector.detectSloBreakpoint(List.of(
                    stage(50, 180, 0.001, 49.5, true),
                    stage(200, 900, 0.001, 190, false)));

            var manyStages = breakpointDetector.detectSloBreakpoint(List.of(
                    stage(20, 120, 0.001, 20, true),
                    stage(40, 140, 0.001, 40, true),
                    stage(60, 160, 0.001, 60, true),
                    stage(80, 200, 0.001, 80, true),
                    stage(100, 260, 0.001, 100, true),
                    stage(120, 700, 0.001, 118, false)));

            assertThat(fewStages.orElseThrow().strength()).isEqualTo(EvidenceStrength.LOW);
            assertThat(manyStages.orElseThrow().strength()).isEqualTo(EvidenceStrength.HIGH);
        }

        @Test
        @DisplayName("a stage with barely any samples weakens the finding, even with many stages")
        void thinSamplingWeakensTheFinding() {
            List<StageObservation> leadIn = List.of(
                    stage(20, 120, 0.001, 20, true),
                    stage(40, 140, 0.001, 40, true),
                    stage(60, 160, 0.001, 60, true),
                    stage(80, 200, 0.001, 80, true));

            var wellSampled = breakpointDetector.detectSloBreakpoint(
                    concat(leadIn, stage(100, 900, 0.001, 98, false)));

            var barelySampled = breakpointDetector.detectSloBreakpoint(
                    concat(leadIn, new StageObservation(RequestsPerSecond.of(100),
                            RequestsPerSecond.of(98), Duration.ofMillis(900), ErrorRate.ZERO, 1,
                            List.of("latency.p95"))));

            assertThat(wellSampled.orElseThrow().strength()).isEqualTo(EvidenceStrength.HIGH);
            assertThat(barelySampled.orElseThrow().strength()).isEqualTo(EvidenceStrength.LOW);
        }

        private List<StageObservation> concat(List<StageObservation> stages, StageObservation last) {
            List<StageObservation> all = new java.util.ArrayList<>(stages);
            all.add(last);
            return all;
        }

        @Test
        void highestCompliantLevelIsTheCapacityCandidate() {
            assertThat(breakpointDetector.highestCompliantLevel(List.of(
                    stage(50, 180, 0.001, 49.5, true),
                    stage(100, 240, 0.001, 99, true),
                    stage(150, 620, 0.002, 148, false))))
                    .hasValueSatisfying(level -> assertThat(level.asDouble()).isEqualTo(100.0));
        }

        @Test
        @DisplayName("a breakpoint carries the unit of whatever the workload controlled")
        void breakpointStatesItsUnit() {
            var byRate = breakpointDetector.detectSloBreakpoint(List.of(
                    stage(50, 180, 0.001, 49.5, true),
                    stage(150, 620, 0.002, 148, false)));

            var byConcurrency = breakpointDetector.detectSloBreakpoint(List.of(
                    vuStage(20, 180, true),
                    vuStage(80, 900, false)));

            assertThat(byRate.orElseThrow().describe()).contains("150 requests/sec");
            assertThat(byConcurrency.orElseThrow().describe()).contains("80 VUs");
        }

        private StageObservation vuStage(int vus, long p95Millis, boolean compliant) {
            return new StageObservation(
                    dev.vortex.core.shared.Concurrency.of(vus),
                    RequestsPerSecond.of(vus * 4),
                    Duration.ofMillis(p95Millis),
                    ErrorRate.ofFraction(0.001),
                    60,
                    compliant ? List.of() : List.of("latency.p95"));
        }
    }

    @Nested
    @DisplayName("system saturation is reported conservatively")
    class SaturationDetection {

        @Test
        @DisplayName("a single signal is not enough to claim the system broke")
        void oneSignalIsNotEnough() {
            var saturation = saturationDetector.detect(List.of(
                    stage(50, 180, 0.001, 49.5, true),
                    stage(100, 240, 0.001, 99, true),
                    // errors climb, but the generator kept up and latency stayed sane
                    stage(150, 300, 0.20, 149, false)));

            assertThat(saturation.wasObserved()).isFalse();
            assertThat(saturation.describe()).isEqualTo("Not established by this test");
            assertThat(saturation.explanation()).contains("at least 2 independent signals");
        }

        @Test
        void reportsARangeWhenSeveralSignalsAgree() {
            var saturation = saturationDetector.detect(List.of(
                    stage(50, 180, 0.001, 49.5, true),
                    stage(100, 200, 0.001, 99, true),
                    stage(150, 1400, 0.15, 110, false),
                    stage(200, 2600, 0.30, 112, false)));

            assertThat(saturation.wasObserved()).isTrue();
            assertThat(saturation.describe()).isEqualTo("approximately 150–200 requests/sec");
            assertThat(saturation.signals()).isNotEmpty();
            assertThat(saturation.strength())
                    .isIn(EvidenceStrength.LOW, EvidenceStrength.MEDIUM);
        }

        @Test
        @DisplayName("\"not established\" is the answer when the service absorbed everything")
        void healthyRunEstablishesNothing() {
            var saturation = saturationDetector.detect(List.of(
                    stage(50, 180, 0.001, 49.5, true),
                    stage(100, 200, 0.001, 99, true),
                    stage(150, 220, 0.001, 149, true)));

            assertThat(saturation.wasObserved()).isFalse();
            assertThat(saturation.explanation()).contains("above the range tested");
        }

        @Test
        void tooFewStagesCannotEstablishSaturation() {
            var saturation = saturationDetector.detect(List.of(
                    stage(100, 3000, 0.5, 40, false),
                    stage(200, 5000, 0.8, 41, false)));

            assertThat(saturation.wasObserved()).isFalse();
            assertThat(saturation.explanation()).contains("not produce enough distinct traffic levels");
        }

        @Test
        void neverReportsAPrecisePointWhenOnlyARangeIsSupported() {
            var saturation = saturationDetector.detect(List.of(
                    stage(50, 180, 0.001, 49.5, true),
                    stage(100, 200, 0.001, 99, true),
                    stage(150, 1400, 0.15, 110, false),
                    stage(200, 2600, 0.30, 112, false)));

            assertThat(saturation.describe()).startsWith("approximately");
            assertThat(saturation.explanation()).contains("bounded range, not a precise");
        }
    }
}
