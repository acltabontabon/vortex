package com.acltabontabon.vortex.core.validity;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.analysis.EvidenceIds;
import com.acltabontabon.vortex.core.analysis.StageObservation;
import com.acltabontabon.vortex.core.execution.ExecutionState;
import com.acltabontabon.vortex.core.execution.FailureReason;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.metrics.Aggregation;
import com.acltabontabon.vortex.core.metrics.FailureClass;
import com.acltabontabon.vortex.core.metrics.LoadGeneration;
import com.acltabontabon.vortex.core.metrics.MeasuredResults;
import com.acltabontabon.vortex.core.metrics.MetricObservation;
import com.acltabontabon.vortex.core.metrics.MetricSource;
import com.acltabontabon.vortex.core.metrics.MetricUnit;
import com.acltabontabon.vortex.core.metrics.ReliabilityBreakdown;
import com.acltabontabon.vortex.core.metrics.ResponseClass;
import com.acltabontabon.vortex.core.metrics.SamplePoint;
import com.acltabontabon.vortex.core.metrics.MetricSeries;
import com.acltabontabon.vortex.core.metrics.StageTelemetry;
import com.acltabontabon.vortex.core.metrics.TelemetryAvailability;
import com.acltabontabon.vortex.core.metrics.TelemetryGap;
import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.resource.ResourceKind;
import com.acltabontabon.vortex.core.resource.ResourceLimit;
import com.acltabontabon.vortex.core.resource.ResourceScope;
import com.acltabontabon.vortex.core.resource.ResourceSignal;
import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.workload.StageWindowBasis;
import com.acltabontabon.vortex.core.workload.TestType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Whether the experiment was carried out as specified.
 *
 * <p>Every rule is asserted four ways, because three of them are where a validity model goes wrong.
 * It fires on the right evidence; it cites measurements that resolve; its sentence names the number
 * that was crossed, so an engineer can argue with it; and — the one that matters most — it does
 * <em>not</em> fire when the measurement it needs is missing. A rule that fires on an absence is
 * worse than no rule, because its conclusion looks identical to a real one.
 */
class RunQualityAssessorTest {

    private final RunQualityAssessor assessor = new RunQualityAssessor();

    private static final Instant START = Fixtures.NOW;

    private static EffectiveTestPlan plan() {
        return Fixtures.plan();
    }

    /** The measurements every test starts from: a healthy ten-minute average-load run. */
    private static MeasuredResults healthy() {
        MeasuredResults base = Fixtures.results(281, 0.0);
        return rebuild(base, base.window(), base.series(), reported(0),
                allSucceeded(base.requests()), List.of(), List.of(), List.of());
    }

    private static MeasuredResults rebuild(MeasuredResults base, TimeWindow window,
            MetricSeries series, LoadGeneration generation, ReliabilityBreakdown reliability,
            List<StageTelemetry> stageTelemetry, List<TelemetryGap> gaps,
            List<ResourceSignal> resources) {

        return new MeasuredResults(window, base.targetLoad(), base.achievedRate(), base.requests(),
                base.failures(), base.latency(), base.perOperation(), series, List.of(),
                stageTelemetry, gaps, generation, base.phases(), reliability, resources);
    }

    private static LoadGeneration reported(long dropped) {
        return new LoadGeneration(30_852L, dropped, 51.4);
    }

    private static ReliabilityBreakdown allSucceeded(long requests) {
        return new ReliabilityBreakdown(Map.of(ResponseClass.SUCCESS, requests), Map.of(),
                Map.of("200", requests), requests);
    }

    private static ResourceSignal generatorCpu(double ratio) {
        return new ResourceSignal(
                MetricObservation.of("metric:generator.process.cpu.utilization",
                        "Load generator process CPU", MetricSource.DERIVED, MetricUnit.RATIO,
                        Aggregation.MAX, ratio, new TimeWindow(START, START.plusSeconds(600))),
                ResourceKind.CPU, ResourceScope.LOAD_GENERATOR,
                ResourceLimit.inherentTo(MetricUnit.RATIO));
    }

