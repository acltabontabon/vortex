package dev.vortex.core.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.environment.DependencyMode;
import dev.vortex.core.environment.EnvironmentType;
import dev.vortex.core.environment.TargetUrl;
import dev.vortex.core.environment.TestClassification;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.intent.TestIntent;
import dev.vortex.core.workload.TestType;
import dev.vortex.core.shared.Percentile;
import dev.vortex.core.threshold.LatencyThreshold;
import dev.vortex.core.threshold.ThresholdSet;
import dev.vortex.core.workload.ConstantArrivalRateShape;
import dev.vortex.core.workload.ConstantConcurrencyShape;
import dev.vortex.core.workload.RampingArrivalRateShape;
import dev.vortex.core.workload.Stage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The contract that decides whether two runs may be compared.
 *
 * <p>Two things are being guarded here, and the second matters more than the first. The first is
 * that the right conditions are in the identity. The second is that the hash and the explanation
 * are derived from the same list — because the previous design had a content fingerprint and a
 * field-by-field equivalence check maintained separately, and they drifted apart immediately.
 */
class ExperimentIdentityTest {

    /** Rebuilds a plan varying only the conditions a test wants to change. */
    private static EffectiveTestPlan rebuild(EffectiveTestPlan p, TestType testType,
            String workloadName, TestIntent intent, String serviceVersion, String projectName,
            ThresholdSet thresholds, TargetUrl configuredTarget, EnvironmentType environmentType,
            DependencyMode dependencyMode, TestClassification classification,
            Map<String, String> k6Options, List<Stage> stages) {

        return new EffectiveTestPlan(p.id(), p.projectId(), projectName, serviceVersion, intent,
                workloadName, p.workloadDescription(), testType, p.workloadModel(), p.peakLevel(),
                stages, p.operations(), p.workloadSource(), thresholds, p.environmentName(),
                environmentType, configuredTarget, p.effectiveTarget(), p.targetRewriteReason(),
                dependencyMode, classification, p.headers(), k6Options, p.runner(),
                p.scriptSource(), p.safetyDecisions(), null)
                .withComputedFingerprint();
    }

    private static EffectiveTestPlan same(EffectiveTestPlan p) {
        return rebuild(p, p.testType(), p.workloadName(), p.intent(), p.serviceVersion(),
                p.projectName(), p.thresholds(), p.configuredTarget(), p.environmentType(),
                p.dependencyMode(), p.classification(), p.k6Options(), p.stages());
    }

    /**
     * The property that makes a second, drifting mechanism impossible to reintroduce.
     *
     * <p>Asserted on every case below rather than once in isolation: if a future change adds a
     * dimension to the hash and forgets to describe it, or describes one it does not hash, one of
     * these will fail.
     */
    private static void assertConsistent(EffectiveTestPlan a, EffectiveTestPlan b) {
        boolean compatible = ExperimentIdentity.compare(a, b).compatible();
        boolean sameHash = ExperimentIdentity.fingerprintOf(a)
                .equals(ExperimentIdentity.fingerprintOf(b));
        assertThat(compatible)
                .as("compatibility and fingerprint equality must agree")
                .isEqualTo(sameHash);
    }

    private static void assertIncompatible(EffectiveTestPlan a, EffectiveTestPlan b,
            String expectedPhrase) {

        assertConsistent(a, b);
        var compatibility = ExperimentIdentity.compare(a, b);
        assertThat(compatibility.compatible()).isFalse();
        assertThat(compatibility.differences())
                .as("a difference must be explained, not merely detected")
                .anyMatch(difference -> difference.contains(expectedPhrase));
    }

    private static void assertCompatible(EffectiveTestPlan a, EffectiveTestPlan b) {
        assertConsistent(a, b);
        assertThat(ExperimentIdentity.compare(a, b).compatible()).isTrue();
        assertThat(ExperimentIdentity.compare(a, b).differences()).isEmpty();
    }

    @Nested
    @DisplayName("what identity deliberately ignores")
    class NotConditions {

        @Test
        @DisplayName("the release under test — this is the thing comparison exists to vary")
        void serviceVersionDoesNotAffectIdentity() {
            var baseline = Fixtures.plan().withServiceVersion("abc123").withComputedFingerprint();
            var candidate = Fixtures.plan().withServiceVersion("def456").withComputedFingerprint();

            assertCompatible(baseline, candidate);
        }

