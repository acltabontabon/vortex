package dev.vortex.core.plan;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.workload.TestType;
import dev.vortex.core.workload.ConstantArrivalRateShape;
import dev.vortex.core.workload.ConstantConcurrencyShape;
import dev.vortex.core.workload.OperationMix;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fingerprint answers one question: did these two executions run the same test? It has to be
 * insensitive to how the configuration was written and sensitive to what it says.
 */
class PlanFingerprintTest {

    @Test
    @DisplayName("key order does not change the fingerprint")
    void keyOrderIsIrrelevant() {
        Map<String, Object> oneOrder = new LinkedHashMap<>();
        oneOrder.put("arrivalRate", 20);
        oneOrder.put("duration", "10m");

        Map<String, Object> otherOrder = new LinkedHashMap<>();
        otherOrder.put("duration", "10m");
        otherOrder.put("arrivalRate", 20);

        assertThat(PlanFingerprint.of(oneOrder)).isEqualTo(PlanFingerprint.of(otherOrder));
    }

    @Test
    void unsetOptionalFieldsDoNotAffectTheFingerprint() {
        Map<String, Object> withoutOptional = Map.of("rate", 20);
        Map<String, Object> withNullOptional = new LinkedHashMap<>();
        withNullOptional.put("rate", 20);
        withNullOptional.put("note", null);

        assertThat(PlanFingerprint.of(withoutOptional)).isEqualTo(PlanFingerprint.of(withNullOptional));
    }

    @Test
    void numbersAreNormalisedSoFormattingDoesNotMatter() {
        assertThat(CanonicalJson.render(Map.of("rate", new java.math.BigDecimal("20.000"))))
                .isEqualTo(CanonicalJson.render(Map.of("rate", 20)));
    }

    @Test
    void changingAnArrivalRateChangesTheFingerprint() {
        var slower = Fixtures.plan(TestType.AVERAGE_LOAD,
                ConstantArrivalRateShape.of(20, Duration.ofMinutes(10)));
        var faster = Fixtures.plan(TestType.AVERAGE_LOAD,
                ConstantArrivalRateShape.of(21, Duration.ofMinutes(10)));

        assertThat(slower.fingerprint()).isNotEqualTo(faster.fingerprint());
    }

    @Test
    @DisplayName("the same number under a different workload model is a different test")
    void workloadModelChangesTheFingerprint() {
        var byRate = Fixtures.plan(TestType.AVERAGE_LOAD,
                ConstantArrivalRateShape.of(50, Duration.ofMinutes(10)),
                OperationMix.single(Fixtures.GET_ORDER));
        var byConcurrency = Fixtures.plan(TestType.AVERAGE_LOAD,
                ConstantConcurrencyShape.of(50, Duration.ofMinutes(10)),
                OperationMix.single(Fixtures.GET_ORDER));

        // Both say "50". One offers 50 requests every second whatever the service does; the other
        // holds 50 clients that wait for it. Treating them as the same test would let a regression
        // comparison produce a confident percentage across two different experiments.
        assertThat(byRate.fingerprint()).isNotEqualTo(byConcurrency.fingerprint());
        assertThat(byRate.describesSameTestAs(byConcurrency)).isFalse();
    }

    @Test
    @DisplayName("changing the operation mix changes the fingerprint")
    void operationMixChangesTheFingerprint() {
        var twoWay = Fixtures.plan(TestType.AVERAGE_LOAD,
                ConstantArrivalRateShape.of(100, Duration.ofMinutes(10)), Fixtures.operationMix());
        var fourWay = Fixtures.plan(TestType.AVERAGE_LOAD,
                ConstantArrivalRateShape.of(100, Duration.ofMinutes(10)), Fixtures.fourWayMix());

        assertThat(twoWay.fingerprint()).isNotEqualTo(fourWay.fingerprint());
    }

    @Test
    @DisplayName("the k6 scenario key is not part of the plan's identity")
    void scenarioKeysDoNotAffectTheFingerprint() {
        var plan = Fixtures.plan();
        var renamed = new EffectiveTestPlan(plan.id(), plan.projectId(), plan.projectName(),
                plan.serviceVersion(), plan.intent(), plan.workloadName(), plan.workloadDescription(),
                plan.testType(), plan.workloadModel(), plan.peakLevel(), plan.stages(),
                plan.operations().stream()
                        .map(operation -> new PlannedOperation(operation.operationId(),
                                operation.name(), operation.k6ScenarioKey() + "_renamed",
                                operation.method(), operation.pathTemplate(),
                                operation.requestData(), operation.provenance(), operation.expect(),
                                operation.share(), operation.arrivalRate()))
                        .toList(),
                plan.workloadSource(), plan.thresholds(), plan.environmentName(),
                plan.environmentType(), plan.configuredTarget(), plan.effectiveTarget(),
                plan.targetRewriteReason(), plan.dependencyMode(), plan.classification(),
                plan.headers(), plan.k6Options(), plan.runner(), plan.scriptSource(),
                plan.safetyDecisions(), null).withComputedFingerprint();

        // The key is how measurements are attributed, not what the test is. Two runs that differ
        // only by it ran the same experiment.
        assertThat(renamed.fingerprint()).isEqualTo(plan.fingerprint());
    }

    @Test
    void identicalPlansShareAFingerprint() {
        assertThat(Fixtures.plan().fingerprint()).isEqualTo(Fixtures.plan().fingerprint());
        assertThat(Fixtures.plan().describesSameTestAs(Fixtures.plan())).isTrue();
    }

    @Test
    void fingerprintsAreSha256Hex() {
        var fingerprint = PlanFingerprint.of(Map.of("a", 1));

        assertThat(fingerprint.algorithm()).isEqualTo("SHA-256");
        assertThat(fingerprint.hash()).matches("[0-9a-f]{64}");
        assertThat(fingerprint.shortHash()).hasSize(8);
    }

    @Test
    void unicodeIsNormalisedBeforeHashing() {
        String composed = "café";
        String decomposed = "café";

        assertThat(PlanFingerprint.of(Map.of("name", composed)))
                .isEqualTo(PlanFingerprint.of(Map.of("name", decomposed)));
    }

    @Test
    void controlCharactersAreEscapedRatherThanEmitted() {
        String withControlChar = "a" + (char) 1 + "b";

        assertThat(CanonicalJson.render(Map.of("k", withControlChar)))
                .isEqualTo("{\"k\":\"a\\u0001b\"}");
    }

    @Test
    @DisplayName("the canonical form of a real plan contains no credential material")
    void noSecretValuesInTheCanonicalForm() {
        String canonical = CanonicalJson.render(Fixtures.plan().canonicalForm());

        assertThat(canonical).doesNotContain("Bearer").doesNotContain("eyJ");
    }
}