    private static ResourceSignal generatorHostCpu(double ratio) {
        return new ResourceSignal(
                MetricObservation.of("metric:generator.host.cpu.utilization", "Load generator host CPU",
                        MetricSource.DERIVED, MetricUnit.RATIO, Aggregation.MAX, ratio,
                        new TimeWindow(START, START.plusSeconds(600))),
                ResourceKind.CPU, ResourceScope.LOAD_GENERATOR_HOST,
                ResourceLimit.inherentTo(MetricUnit.RATIO));
    }

    private static StageObservation stage(double offered, double achieved, long p95Millis,
            long requests, List<String> violated, List<ResourceSignal> resources) {

        return new StageObservation(RequestsPerSecond.of(offered), RequestsPerSecond.of(achieved),
                Duration.ofMillis(p95Millis), ErrorRate.ZERO, 12, violated, List.of(),
                StageWindowBasis.OBSERVED, resources, requests);
    }

    private RunQualityAssessment assess(MeasuredResults results, List<StageObservation> stages) {
        return assessor.assess(plan(), results, stages, ExecutionState.COMPLETED, null);
    }

    // ---------------------------------------------------------------- the generator

    @Nested
    @DisplayName("the load the workload asked for was never produced")
    class OfferedLoadNotGenerated {

        @Test
        @DisplayName("dropped work is direct evidence, and withholds the capacity claim")
        void droppedWorkFires() {
            var assessment = assess(
                    rebuild(healthy(), healthy().window(), healthy().series(), reported(4_812),
                            allSucceeded(30_852), List.of(), List.of(), List.of()),
                    List.of());

            assertThat(assessment.quality()).isEqualTo(RunQuality.INVALID);
            assertThat(assessment.has(ValidityReason.OFFERED_LOAD_NOT_GENERATED)).isTrue();
            assertThat(assessment.permitsAnyCapacityClaim()).isFalse();
        }

        @Test
        @DisplayName("its statement names the number, and it cites a measurement that resolves")
        void droppedWorkIsArguable() {
            var finding = assess(
                    rebuild(healthy(), healthy().window(), healthy().series(), reported(4_812),
                            allSucceeded(30_852), List.of(), List.of(), List.of()),
                    List.of())
                    .finding(ValidityReason.OFFERED_LOAD_NOT_GENERATED).orElseThrow();

            assertThat(finding.statement()).contains("4812");
            assertThat(finding.evidenceIds()).contains(EvidenceIds.ITERATIONS_DROPPED);
            // A citation that does not resolve is discarded by the reference validator, so a finding
            // citing only unresolvable ids would silently vanish from a report.
            assertThat(EvidenceIds.resolve(EvidenceIds.ITERATIONS_DROPPED))
                    .isEqualTo(EvidenceIds.ITERATIONS_DROPPED);
        }

        @Test
        @DisplayName("dropped work fires without any service telemetry, because it is direct evidence")
        void directEvidenceNeedsNothingElse() {
            var assessment = assess(
                    rebuild(healthy(), healthy().window(), healthy().series(), reported(4_812),
                            ReliabilityBreakdown.notReported(), List.of(), List.of(), List.of()),
                    List.of());

            assertThat(assessment.has(ValidityReason.OFFERED_LOAD_NOT_GENERATED)).isTrue();
        }

        @Test
        @DisplayName("a generator that kept up is not a generator nobody measured")
        void zeroDropsDoNotFire() {
            assertThat(assess(healthy(), List.of())
                    .has(ValidityReason.OFFERED_LOAD_NOT_GENERATED)).isFalse();
        }

        @Test
        @DisplayName("an engine that reported nothing about itself produces no finding either")
        void unreportedGenerationDoesNotFire() {
            var silent = rebuild(healthy(), healthy().window(), healthy().series(),
                    LoadGeneration.notReported(), allSucceeded(30_852), List.of(), List.of(),
                    List.of());

            assertThat(assess(silent, List.of())
                    .has(ValidityReason.OFFERED_LOAD_NOT_GENERATED)).isFalse();
        }
    }