        @Test
        @DisplayName("an absent release is still the same experiment as a named one")
        void anAbsentVersionIsNotADifference() {
            var unnamed = Fixtures.plan().withServiceVersion("").withComputedFingerprint();
            var named = Fixtures.plan().withServiceVersion("abc123").withComputedFingerprint();

            assertCompatible(unnamed, named);
        }

        @Test
        @DisplayName("the workload name is a label, so renaming does not sever a comparison history")
        void workloadNameDoesNotAffectIdentity() {
            var plan = Fixtures.plan();
            var renamed = rebuild(plan, plan.testType(), "production-peak", plan.intent(),
                    plan.serviceVersion(), plan.projectName(), plan.thresholds(),
                    plan.configuredTarget(), plan.environmentType(), plan.dependencyMode(),
                    plan.classification(), plan.k6Options(), plan.stages());

            assertCompatible(plan, renamed);
        }

        @Test
        @DisplayName("the stated intent is a question, not a condition")
        void intentDoesNotAffectIdentity() {
            var plan = Fixtures.plan();
            var reworded = rebuild(plan, plan.testType(), plan.workloadName(),
                    new TestIntent(plan.testType(), "Can it survive month-end?"),
                    plan.serviceVersion(), plan.projectName(), plan.thresholds(),
                    plan.configuredTarget(), plan.environmentType(), plan.dependencyMode(),
                    plan.classification(), plan.k6Options(), plan.stages());

            assertCompatible(plan, reworded);
        }

        @Test
        @DisplayName("the k6 scenario key describes how measurements are emitted")
        void scenarioKeysDoNotAffectIdentity() {
            var plan = Fixtures.plan();
            var rekeyed = new EffectiveTestPlan(plan.id(), plan.projectId(), plan.projectName(),
                    plan.serviceVersion(), plan.intent(), plan.workloadName(),
                    plan.workloadDescription(), plan.testType(), plan.workloadModel(),
                    plan.peakLevel(), plan.stages(),
                    plan.operations().stream()
                            .map(operation -> new PlannedOperation(operation.operationId(),
                                    operation.name(), operation.k6ScenarioKey() + "_2",
                                    operation.method(), operation.pathTemplate(),
                                    operation.requestData(), operation.provenance(),
                                    operation.expect(), operation.share(), operation.arrivalRate()))
                            .toList(),
                    plan.workloadSource(), plan.thresholds(), plan.environmentName(),
                    plan.environmentType(), plan.configuredTarget(), plan.effectiveTarget(),
                    plan.targetRewriteReason(), plan.dependencyMode(), plan.classification(),
                    plan.headers(), plan.k6Options(), plan.runner(), plan.scriptSource(),
                    plan.safetyDecisions(), null).withComputedFingerprint();

            assertCompatible(plan, rekeyed);
        }

        @Test
        @DisplayName("identifiers do not make two runs of one experiment into two experiments")
        void identifiersDoNotAffectIdentity() {
            assertCompatible(Fixtures.plan(), same(Fixtures.plan()));
        }
    }

    @Nested
    @DisplayName("what identity is made of")
    class Conditions {

        @Test
        @DisplayName("the same number under a different workload model is a different quantity")
        void workloadModel() {
            assertIncompatible(
                    Fixtures.plan(TestType.AVERAGE_LOAD,
                            ConstantArrivalRateShape.of(50, Duration.ofMinutes(10))),
                    Fixtures.plan(TestType.AVERAGE_LOAD,
                            ConstantConcurrencyShape.of(50, Duration.ofMinutes(10))),
                    "workload model changed");
        }

        @Test
        void offeredLoad() {
            assertIncompatible(
                    Fixtures.plan(TestType.AVERAGE_LOAD,
                            ConstantArrivalRateShape.of(100, Duration.ofMinutes(10))),
                    Fixtures.plan(TestType.AVERAGE_LOAD,
                            ConstantArrivalRateShape.of(150, Duration.ofMinutes(10))),
                    "offered load changed");
        }

