package dev.vortex.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.environment.SecretReferences;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.plan.CanonicalJson;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.project.ProjectConfiguration;
import dev.vortex.persistence.config.YamlConfigurationStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A resolved secret must exist in exactly one place: the environment of the load-generator process,
 * for as long as that process runs.
 *
 * <p>Everything Vortex writes down — configuration, effective plans, artifacts, reports, history,
 * AI prompts — carries the environment-variable reference instead. These tests guard the persisted
 * surfaces; {@code K6ScriptGeneratorTest} guards the generated workload, and
 * {@code ExecutionPolicyTest} guards display and masking.
 */
class SecretsNeverPersistTest {

    /** Deliberately token-shaped, so a substring match would catch a partial leak too. */
    private static final String SECRET = "eyJhbGciOiJIUzI1NiJ9.SUPERSECRET.signature";

    /** Assembled rather than written literally, so this file contains no credential-shaped text. */
    private static final String REFERENCE = "$" + "{VORTEX_AUTH_TOKEN}";
    private static final String OTHER_REFERENCE = "$" + "{OTHER_TOKEN}";

    private final YamlConfigurationStore configurationStore = new YamlConfigurationStore();

    private ProjectConfiguration configurationWithASecretHeader() {
        return Fixtures.configuration()
                .withEnvironments(List.of(Fixtures.performanceEnvironment()));
    }

    @Test
    @DisplayName("the portable configuration stores the reference, never a resolved value")
    void configurationKeepsTheReference() {
        String yaml = configurationStore.render(configurationWithASecretHeader());

        assertThat(yaml).contains(REFERENCE);
        assertThat(yaml).doesNotContain(SECRET).doesNotContain("SUPERSECRET");
    }

    @Test
    @DisplayName("a rendered configuration round-trips its reference unchanged")
    void referenceSurvivesARoundTrip() {
        var reloaded = configurationStore
                .parse(configurationStore.render(configurationWithASecretHeader()), "test")
                .configuration();

        assertThat(reloaded.environments()).singleElement().satisfies(environment -> {
            assertThat(environment.headers()).containsEntry("Authorization", REFERENCE);
            assertThat(environment.hasSecretReferences()).isTrue();
        });
    }

    @Test
    @DisplayName("an effective plan serialises the reference, so plan.json never holds a credential")
    void effectiveTestPlanKeepsTheReference() throws Exception {
        String json = JsonDocuments.mapper()
                .writeValueAsString(planWithHeader(Fixtures.plan(), REFERENCE));

        assertThat(json).contains(REFERENCE);
        assertThat(json).doesNotContain(SECRET).doesNotContain("SUPERSECRET");
    }

    @Test
    @DisplayName("the fingerprint depends on the reference, so it can never encode a credential")
    void fingerprintingUsesTheReferenceOnly() {
        var withReference = planWithHeader(Fixtures.plan(), REFERENCE);
        var withDifferentReference = planWithHeader(Fixtures.plan(), OTHER_REFERENCE);

        // A different reference is a different test configuration, so the fingerprint moves.
        assertThat(withReference.fingerprint()).isNotEqualTo(withDifferentReference.fingerprint());

        // And the canonical form carries no credential material of its own.
        assertThat(CanonicalJson.render(withReference.canonicalForm()))
                .contains("VORTEX_AUTH_TOKEN")
                .doesNotContain("SUPERSECRET");
    }

    @Test
    @DisplayName("a literal credential in a header position is masked for display")
    void literalValuesAreMaskedForDisplay() {
        assertThat(SecretReferences.mask(SECRET))
                .isEqualTo(SecretReferences.MASK)
                .doesNotContain("SUPERSECRET");
    }

    @Test
    @DisplayName("a pure reference is safe to show, since it reveals nothing")
    void referencesAreShownAsThemselves() {
        assertThat(SecretReferences.mask(REFERENCE)).isEqualTo(REFERENCE);
    }

    private EffectiveTestPlan planWithHeader(EffectiveTestPlan base, String value) {
        return new EffectiveTestPlan(
                base.id(), base.projectId(), base.projectName(), base.serviceVersion(),
                base.intent(), base.workloadName(), base.workloadDescription(), base.testType(),
                base.workloadModel(), base.peakLevel(), base.stages(), base.operations(),
                base.workloadSource(), base.thresholds(), base.environmentName(),
                base.environmentType(), base.configuredTarget(), base.effectiveTarget(), "",
                base.dependencyMode(), base.classification(), Map.of("Authorization", value),
                base.k6Options(), base.runner(), base.scriptSource(), List.of(), null)
                .withComputedFingerprint();
    }
}
