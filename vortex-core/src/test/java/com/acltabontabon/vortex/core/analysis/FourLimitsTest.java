package com.acltabontabon.vortex.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.metrics.Aggregation;
import com.acltabontabon.vortex.core.metrics.MetricObservation;
import com.acltabontabon.vortex.core.metrics.MetricSource;
import com.acltabontabon.vortex.core.metrics.MetricUnit;
import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.core.resource.ResourceKind;
import com.acltabontabon.vortex.core.resource.ResourceLimit;
import com.acltabontabon.vortex.core.resource.ResourceScope;
import com.acltabontabon.vortex.core.resource.ResourceSignal;
import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.validity.RunQualityAssessment;
import com.acltabontabon.vortex.core.validity.ValidityEffect;
import com.acltabontabon.vortex.core.validity.ValidityFinding;
import com.acltabontabon.vortex.core.validity.ValidityReason;
import com.acltabontabon.vortex.core.workload.StageWindowBasis;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Four limits, reported separately, because they routinely disagree.
 *
 * <p>The distinction they preserve is what an engineer does next. A service that breaches a p95
 * objective at 300 requests/sec while its throughput ceiling is 900 and its CPU never passes 40% is
 * <em>slow</em>; a service whose throughput flattens at 400 is <em>full</em>. One number cannot say
 * both, and collapsing them sends somebody to tune code when they needed instances.
 */
class FourLimitsTest {

    private static final Instant START = Instant.parse("2026-08-21T10:00:00Z");

    private final ThroughputCeilingDetector ceilings = new ThroughputCeilingDetector();
    private final ResourceLimitDetector resources = new ResourceLimitDetector();

    private static StageObservation stage(double offered, double achieved, long p95Millis,
            List<ResourceSignal> signals) {
        return new StageObservation(RequestsPerSecond.of(offered), RequestsPerSecond.of(achieved),
                Duration.ofMillis(p95Millis), ErrorRate.ZERO, 12, List.of(), List.of(),
                StageWindowBasis.OBSERVED, signals, 5_000);
    }

    private static ResourceSignal cpu(double percent, ResourceScope scope) {
        return new ResourceSignal(
                MetricObservation.of("metric:system.cpu.utilization", "CPU utilisation",
                        MetricSource.PROMETHEUS, MetricUnit.PERCENT, Aggregation.MAX, percent,
                        new TimeWindow(START, START.plusSeconds(60))),
                ResourceKind.CPU, scope, ResourceLimit.inherentToPercentage());
    }

    @Nested
    @DisplayName("the throughput ceiling")
    class Ceiling {

        @Test
        @DisplayName("offered up, achieved flat, latency up — a queue forming")
        void theShapeIsDetected() {
            var stages = List.of(
                    stage(100, 100, 100, List.of()),
                    stage(200, 200, 110, List.of()),
                    stage(400, 205, 300, List.of()),
                    stage(800, 208, 900, List.of()));

            var ceiling = ceilings.detect(stages, RunQualityAssessment.valid());

            assertThat(ceiling.status()).isEqualTo(ThroughputCeiling.Status.OBSERVED);
            assertThat(ceiling.levelIfPresent())
                    .hasValueSatisfying(level -> assertThat(level.asDouble()).isEqualTo(200));
        }

        @Test
        @DisplayName("a healthy plateau is not a ceiling — throughput flat and latency flat")
        void aHealthyPlateauIsNotACeiling() {
            // A service that stops taking more work and answers just as quickly usually is not
            // saturated; something upstream stopped asking.
            var stages = List.of(
                    stage(100, 100, 100, List.of()),
                    stage(200, 200, 100, List.of()),
                    stage(400, 205, 102, List.of()));

            assertThat(ceilings.detect(stages, RunQualityAssessment.valid()).status())
                    .isEqualTo(ThroughputCeiling.Status.NOT_OBSERVED);
        }