    @Nested
    @DisplayName("attributing a shortfall to the generator rather than the service")
    class TheIndirectBranch {

        private final ResourceSignal quietPool = new ResourceSignal(
                MetricObservation.of("metric:pool.connections.utilization", "Pool",
                        MetricSource.PROMETHEUS, MetricUnit.PERCENT, Aggregation.MAX, 22,
                        new TimeWindow(START, START.plusSeconds(600))),
                ResourceKind.POOL, ResourceScope.SYSTEM_UNDER_TEST,
                ResourceLimit.inherentToPercentage());

        private MeasuredResults withoutDropCounts() {
            return rebuild(healthy(), healthy().window(), healthy().series(),
                    LoadGeneration.notReported(), allSucceeded(30_852), List.of(), List.of(),
                    List.of());
        }

        @Test
        @DisplayName("fires only when the service was positively established as untroubled")
        void firesOnPositiveEvidence() {
            var stages = List.of(
                    stage(100, 100, 100, 5_000, List.of(), List.of(quietPool)),
                    stage(200, 150, 100, 5_000, List.of(), List.of(quietPool)));

            var assessment = assess(withoutDropCounts(), stages);

            assertThat(assessment.has(ValidityReason.OFFERED_LOAD_NOT_GENERATED)).isTrue();
            assertThat(assessment.finding(ValidityReason.OFFERED_LOAD_NOT_GENERATED).orElseThrow()
                    .statement()).contains("no observed resource near its limit");
        }

        @Test
        @DisplayName("a shortfall with no service telemetry is not attributed to the generator")
        void missingServiceTelemetryRefusesAttribution() {
            // "The service showed no distress" cannot mean "nobody looked at the service". This is
            // the branch where absence would otherwise become evidence about whose fault it was.
            var stages = List.of(
                    stage(100, 100, 100, 5_000, List.of(), List.of()),
                    stage(200, 150, 100, 5_000, List.of(), List.of()));

            assertThat(assess(withoutDropCounts(), stages)
                    .has(ValidityReason.OFFERED_LOAD_NOT_GENERATED)).isFalse();
        }

        @Test
        @DisplayName("a shortfall alongside rising latency is ambiguous, and stays unattributed")
        void simultaneousServiceDistressRefusesAttribution() {
            var stages = List.of(
                    stage(100, 100, 100, 5_000, List.of(), List.of(quietPool)),
                    stage(200, 150, 400, 5_000, List.of(), List.of(quietPool)));

            assertThat(assess(withoutDropCounts(), stages)
                    .has(ValidityReason.OFFERED_LOAD_NOT_GENERATED)).isFalse();
        }

        @Test
        @DisplayName("a shortfall with a saturated service resource is the service's, not ours")
        void aServiceAtItsLimitRefusesAttribution() {
            var busyPool = new ResourceSignal(
                    MetricObservation.of("metric:pool.connections.utilization", "Pool",
                            MetricSource.PROMETHEUS, MetricUnit.PERCENT, Aggregation.MAX, 99,
                            new TimeWindow(START, START.plusSeconds(600))),
                    ResourceKind.POOL, ResourceScope.SYSTEM_UNDER_TEST,
                    ResourceLimit.inherentToPercentage());

            var stages = List.of(
                    stage(100, 100, 100, 5_000, List.of(), List.of(busyPool)),
                    stage(200, 150, 100, 5_000, List.of(), List.of(busyPool)));

            assertThat(assess(withoutDropCounts(), stages)
                    .has(ValidityReason.OFFERED_LOAD_NOT_GENERATED)).isFalse();
        }