        @Test
        @DisplayName("a ramp to 100/sec is not a run held at 100/sec, even for the same total time")
        void stageShape() {
            var steady = Fixtures.plan(TestType.AVERAGE_LOAD,
                    ConstantArrivalRateShape.of(100, Duration.ofMinutes(20)));
            var ramped = Fixtures.plan(TestType.AVERAGE_LOAD,
                    new RampingArrivalRateShape(dev.vortex.core.shared.RequestsPerSecond.of(50),
                            List.of(Stage.ofRate(50, Duration.ofMinutes(10)),
                                    Stage.ofRate(100, Duration.ofMinutes(10)))));

            // Same peak, same total duration, same operations — and a different experiment: the
            // ramp arrives at 100/sec with an already-loaded, warmed-up system.
            assertThat(steady.peakLevel().asDouble()).isEqualTo(ramped.peakLevel().asDouble());
            assertThat(steady.totalDuration()).isEqualTo(ramped.totalDuration());
            assertIncompatible(steady, ramped, "workload shape changed");
        }

        @Test
        @DisplayName("the same environment name pointed somewhere else is somewhere else")
        void target() {
            var plan = Fixtures.plan();
            var moved = rebuild(plan, plan.testType(), plan.workloadName(), plan.intent(),
                    plan.serviceVersion(), plan.projectName(), plan.thresholds(),
                    TargetUrl.of("http://localhost:9090"), plan.environmentType(),
                    plan.dependencyMode(), plan.classification(), plan.k6Options(), plan.stages());

            assertIncompatible(plan, moved, "target changed");
        }

        @Test
        void objectives() {
            var plan = Fixtures.plan();
            var stricter = rebuild(plan, plan.testType(), plan.workloadName(), plan.intent(),
                    plan.serviceVersion(), plan.projectName(),
                    ThresholdSet.of(LatencyThreshold.of(Percentile.P95, Duration.ofMillis(200))),
                    plan.configuredTarget(), plan.environmentType(), plan.dependencyMode(),
                    plan.classification(), plan.k6Options(), plan.stages());

            assertIncompatible(plan, stricter, "objectives being measured against changed");
        }

        @Test
        @DisplayName("a reordered objective list is the same set of objectives")
        void objectiveOrderIsNotAcondition() {
            var plan = Fixtures.plan();
            var reordered = rebuild(plan, plan.testType(), plan.workloadName(), plan.intent(),
                    plan.serviceVersion(), plan.projectName(),
                    ThresholdSet.of(
                            dev.vortex.core.threshold.ErrorRateThreshold.ofPercent(1),
                            LatencyThreshold.of(Percentile.P99, Duration.ofMillis(1000)),
                            LatencyThreshold.of(Percentile.P95, Duration.ofMillis(500))),
                    plan.configuredTarget(), plan.environmentType(), plan.dependencyMode(),
                    plan.classification(), plan.k6Options(), plan.stages());

            assertCompatible(plan, reordered);
        }

        @Test
        void operationMix() {
            assertIncompatible(
                    Fixtures.plan(TestType.AVERAGE_LOAD,
                            ConstantArrivalRateShape.of(100, Duration.ofMinutes(10)),
                            Fixtures.operationMix()),
                    Fixtures.plan(TestType.AVERAGE_LOAD,
                            ConstantArrivalRateShape.of(100, Duration.ofMinutes(10)),
                            Fixtures.fourWayMix()),
                    "set of operations changed");
        }

        @Test
        void testType() {
            assertIncompatible(
                    Fixtures.plan(TestType.AVERAGE_LOAD,
                            ConstantArrivalRateShape.of(100, Duration.ofMinutes(10))),
                    Fixtures.plan(TestType.STRESS,
                            ConstantArrivalRateShape.of(100, Duration.ofMinutes(10))),
                    "test type changed");
        }

        @Test
        @DisplayName("changing the total says so once, not also as an operations change")
        void aChangedTotalIsReportedOnce() {
            var slower = Fixtures.plan(TestType.AVERAGE_LOAD,
                    ConstantArrivalRateShape.of(100, Duration.ofMinutes(10)),
                    Fixtures.operationMix());
            var faster = Fixtures.plan(TestType.AVERAGE_LOAD,
                    ConstantArrivalRateShape.of(150, Duration.ofMinutes(10)),
                    Fixtures.operationMix());

            assertConsistent(slower, faster);
            assertThat(ExperimentIdentity.compare(slower, faster).differences())
                    .as("the same operations at a different total is a level change, nothing more")
                    .containsExactly(
                            "offered load changed from 100 requests/sec to 150 requests/sec");
        }