        @Test
        @DisplayName("a service that keeps absorbing load reaches no ceiling, and says so")
        void aResponsiveServiceReachesNoCeiling() {
            var stages = List.of(
                    stage(100, 99, 100, List.of()),
                    stage(200, 198, 110, List.of()),
                    stage(400, 395, 130, List.of()));

            var ceiling = ceilings.detect(stages, RunQualityAssessment.valid());

            assertThat(ceiling.status()).isEqualTo(ThroughputCeiling.Status.NOT_OBSERVED);
            assertThat(ceiling.describe()).contains("kept pace");
        }

        @Test
        @DisplayName("the same shape on a constrained generator is not a finding about the service")
        void aConstrainedGeneratorIsNotAServiceCeiling() {
            // The guard that makes this detector honest. A saturated generator produces exactly the
            // shape above, and reporting it as a service ceiling is the substitution the whole phase
            // exists to prevent, arriving through a different door.
            var stages = List.of(
                    stage(100, 100, 100, List.of()),
                    stage(200, 200, 110, List.of()),
                    stage(400, 205, 300, List.of()),
                    stage(800, 208, 900, List.of()));

            var constrained = RunQualityAssessment.of(List.of(new ValidityFinding(
                    ValidityReason.OFFERED_LOAD_NOT_GENERATED, ValidityEffect.WITHHOLDS_CAPACITY,
                    "fell behind at 400 requests/sec", List.of(), RequestsPerSecond.of(400))));

            var ceiling = ceilings.detect(stages, constrained);

            assertThat(ceiling.status()).isEqualTo(ThroughputCeiling.Status.GENERATOR_BOUND);
            assertThat(ceiling.isQuotable()).isFalse();
            assertThat(ceiling.levelIfPresent()).isEmpty();
            assertThat(ceiling.describe()).contains("says nothing about the service");
        }

        @Test
        @DisplayName("two levels cannot establish a derivative, and the refusal says why")
        void tooFewStagesIsNotEvaluated() {
            var ceiling = ceilings.detect(
                    List.of(stage(100, 100, 100, List.of()), stage(200, 120, 400, List.of())),
                    RunQualityAssessment.valid());

            assertThat(ceiling.status()).isEqualTo(ThroughputCeiling.Status.NOT_EVALUATED);
            assertThat(ceiling.describe()).contains("at least 3 levels");
        }
    }

    @Nested
    @DisplayName("the resource limit")
    class TheResourceLimit {

        @Test
        @DisplayName("names the resource that reached its declared limit, and where")
        void namesTheResourceAndTheLevel() {
            var stages = List.of(
                    stage(100, 100, 100, List.of(cpu(40, ResourceScope.SYSTEM_UNDER_TEST))),
                    stage(200, 200, 110, List.of(cpu(100, ResourceScope.SYSTEM_UNDER_TEST))));

            var found = resources.detect(stages);

            assertThat(found.wasReached()).isTrue();
            assertThat(found.kind()).isEqualTo(ResourceKind.CPU);
            assertThat(found.levelIfPresent())
                    .hasValueSatisfying(level -> assertThat(level.asDouble()).isEqualTo(200));
        }

        @Test
        @DisplayName("with nothing classified it says nobody looked, not that nothing ran out")
        void nothingClassifiedIsNotNothingReached() {
            var found = resources.detect(List.of(stage(100, 100, 100, List.of())));

            assertThat(found.status())
                    .isEqualTo(ResourceLimitFinding.Status.NO_TYPED_RESOURCE_TELEMETRY);
            assertThat(found.wasReached()).isFalse();
        }

        @Test
        @DisplayName("with CPU alone and nothing near a limit it names what it saw")
        void itNamesWhatWasObserved() {
            // "No resource limit was identified" on its own tells an engineer nothing. Naming what
            // was observed doubles as instructions for the next run.
            var found = resources.detect(List.of(
                    stage(100, 100, 100, List.of(cpu(38, ResourceScope.SYSTEM_UNDER_TEST)))));

            assertThat(found.status())
                    .isEqualTo(ResourceLimitFinding.Status.NONE_REACHED_ITS_LIMIT);
            assertThat(found.describe()).contains("CPU utilisation").contains("neither reached");
        }

