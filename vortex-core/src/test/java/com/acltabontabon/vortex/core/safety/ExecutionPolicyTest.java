package com.acltabontabon.vortex.core.safety;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.environment.DependencyMode;
import com.acltabontabon.vortex.core.environment.EnvironmentType;
import com.acltabontabon.vortex.core.environment.SecretReferences;
import com.acltabontabon.vortex.core.environment.TargetUrl;
import com.acltabontabon.vortex.core.environment.TestClassification;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.intent.TestIntent;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.plan.RunnerKind;
import com.acltabontabon.vortex.core.plan.ScriptSource;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.shared.TestPlanId;
import com.acltabontabon.vortex.core.workload.Stage;
import com.acltabontabon.vortex.core.workload.TestType;
import com.acltabontabon.vortex.core.workload.WorkloadSource;
import com.acltabontabon.vortex.core.workload.ConstantArrivalRateShape;
import com.acltabontabon.vortex.core.workload.WorkloadModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ExecutionPolicyTest {

    private final ExecutionPolicy policy = ExecutionPolicy.withDefaults();

    private static EffectiveTestPlan planFor(EnvironmentType type, String environmentName,
            String url, double rate) {
        var base = Fixtures.plan(TestType.STRESS,
                ConstantArrivalRateShape.of(rate, Duration.ofMinutes(10)));
        return new EffectiveTestPlan(
                TestPlanId.of("p1"), ProjectId.of("checkout"), "checkout-service", "2.17.0",
                TestIntent.defaultFor(TestType.STRESS), "production-peak", "", TestType.STRESS,
                WorkloadModel.OPEN, RequestsPerSecond.of(rate),
                List.of(new Stage(RequestsPerSecond.of(rate), Duration.ofMinutes(10))),
                base.operations(), WorkloadSource.manual(), Fixtures.thresholds(),
                environmentName, type,
                TargetUrl.of(url), TargetUrl.of(url), "", DependencyMode.REAL,
                TestClassification.INTEGRATED, Map.of(), Map.of(), RunnerKind.LOCAL_BINARY,
                ScriptSource.GENERATED, List.of(), null).withComputedFingerprint();
    }

    @Nested
    @DisplayName("targets")
    class Targets {

        @Test
        void aLocalRunNeedsNoConfirmation() {
            var assessment = policy.assess(Fixtures.plan());

            assertThat(assessment.isBlocked()).isFalse();
            assertThat(assessment.requiresConfirmation()).isFalse();
        }

        @Test
        @DisplayName("a non-local target requires the environment name to be typed, not a generic yes")
        void nonLocalTargetsRequireTypedConfirmation() {
            var assessment = policy.assess(
                    planFor(EnvironmentType.SHARED_TEST, "shared-test", "https://checkout.test.example.com", 20));

            assertThat(assessment.requiresConfirmation()).isTrue();
            assertThat(assessment.requiredChallenges()).containsExactly("shared-test");
            assertThat(assessment.warnings())
                    .anySatisfy(finding -> assertThat(finding.detail())
                            .contains("Confirm by typing the environment name"));
        }

        @Test
        @DisplayName("a production-looking hostname is a hint, never a classification")
        void productionHostnamesWarnButDoNotReclassify() {
            var plan = planFor(EnvironmentType.PERFORMANCE, "performance",
                    "https://checkout.prod.example.com", 20);

            var assessment = policy.assess(plan);

            assertThat(assessment.findings())
                    .anySatisfy(finding -> {
                        assertThat(finding.policyId()).isEqualTo("target.production-hint");
                        assertThat(finding.detail()).contains("best-effort hint only");
                        assertThat(finding.detail()).contains("remains authoritative");
                    });
            assertThat(plan.environmentType()).isEqualTo(EnvironmentType.PERFORMANCE);
        }

        @Test
        void anOrdinaryHostnameRaisesNoProductionHint() {
            var assessment = policy.assess(
                    planFor(EnvironmentType.PERFORMANCE, "performance", "https://checkout.perf.example.com", 20));

            assertThat(assessment.findings())
                    .noneMatch(finding -> finding.policyId().equals("target.production-hint"));
        }

        @Test
        void anAllowlistBlocksUnlistedHosts() {
            var restricted = new ExecutionPolicy(new SafetyLimits(
                    Map.of(), Duration.ofHours(4), List.of("*.perf.example.com")));

            var assessment = restricted.assess(
                    planFor(EnvironmentType.PERFORMANCE, "performance", "https://elsewhere.example.com", 20));

            assertThat(assessment.isBlocked()).isTrue();
            assertThat(assessment.blocking().getFirst().detail()).contains("Add it under");
        }
    }

    @Nested
    @DisplayName("rate and duration limits")
    class Limits {

        @Test
        void exceedingTheEnvironmentCeilingRequiresATypedOverride() {
            var assessment = policy.assess(
                    planFor(EnvironmentType.SHARED_TEST, "shared-test", "https://checkout.test.example.com", 250));

            assertThat(assessment.findings())
                    .anySatisfy(finding -> {
                        assertThat(finding.policyId()).isEqualTo("rate.ceiling");
                        assertThat(finding.detail()).contains("Configured limit for Shared test: 100");
                        assertThat(finding.detail()).contains("Requested: 250");
                        assertThat(finding.requiresTypedConfirmation()).isTrue();
                    });
        }

        @Test
        void stayingWithinTheCeilingRaisesNothing() {
            var assessment = policy.assess(
                    planFor(EnvironmentType.SHARED_TEST, "shared-test", "https://checkout.test.example.com", 50));

            assertThat(assessment.findings())
                    .noneMatch(finding -> finding.policyId().equals("rate.ceiling"));
        }

        @Test
        void anExcessivelyLongRunIsBlockedOutright() {
            var shortLimit = new ExecutionPolicy(
                    new SafetyLimits(Map.of(), Duration.ofMinutes(5), List.of()));

            assertThat(shortLimit.assess(Fixtures.plan()).isBlocked()).isTrue();
        }
    }

    @Test
    @DisplayName("every assessment states what class of question the run can answer")
    void classificationIsAlwaysReported() {
        assertThat(policy.assess(Fixtures.plan()).notes())
                .anySatisfy(finding -> {
                    assertThat(finding.title()).isEqualTo("Isolated performance test");
                    assertThat(finding.detail()).contains("does not establish production capacity");
                });
    }

    @Nested
    @DisplayName("secret references")
    class Secrets {

        @Test
        void aPureReferenceIsSafeToDisplay() {
            assertThat(SecretReferences.mask("${VORTEX_AUTH_TOKEN}")).isEqualTo("${VORTEX_AUTH_TOKEN}");
        }

        @Test
        @DisplayName("a literal credential in a secret position is masked, never echoed")
        void literalValuesAreMasked() {
            assertThat(SecretReferences.mask("Bearer eyJhbGciOiJIUzI1NiJ9.abc"))
                    .isEqualTo(SecretReferences.MASK)
                    .doesNotContain("eyJ");
        }

        @Test
        void aReferenceEmbeddedInATemplateIsAlsoMasked() {
            assertThat(SecretReferences.mask("Bearer ${TOKEN}")).isEqualTo(SecretReferences.MASK);
        }

        @Test
        void referencedVariableNamesAreDiscoverable() {
            assertThat(SecretReferences.referencedNames("${A} and ${B}")).containsExactly("A", "B");
        }

        @Test
        void anEnvironmentReportsOnlyMaskedHeaderValues() {
            assertThat(Fixtures.performanceEnvironment().headerNames())
                    .containsEntry("Authorization", "${VORTEX_AUTH_TOKEN}");
            assertThat(Fixtures.performanceEnvironment().hasSecretReferences()).isTrue();
        }
    }
}