        @Test
        @DisplayName("an engine override changes what the load generator actually does")
        void k6Options() {
            var plan = Fixtures.plan();
            var overridden = rebuild(plan, plan.testType(), plan.workloadName(), plan.intent(),
                    plan.serviceVersion(), plan.projectName(), plan.thresholds(),
                    plan.configuredTarget(), plan.environmentType(), plan.dependencyMode(),
                    plan.classification(), Map.of("gracefulStop", "60s"), plan.stages());

            assertIncompatible(plan, overridden, "raw engine options changed");
        }

        @Test
        @DisplayName("dependency mode decides what a result may claim")
        void dependencyMode() {
            var plan = Fixtures.plan();
            var integrated = rebuild(plan, plan.testType(), plan.workloadName(), plan.intent(),
                    plan.serviceVersion(), plan.projectName(), plan.thresholds(),
                    plan.configuredTarget(), plan.environmentType(), DependencyMode.REAL,
                    TestClassification.INTEGRATED, plan.k6Options(), plan.stages());

            assertIncompatible(plan, integrated, "dependency mode changed");
        }
    }

    @Nested
    @DisplayName("the canonical form")
    class CanonicalForm {

        @Test
        @DisplayName("carries a schema tag, so a hash from an older contract is identifiably so")
        void isVersioned() {
            assertThat(ExperimentIdentity.canonicalForm(Fixtures.plan()))
                    .containsEntry("schema", ExperimentIdentity.SCHEMA);
        }

        @Test
        void holdsNoSecretValues() {
            var plan = new EffectiveTestPlan(Fixtures.plan().id(), Fixtures.plan().projectId(),
                    "checkout", "abc", Fixtures.plan().intent(), "peak", "",
                    Fixtures.plan().testType(), Fixtures.plan().workloadModel(),
                    Fixtures.plan().peakLevel(), Fixtures.plan().stages(),
                    Fixtures.plan().operations(), Fixtures.plan().workloadSource(),
                    Fixtures.plan().thresholds(), "performance", EnvironmentType.PERFORMANCE,
                    TargetUrl.of("https://checkout.perf.example.com"),
                    TargetUrl.of("https://checkout.perf.example.com"), "", DependencyMode.REAL,
                    TestClassification.INTEGRATED,
                    Map.of("Authorization", "${VORTEX_AUTH_TOKEN}"), Map.of(),
                    Fixtures.plan().runner(), Fixtures.plan().scriptSource(), List.of(), null);

            String canonical = CanonicalJson.render(ExperimentIdentity.canonicalForm(plan));

            // The reference participates; a resolved credential never exists here to leak.
            assertThat(canonical).contains("${VORTEX_AUTH_TOKEN}");
            assertThat(canonical).doesNotContain("Bearer").doesNotContain("eyJ");
        }

        @Test
        @DisplayName("a header reference is a condition, so changing which secret is sent matters")
        void headerReferencesAreConditions() {
            var plan = Fixtures.plan();
            var different = new EffectiveTestPlan(plan.id(), plan.projectId(), plan.projectName(),
                    plan.serviceVersion(), plan.intent(), plan.workloadName(),
                    plan.workloadDescription(), plan.testType(), plan.workloadModel(),
                    plan.peakLevel(), plan.stages(), plan.operations(), plan.workloadSource(),
                    plan.thresholds(), plan.environmentName(), plan.environmentType(),
                    plan.configuredTarget(), plan.effectiveTarget(), plan.targetRewriteReason(),
                    plan.dependencyMode(), plan.classification(),
                    Map.of("Authorization", "${OTHER_TOKEN}"), plan.k6Options(), plan.runner(),
                    plan.scriptSource(), plan.safetyDecisions(), null).withComputedFingerprint();

            assertIncompatible(plan, different, "request headers changed");
        }
    }
}
