package dev.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.catalog.ServiceCatalog;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.RunnerKind;
import dev.vortex.core.plan.ScriptSource;
import dev.vortex.core.project.ProjectConfiguration;
import dev.vortex.core.workload.RateAllocator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which build of the service a run says it tested.
 *
 * <p>Release identity is what makes a comparison mean something: two runs of the same experiment
 * against the same build measure run-to-run noise, and against different builds they measure a
 * change. Without it, every comparison is ambiguous in a way nothing downstream can recover.
 */
class ReleaseIdentityTest {

    private final PlanResolver resolver =
            new PlanResolver(new RateAllocator(), new RequestDataResolver(),
                    new dev.vortex.core.fixtures.FakeDatasetStore());

    private EffectiveTestPlan resolve(ProjectConfiguration configuration, String override) {
        return resolver.resolve(Fixtures.project(), configuration, Fixtures.catalog(),
                new PlanResolver.ResolutionRequest("average-load", "local", null,
                        RunnerKind.LOCAL_BINARY, ScriptSource.GENERATED, List.of(), null, override));
    }

    private static ProjectConfiguration configuredWith(String version) {
        return Fixtures.configuration().withServiceVersion(version);
    }

    @Test
    @DisplayName("the configured release reaches the plan that actually runs")
    void configuredVersionSurvivesResolution() {
        var plan = resolve(configuredWith("v2.17.0"), "");

        assertThat(plan.serviceVersion()).isEqualTo("v2.17.0");
        assertThat(plan.serviceVersionIfPresent()).contains("v2.17.0");
    }

    @Test
    @DisplayName("the command line wins, which is how a pipeline stamps the commit it checked out")
    void theOverrideTakesPrecedence() {
        var plan = resolve(configuredWith("v2.17.0"), "3f97a82");

        assertThat(plan.serviceVersion()).isEqualTo("3f97a82");
    }

    @Test
    @DisplayName("an absent release stays absent — 'unknown' is not a version")
    void absentStaysAbsent() {
        var plan = resolve(configuredWith(""), "");

        assertThat(plan.serviceVersion()).isEmpty();
        assertThat(plan.serviceVersionIfPresent()).isEmpty();
    }

    @Test
    @DisplayName("a blank override means 'say nothing', not 'record nothing'")
    void aBlankOverrideFallsBackToConfiguration() {
        assertThat(resolve(configuredWith("v2.17.0"), "   ").serviceVersion()).isEqualTo("v2.17.0");
    }

    @Test
    @DisplayName("the release does not leak into anything else the plan describes")
    void nothingElseIsAffected() {
        var withVersion = resolve(configuredWith("v2.17.0"), "");
        var without = resolve(configuredWith(""), "");

        assertThat(withVersion.operations()).isEqualTo(without.operations());
        assertThat(withVersion.thresholds()).isEqualTo(without.thresholds());
        assertThat(withVersion.peakLevel().display()).isEqualTo(without.peakLevel().display());
        assertThat(withVersion.configuredTarget()).isEqualTo(without.configuredTarget());
    }

    @Test
    @DisplayName("two releases of one experiment share a fingerprint, which is the entire point")
    void releasesDoNotForkTheExperiment() {
        var before = resolve(configuredWith("v2.17.0"), "");
        var after = resolve(configuredWith("v2.18.0"), "");

        assertThat(before.fingerprint()).isEqualTo(after.fingerprint());
        assertThat(before.describesSameTestAs(after)).isTrue();
    }

    @Test
    @DisplayName("resolution still never writes back into the configuration")
    void resolutionRemainsOneDirectional() {
        var configuration = configuredWith("v2.17.0");
        resolve(configuration, "3f97a82");

        // The override belongs to that run. Next week's edit of vortex.yaml should still show
        // whatever a person wrote in it.
        assertThat(configuration.serviceVersion()).isEqualTo("v2.17.0");
    }

    @Test
    @DisplayName("a catalog that resolves nothing is a different failure, unaffected by the release")
    void anEmptyCatalogStillFails() {
        var configuration = configuredWith("v2.17.0");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                resolver.resolve(Fixtures.project(), configuration, ServiceCatalog.empty(),
                        new PlanResolver.ResolutionRequest("average-load", "local", null,
                                RunnerKind.LOCAL_BINARY, ScriptSource.GENERATED, List.of(), null,
                                "3f97a82"))))
                .isInstanceOf(PlanResolutionException.class);
    }
}