        @Test
        @DisplayName("with no outcome distribution the branch cannot fire at all")
        void unclassifiedOutcomesRefuseAttribution() {
            var unclassified = rebuild(healthy(), healthy().window(), healthy().series(),
                    LoadGeneration.notReported(), ReliabilityBreakdown.notReported(), List.of(),
                    List.of(), List.of());
            var stages = List.of(
                    stage(100, 100, 100, 5_000, List.of(), List.of(quietPool)),
                    stage(200, 150, 100, 5_000, List.of(), List.of(quietPool)));

            assertThat(assess(unclassified, stages)
                    .has(ValidityReason.OFFERED_LOAD_NOT_GENERATED)).isFalse();
        }

        @Test
        @DisplayName("a single stage has no healthy lower level to be compared against")
        void oneStageRefusesAttribution() {
            var stages = List.of(stage(200, 150, 100, 5_000, List.of(), List.of(quietPool)));

            assertThat(assess(withoutDropCounts(), stages)
                    .has(ValidityReason.OFFERED_LOAD_NOT_GENERATED)).isFalse();
        }
    }

    @Nested
    @DisplayName("the machine generating the traffic reached its own limit")
    class GeneratorSaturated {

        @Test
        @DisplayName("fires on a generator-scoped signal at its declared limit")
        void firesAtTheLimit() {
            var saturated = rebuild(healthy(), healthy().window(), healthy().series(), reported(0),
                    allSucceeded(30_852), List.of(), List.of(), List.of(generatorCpu(1.0)));

            var assessment = assess(saturated, List.of());

            assertThat(assessment.has(ValidityReason.GENERATOR_SATURATED)).isTrue();
            assertThat(assessment.quality()).isEqualTo(RunQuality.INVALID);
            assertThat(assessment.finding(ValidityReason.GENERATOR_SATURATED).orElseThrow()
                    .evidenceIds()).contains("metric:generator.process.cpu.utilization");
        }

        @Test
        @DisplayName("a generator with room to spare does not fire")
        void aHealthyGeneratorDoesNotFire() {
            var comfortable = rebuild(healthy(), healthy().window(), healthy().series(), reported(0),
                    allSucceeded(30_852), List.of(), List.of(), List.of(generatorCpu(0.31)));

            assertThat(assess(comfortable, List.of()).has(ValidityReason.GENERATOR_SATURATED))
                    .isFalse();
        }

        @Test
        @DisplayName("and a generator nobody measured does not fire either — silence is not health")
        void anUnmeasuredGeneratorDoesNotFire() {
            // The single most important negative in this class. A run with no generator telemetry
            // must not be mistaken for a run whose generator was fine, and the way that mistake
            // would be made is by reading the absence of this finding as its refutation.
            assertThat(assess(healthy(), List.of()).has(ValidityReason.GENERATOR_SATURATED))
                    .isFalse();
            assertThat(healthy().observedTheLoadGenerator()).isFalse();
        }