        @Test
        @DisplayName("the load generator's own CPU at its limit is never the service's resource limit")
        void theGeneratorIsNeverTheServicesResourceLimit() {
            var found = resources.detect(List.of(
                    stage(100, 100, 100, List.of(cpu(100, ResourceScope.LOAD_GENERATOR)))));

            assertThat(found.wasReached()).isFalse();
            assertThat(found.status())
                    .isEqualTo(ResourceLimitFinding.Status.NO_TYPED_RESOURCE_TELEMETRY);
        }

        @Test
        @DisplayName("a classified resource with no published limit is not one that stayed clear of it")
        void noPublishedLimitIsItsOwnAnswer() {
            var unbounded = ResourceSignal.unbounded(
                    MetricObservation.of("metric:executor.queued", "Tasks queued",
                            MetricSource.PROMETHEUS, MetricUnit.COUNT, Aggregation.MAX, 4_000,
                            new TimeWindow(START, START.plusSeconds(60))),
                    ResourceKind.QUEUE, ResourceScope.SYSTEM_UNDER_TEST);

            var found = resources.detect(List.of(stage(100, 100, 100, List.of(unbounded))));

            assertThat(found.status()).isEqualTo(ResourceLimitFinding.Status.NO_LIMITS_PUBLISHED);
        }
    }

    @Nested
    @DisplayName("four findings, not one")
    class Separately {

        @Test
        @DisplayName("a slow service and a full service are different findings at different levels")
        void limitsAreReportedSeparately() {
            // Objectives breached at 300 while throughput still responds to 800 and CPU never
            // passes 40%. Collapsing these into "the breakpoint" throws away the distinction that
            // decides whether to tune the code or add instances.
            var stages = List.of(
                    stage(100, 100, 100, List.of(cpu(20, ResourceScope.SYSTEM_UNDER_TEST))),
                    stage(300, 298, 400, List.of(cpu(30, ResourceScope.SYSTEM_UNDER_TEST))),
                    stage(800, 790, 500, List.of(cpu(38, ResourceScope.SYSTEM_UNDER_TEST))));

            var ceiling = ceilings.detect(stages, RunQualityAssessment.valid());
            var resourceLimit = resources.detect(stages);

            assertThat(ceiling.isQuotable()).isFalse();
            assertThat(resourceLimit.wasReached()).isFalse();
            // And the service was demonstrably still absorbing load, which is a real conclusion.
            assertThat(ceiling.status()).isEqualTo(ThroughputCeiling.Status.NOT_OBSERVED);
        }

        @Test
        @DisplayName("a run that established none of the four says so plainly")
        void noneEstablishedIsAnAnswer() {
            var findings = new LimitFindings(null,
                    ThroughputCeiling.notObserved(3),
                    ResourceLimitFinding.notObserved(
                            ResourceLimitFinding.Status.NO_TYPED_RESOURCE_TELEMETRY, List.of()),
                    null, List.of());

            assertThat(findings.noneEstablished()).isTrue();
            assertThat(findings.describeFirst()).contains("No limit was established");
        }

        @Test
        @DisplayName("two limits reached at the same level are both named")
        void tiesAreBothNamed() {
            var findings = new LimitFindings(null, null, null, null, List.of(
                    new LimitFindings.FirstLimitingSignal(
                            LimitFindings.LimitKind.THROUGHPUT_CEILING, RequestsPerSecond.of(400),
                            "", null),
                    new LimitFindings.FirstLimitingSignal(
                            LimitFindings.LimitKind.RESOURCE_LIMIT, RequestsPerSecond.of(400),
                            "", ResourceKind.CPU)));

            // Reporting one would assert an ordering the run did not establish.
            assertThat(findings.describeFirst())
                    .contains("throughput ceiling")
                    .contains("resource limit")
                    .contains("CPU");
        }
    }
}
