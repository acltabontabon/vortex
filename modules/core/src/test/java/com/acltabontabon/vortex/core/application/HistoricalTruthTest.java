package com.acltabontabon.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.plan.RunnerKind;
import com.acltabontabon.vortex.core.plan.ScriptSource;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.workload.ConstantArrivalRateShape;
import com.acltabontabon.vortex.core.workload.RateAllocator;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Editing or deleting a workload must never change what a finished run says it did.
 *
 * <p>This is the property that makes a run evidence rather than a view. A workload definition is
 * editable and lives in version control; a run is a record of what actually happened, and if
 * changing the first could rewrite the second then no result could be trusted after the fact.
 *
 * <p>The guarantee is structural rather than enforced by a rule: resolution copies everything it
 * needs into an {@link EffectiveTestPlan}, and the plan is stored by value on the execution. These
 * tests pin that down, because it would be easy to reintroduce a reference — a workload name looked
 * up at render time would be enough — and nothing else would fail.
 */
@DisplayName("history survives edits to the workloads it came from")
class HistoricalTruthTest {

    private final PlanResolver resolver =
            new PlanResolver(new RateAllocator(), new RequestDataResolver(),
                    new com.acltabontabon.vortex.core.fixtures.FakeDatasetStore());

    private EffectiveTestPlan resolve(com.acltabontabon.vortex.core.project.ProjectConfiguration configuration,
            String workloadName) {
        return resolver.resolve(Fixtures.project(), configuration, Fixtures.catalog(),
                new PlanResolver.ResolutionRequest(workloadName, "local", null,
                        RunnerKind.LOCAL_BINARY, ScriptSource.GENERATED, List.of(), null, ""));
    }

    @Test
    @DisplayName("a run keeps the rate it executed after the workload's rate is changed")
    void changingTheRateDoesNotRewriteAFinishedRun() {
        var original = Fixtures.configuration();
        var plan = resolve(original, "average-load");

        assertThat(plan.peakLevel()).isEqualTo(RequestsPerSecond.of(20));

        // The user edits the workload: 20 requests/sec becomes 145.
        var edited = Fixtures.workload("average-load",
                com.acltabontabon.vortex.core.workload.TestType.AVERAGE_LOAD, Fixtures.operationMix(),
                ConstantArrivalRateShape.of(145, Duration.ofMinutes(10)));
        var afterEdit = original.withWorkload(edited);

        // The already-resolved plan is a snapshot, not a view onto the configuration.
        assertThat(plan.peakLevel()).isEqualTo(RequestsPerSecond.of(20));
        assertThat(afterEdit.workloadByName("average-load").orElseThrow().peakLevel())
                .isEqualTo(RequestsPerSecond.of(145));

        // ...and the per-operation split the run actually drove is unchanged too, not recomputed.
        assertThat(plan.totalArrivalRate()).hasValue(RequestsPerSecond.of(20));
    }

    @Test
    @DisplayName("a run remains readable after the workload it came from is deleted")
    void deletingTheWorkloadLeavesTheRunIntact() {
        var original = Fixtures.configuration();
        var plan = resolve(original, "average-load");

        var afterDelete = original.withoutWorkload("average-load");

        assertThat(afterDelete.workloadByName("average-load")).isEmpty();

        // Everything the result page needs is on the plan, so there is nothing left to dangle.
        assertThat(plan.workloadName()).isEqualTo("average-load");
        assertThat(plan.testType()).isEqualTo(com.acltabontabon.vortex.core.workload.TestType.AVERAGE_LOAD);
        assertThat(plan.operations()).isNotEmpty();
        assertThat(plan.thresholds().isEmpty()).isFalse();
        assertThat(plan.environmentName()).isEqualTo("local");
    }

    @Test
    @DisplayName("deleting an absent workload is not an error")
    void deletingSomethingAbsentIsFine() {
        var configuration = Fixtures.configuration();

        assertThat(configuration.withoutWorkload("never-existed").workloads())
                .hasSameSizeAs(configuration.workloads());
    }

    @Test
    @DisplayName("renaming a workload does not sever a run's experiment identity")
    void renamingDoesNotSeverComparability() {
        var original = Fixtures.configuration();
        var before = resolve(original, "average-load");

        // Same experiment, different label. ADR-027 excludes the name from identity precisely so
        // that tidying up a name does not orphan the history compared against it.
        var renamed = Fixtures.workload("weekday-traffic",
                com.acltabontabon.vortex.core.workload.TestType.AVERAGE_LOAD, Fixtures.operationMix(),
                ConstantArrivalRateShape.of(20, Duration.ofMinutes(10)));
        var after = resolve(original.withoutWorkload("average-load").withWorkload(renamed),
                "weekday-traffic");

        assertThat(after.fingerprint()).isEqualTo(before.fingerprint());
    }
}