        @Test
        @DisplayName("a host-scoped signal at its limit never fires this — only the generator's own does")
        void aHostScopedSignalDoesNotFireSaturation() {
            // The regression this whole redesign exists to fix: ordinary host memory or CPU pressure,
            // caused by anything at all sharing the machine, must not read as the generator's own
            // limit. LOAD_GENERATOR_HOST is excluded from this rule entirely — see
            // GeneratorHostUnderPressure below for what it fires instead.
            var hostSaturated = rebuild(healthy(), healthy().window(), healthy().series(), reported(0),
                    allSucceeded(30_852), List.of(), List.of(), List.of(generatorHostCpu(1.0)));

            assertThat(assess(hostSaturated, List.of()).has(ValidityReason.GENERATOR_SATURATED))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("the load generator's host was under resource pressure")
    class GeneratorHostUnderPressure {

        @Test
        @DisplayName("fires on a host-scoped signal near its limit, and qualifies rather than withholds")
        void firesAndQualifies() {
            var pressured = rebuild(healthy(), healthy().window(), healthy().series(), reported(0),
                    allSucceeded(30_852), List.of(), List.of(), List.of(generatorHostCpu(0.95)));

            var assessment = assess(pressured, List.of());

            assertThat(assessment.has(ValidityReason.GENERATOR_HOST_UNDER_PRESSURE)).isTrue();
            assertThat(assessment.finding(ValidityReason.GENERATOR_HOST_UNDER_PRESSURE).orElseThrow()
                    .effect()).isEqualTo(ValidityEffect.QUALIFIES);
            // Weaker than GENERATOR_SATURATED by design: this qualifies confidence, it does not
            // withhold the capacity claim.
            assertThat(assessment.permitsAnyCapacityClaim()).isTrue();
            assertThat(assessment.quality()).isEqualTo(RunQuality.DEGRADED);
        }

        @Test
        @DisplayName("a generator-scoped signal (not its host) never fires this")
        void aGeneratorScopedSignalDoesNotFire() {
            var busyGenerator = rebuild(healthy(), healthy().window(), healthy().series(), reported(0),
                    allSucceeded(30_852), List.of(), List.of(), List.of(generatorCpu(0.95)));

            assertThat(assess(busyGenerator, List.of())
                    .has(ValidityReason.GENERATOR_HOST_UNDER_PRESSURE)).isFalse();
        }

        @Test
        @DisplayName("a comfortable host does not fire")
        void aComfortableHostDoesNotFire() {
            var comfortable = rebuild(healthy(), healthy().window(), healthy().series(), reported(0),
                    allSucceeded(30_852), List.of(), List.of(), List.of(generatorHostCpu(0.31)));

            assertThat(assess(comfortable, List.of())
                    .has(ValidityReason.GENERATOR_HOST_UNDER_PRESSURE)).isFalse();
        }

        @Test
        @DisplayName("host-only telemetry still counts as having observed the generator")
        void hostOnlyTelemetryCountsAsObserved() {
            var hostOnly = rebuild(healthy(), healthy().window(), healthy().series(), reported(0),
                    allSucceeded(30_852), List.of(), List.of(), List.of(generatorHostCpu(0.31)));

            assertThat(hostOnly.observedTheLoadGenerator()).isTrue();
        }
    }

    // ---------------------------------------------------------------- the experiment

    @Nested
    @DisplayName("the run was too short")
    class RunTooShort {

        @Test
        @DisplayName("fires below the type's minimum, and names both durations")
        void firesAndNamesBothNumbers() {
            MeasuredResults brief = Fixtures.results(281, 0.0);
            var shortened = rebuild(brief, new TimeWindow(START, START.plus(Duration.ofMinutes(2))),
                    MetricSeries.empty(), reported(0), allSucceeded(brief.requests()), List.of(),
                    List.of(), List.of());

            var finding = assess(shortened, List.of()).finding(ValidityReason.RUN_TOO_SHORT)
                    .orElseThrow();

            // Both numbers, because a qualification an engineer cannot argue with is one they
            // cannot act on either — and the figure they need to argue is the one they need to fix.
            assertThat(finding.statement()).contains("2m").contains("5m");
            assertThat(finding.effect()).isEqualTo(ValidityEffect.QUALIFIES);
        }

        @Test
        @DisplayName("a run at or above the minimum does not fire")
        void aLongEnoughRunDoesNotFire() {
            assertThat(assess(healthy(), List.of()).has(ValidityReason.RUN_TOO_SHORT)).isFalse();
        }

        @Test
        @DisplayName("the threshold comes from the policy, not from a literal in the rule")
        void theThresholdIsPolicy() {
            assertThat(ValidityPolicy.defaults().minimumRunDuration(TestType.AVERAGE_LOAD))
                    .isEqualTo(Duration.ofMinutes(5));
            assertThat(ValidityPolicy.defaults().minimumRunDuration(TestType.SMOKE))
                    .isEqualTo(Duration.ZERO);
        }
    }

    @Nested
    @DisplayName("a stage carried too few requests to be a boundary edge")
    class InsufficientSamples {

        @Test
        @DisplayName("fires on a thin stage and names the floor it missed")
        void firesOnAThinStage() {
            var stages = List.of(
                    stage(100, 100, 100, 11, List.of(), List.of()),
                    stage(200, 200, 120, 9_000, List.of(), List.of()));

            var finding = assess(healthy(), stages).finding(ValidityReason.INSUFFICIENT_SAMPLES)
                    .orElseThrow();

            assertThat(finding.statement()).contains("100").contains("11");
            assertThat(finding.effect()).isEqualTo(ValidityEffect.QUALIFIES);
        }

        @Test
        @DisplayName("a stage whose request count was never established does not fire")
        void anUncountedStageDoesNotFire() {
            // Eleven requests and "we did not count" are different facts, and only the first is
            // evidence that a stage was too thin.
            var uncounted = List.of(new StageObservation(RequestsPerSecond.of(100),
                    RequestsPerSecond.of(100), Duration.ofMillis(100), ErrorRate.ZERO, 12,
                    List.of()));

            assertThat(assess(healthy(), uncounted).has(ValidityReason.INSUFFICIENT_SAMPLES))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("telemetry did not cover the run")
    class TelemetryIncomplete {

        @Test
        @DisplayName("a gap qualifies the run and blocks a limiting-resource statement")
        void aGapQualifies() {
            var withGap = rebuild(healthy(), healthy().window(), healthy().series(), reported(0),
                    allSucceeded(30_852), List.of(),
                    List.of(new TelemetryGap("prometheus", "metric:system.cpu.utilization",
                            TelemetryAvailability.NO_DATA, "the query matched no series")),
                    List.of());

            var assessment = assess(withGap, List.of());

            assertThat(assessment.has(ValidityReason.TELEMETRY_INCOMPLETE)).isTrue();
            assertThat(assessment.permitsLimitingResourceStatement()).isFalse();
            // Qualified, never invalid: failing a build because telemetry was incomplete would
            // train teams to stop collecting telemetry.
            assertThat(assessment.quality()).isEqualTo(RunQuality.DEGRADED);
        }

        @Test
        @DisplayName("complete telemetry leaves the limiting-resource statement available")
        void completeTelemetryDoesNotFire() {
            assertThat(assess(healthy(), List.of()).permitsLimitingResourceStatement()).isTrue();
        }
    }

    @Nested
    @DisplayName("requests that never reached the service")
    class TargetUnavailable {

        private MeasuredResults withUnreached(long timeouts, long connectionFailures, long total) {
            var reliability = new ReliabilityBreakdown(
                    Map.of(ResponseClass.SUCCESS, total - timeouts - connectionFailures,
                            ResponseClass.UNKNOWN, timeouts + connectionFailures),
                    Map.of(FailureClass.TIMEOUT, timeouts,
                            FailureClass.CONNECTION, connectionFailures),
                    Map.of(), total);

            return rebuild(healthy(), healthy().window(), healthy().series(), reported(0),
                    reliability, List.of(), List.of(), List.of());
        }

        @Test
        @DisplayName("a minority qualifies the run, and says the generator could explain it too")
        void aMinorityQualifies() {
            var assessment = assess(withUnreached(600, 600, 10_000), List.of());
            var finding = assessment.finding(ValidityReason.TARGET_UNAVAILABLE_DURING_RUN)
                    .orElseThrow();

            assertThat(assessment.quality()).isEqualTo(RunQuality.DEGRADED);
            assertThat(finding.statement()).contains("5%").contains("saturated generator");
        }

        @Test
        @DisplayName("a majority means the run did not measure a service serving traffic")
        void aMajorityInvalidates() {
            var assessment = assess(withUnreached(4_000, 3_000, 10_000), List.of());

            assertThat(assessment.quality()).isEqualTo(RunQuality.INVALID);
            assertThat(assessment.permitsAnyCapacityClaim()).isFalse();
        }

        @Test
        @DisplayName("with no outcome classification the rule cannot fire")
        void unclassifiedOutcomesDoNotFire() {
            var unclassified = rebuild(healthy(), healthy().window(), healthy().series(),
                    reported(0), ReliabilityBreakdown.notReported(), List.of(), List.of(),
                    List.of());

            assertThat(assess(unclassified, List.of())
                    .has(ValidityReason.TARGET_UNAVAILABLE_DURING_RUN)).isFalse();
        }
    }

    @Nested
    @DisplayName("the run was interrupted")
    class Interrupted {

        @Test
        @DisplayName("a cancelled run withholds capacity and keeps its measurements")
        void cancellationWithholdsCapacity() {
            var assessment = assessor.assess(plan(), healthy(), List.of(),
                    ExecutionState.CANCELLED, null);

            assertThat(assessment.has(ValidityReason.EXECUTION_INTERRUPTED)).isTrue();
            assertThat(assessment.permitsAnyCapacityClaim()).isFalse();
            assertThat(assessment.finding(ValidityReason.EXECUTION_INTERRUPTED).orElseThrow()
                    .statement()).contains("kept and reported");
        }

        @Test
        @DisplayName("a run interrupted by a restart is graded the same way")
        void anInterruptedRunIsGradedToo() {
            var assessment = assessor.assess(plan(), healthy(), List.of(),
                    ExecutionState.FAILED, FailureReason.INTERRUPTED);

            assertThat(assessment.has(ValidityReason.EXECUTION_INTERRUPTED)).isTrue();
        }

        @Test
        @DisplayName("a run that finished normally does not fire")
        void aCompletedRunDoesNotFire() {
            assertThat(assess(healthy(), List.of()).has(ValidityReason.EXECUTION_INTERRUPTED))
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("what this phase cannot yet assess")
    class NotYetAssessable {

        @Test
        @DisplayName("warm-up is vocabulary, not a rule, and never fires")
        void warmUpNeverFires() {
            // ADR-038 defines the code; the workload model cannot express a declared warm-up, so
            // there is nothing to measure. Inventing an input so the rule had something to read
            // would be exactly the approximation the ADR forbids.
            assertThat(ValidityReason.WARM_UP_NOT_COMPLETED.isAssessable()).isFalse();
            assertThat(assess(healthy(), List.of()).has(ValidityReason.WARM_UP_NOT_COMPLETED))
                    .isFalse();
        }

        @Test
        @DisplayName("every other code is assessable, so the implemented set cites real measurements")
        void everyOtherCodeIsAssessable() {
            assertThat(java.util.Arrays.stream(ValidityReason.values())
                    .filter(ValidityReason::isAssessable)
                    .toList())
                    .hasSize(ValidityReason.values().length - 1);
        }
    }

    // ---------------------------------------------------------------- independence

    @Nested
    @DisplayName("validity and the verdict are independent, in both directions")
    class Independence {

        @Test
        @DisplayName("a run can meet every objective and still not have measured what it claims")
        void passingAndInvalid() {
            MeasuredResults comfortable = Fixtures.results(120, 0.0);
            var invalid = rebuild(comfortable, comfortable.window(), comfortable.series(),
                    reported(4_812), allSucceeded(comfortable.requests()), List.of(), List.of(),
                    List.of());

            var assessment = assess(invalid, List.of());
            var verdict = new com.acltabontabon.vortex.core.threshold.ThresholdEvaluator()
                    .evaluate(Fixtures.thresholds(), invalid).overall();

            assertThat(verdict).isEqualTo(com.acltabontabon.vortex.core.threshold.Verdict.PASS);
            assertThat(assessment.quality()).isEqualTo(RunQuality.INVALID);
        }

        @Test
        @DisplayName("and a run can miss every objective while being perfectly valid")
        void failingAndValid() {
            // A stress test that breaks the service is the healthiest artefact in the product.
            MeasuredResults broken = Fixtures.results(4_000, 0.4);
            var valid = rebuild(broken, broken.window(), broken.series(), reported(0),
                    new ReliabilityBreakdown(
                            Map.of(ResponseClass.SERVER_ERROR, broken.failures(),
                                    ResponseClass.SUCCESS, broken.successes()),
                            Map.of(FailureClass.APPLICATION, broken.failures()),
                            Map.of(), broken.requests()),
                    List.of(), List.of(), List.of());

            var assessment = assess(valid, List.of());
            var verdict = new com.acltabontabon.vortex.core.threshold.ThresholdEvaluator()
                    .evaluate(Fixtures.thresholds(), valid).overall();

            assertThat(verdict).isEqualTo(com.acltabontabon.vortex.core.threshold.Verdict.FAIL);
            assertThat(assessment.quality()).isEqualTo(RunQuality.VALID);
        }
    }

    @Nested
    @DisplayName("what a grade withholds, and from where")
    class Withholding {

        @Test
        @DisplayName("a level-specific finding leaves the levels below it alone")
        void lowerLevelsSurvive() {
            // A generator that fell behind at 900 says nothing about what happened at 300, and
            // refusing the lower figure too would discard evidence the run genuinely produced.
            var finding = new ValidityFinding(ValidityReason.OFFERED_LOAD_NOT_GENERATED,
                    ValidityEffect.WITHHOLDS_CAPACITY, "fell behind at 900 requests/sec",
                    List.of(), RequestsPerSecond.of(900));
            var assessment = RunQualityAssessment.of(List.of(finding));

            assertThat(assessment.permitsCapacityAt(RequestsPerSecond.of(300))).isTrue();
            assertThat(assessment.permitsCapacityAt(RequestsPerSecond.of(900))).isFalse();
            assertThat(assessment.permitsCapacityAt(RequestsPerSecond.of(1_200))).isFalse();
        }

        @Test
        @DisplayName("an unassessed run withholds nothing, because it carries no findings")
        void notAssessedWithholdsNothing() {
            var unassessed = RunQualityAssessment.notAssessed();

            assertThat(unassessed.quality()).isEqualTo(RunQuality.NOT_ASSESSED);
            assertThat(unassessed.quality().isAssessed()).isFalse();
            assertThat(unassessed.permitsAnyCapacityClaim()).isTrue();
            assertThat(unassessed.qualifications()).isEmpty();
        }

        @Test
        @DisplayName("the grade is derived from the findings, never asserted alongside them")
        void theGradeIsDerived() {
            assertThat(RunQualityAssessment.of(List.of()).quality()).isEqualTo(RunQuality.VALID);
            assertThat(RunQualityAssessment.of(List.of(
                    new ValidityFinding(ValidityReason.RUN_TOO_SHORT, ValidityEffect.QUALIFIES,
                            "held for 2m; 5m required", List.of()))).quality())
                    .isEqualTo(RunQuality.DEGRADED);
            assertThat(RunQualityAssessment.of(List.of(
                    new ValidityFinding(ValidityReason.RUN_TOO_SHORT, ValidityEffect.QUALIFIES,
                            "held for 2m; 5m required", List.of()),
                    new ValidityFinding(ValidityReason.GENERATOR_SATURATED,
                            ValidityEffect.WITHHOLDS_CAPACITY, "CPU at its limit", List.of())))
                    .quality())
                    .isEqualTo(RunQuality.INVALID);
        }

        @Test
        @DisplayName("an invalid run keeps every measurement it took")
        void nothingIsDeleted() {
            var invalid = rebuild(healthy(), healthy().window(),
                    new MetricSeries(Duration.ofSeconds(5), List.of(new SamplePoint(START,
                            Duration.ofSeconds(5), RequestsPerSecond.of(20), ErrorRate.ZERO,
                            Duration.ofMillis(200), RequestsPerSecond.of(20), 4, 12L))),
                    reported(4_812), allSucceeded(30_852), List.of(), List.of(), List.of());

            var assessment = assess(invalid, List.of());

            // Invalidity changes what Vortex is willing to state, never what it stores.
            assertThat(assessment.isInvalid()).isTrue();
            assertThat(invalid.series().points()).isNotEmpty();
            assertThat(invalid.latency().isEmpty()).isFalse();
            assertThat(invalid.requests()).isPositive();
        }
    }
}
