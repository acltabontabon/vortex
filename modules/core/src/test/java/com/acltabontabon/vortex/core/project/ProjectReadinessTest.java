package com.acltabontabon.vortex.core.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.project.ProjectReadiness.Item;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The readiness checklist answers two different questions and must not confuse them.
 *
 * <p>"Is this finished?" and "can this run?" have different answers for almost every real project,
 * and an interface that conflates them either hides the primary action behind optional setup or
 * offers it when it cannot possibly work.
 */
class ProjectReadinessTest {

    /**
     * {@code canRun()} used to spell out its two conditions directly. It now derives them from the
     * same {@code requiredToRun} flags the interface reads when it explains a blocked action, so this
     * walks the whole truth table to prove the derivation says exactly what the literal did.
     */
    @Test
    @DisplayName("running depends on an environment and a workload, and on nothing else")
    void canRunMatchesTheRequiredItems() {
        for (int bits = 0; bits < 128; bits++) {
            ProjectReadiness readiness = new ProjectReadiness(
                    (bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0, (bits & 8) != 0,
                    (bits & 16) != 0, (bits & 32) != 0, (bits & 64) != 0);

            boolean expected = readiness.environmentConfigured() && readiness.anyWorkloadConfigured();

            assertThat(readiness.canRun())
                    .as("canRun for bits %d", bits)
                    .isEqualTo(expected)
                    .isEqualTo(readiness.blockers().isEmpty());
        }
    }

    @Test
    @DisplayName("blockers name the missing prerequisites, so the interface can explain rather than hide")
    void blockersNameWhatIsMissing() {
        ProjectReadiness nothing =
                new ProjectReadiness(false, false, false, false, false, false, false);

        assertThat(nothing.required()).hasSize(2);
        assertThat(nothing.blockers())
                .extracting(ProjectReadiness.Item::label)
                .containsExactly("Environment configured", "Workload defined");
        assertThat(nothing.blockers())
                .allSatisfy(item -> assertThat(item.nextStep()).isNotBlank());
    }

    /**
     * Objectives are the clearest case of the distinction: without them a run still happens, and what
     * it establishes is weaker. Gating the run on them would be the interface deciding on somebody's
     * behalf that weaker evidence is worse than none at all.
     */
    @Test
    @DisplayName("objectives, production traffic and an imported API do not block a run")
    void optionalItemsDoNotBlock() {
        ProjectReadiness runnable =
                new ProjectReadiness(false, true, true, false, false, false, false);

        assertThat(runnable.canRun()).isTrue();
        assertThat(runnable.blockers()).isEmpty();
        assertThat(runnable.nextAction()).isPresent();
        assertThat(runnable.nextAction().get().label()).isEqualTo("API imported");
    }

    /**
     * Availability is a third question, independent of the other two.
     *
     * <p>An item can be required and unavailable at the same time — a workload is both, on a service
     * with no imported API — and an interface that collapses "you must do this" into "you may do
     * this now" will either offer a form that cannot be filled in or hide the thing the user came for.
     */
    @Test
    @DisplayName("availability is separate from being required and from being done")
    void availabilityIsItsOwnDimension() {
        ProjectReadiness nothing =
                new ProjectReadiness(false, false, false, false, false, false, false);

        Item workload = itemNamed(nothing, "WORKLOAD");
        assertThat(workload.requiredToRun()).isTrue();
        assertThat(nothing.available(workload)).isFalse();
        assertThat(nothing.unmetPrerequisites(workload))
                .extracting(Item::key)
                .containsExactly("API_IMPORTED");

        ProjectReadiness imported =
                new ProjectReadiness(true, false, false, false, false, false, false);
        assertThat(imported.available(itemNamed(imported, "WORKLOAD"))).isTrue();
    }

    @Test
    @DisplayName("a signal that stands alone is available from the start")
    void standaloneItemsAreAvailableImmediately() {
        ProjectReadiness nothing =
                new ProjectReadiness(false, false, false, false, false, false, false);

        for (String key : List.of("API_IMPORTED", "ENVIRONMENT", "OBJECTIVES", "PRODUCTION_TRAFFIC")) {
            Item item = itemNamed(nothing, key);
            assertThat(item.requires()).as("%s stands alone", key).isEmpty();
            assertThat(nothing.available(item)).as("%s is available", key).isTrue();
        }
    }

    /**
     * The dependencies are a graph, not a sequence: an environment can be configured before, after or
     * instead of importing an API, and nothing about the workload branch changes that.
     */
    @Test
    @DisplayName("the prerequisites form independent branches rather than one ordering")
    void prerequisitesDoNotImposeASequence() {
        ProjectReadiness environmentOnly =
                new ProjectReadiness(false, true, false, false, false, false, false);

        assertThat(environmentOnly.available(itemNamed(environmentOnly, "API_IMPORTED"))).isTrue();
        assertThat(environmentOnly.available(itemNamed(environmentOnly, "OBJECTIVES"))).isTrue();
        assertThat(environmentOnly.available(itemNamed(environmentOnly, "WORKLOAD"))).isFalse();
    }

    @Test
    @DisplayName("anything with a prerequisite can say why it is not possible yet")
    void blockedItemsExplainThemselves() {
        ProjectReadiness nothing =
                new ProjectReadiness(false, false, false, false, false, false, false);

        assertThat(nothing.items())
                .filteredOn(item -> !item.requires().isEmpty())
                .isNotEmpty()
                .allSatisfy(item -> assertThat(item.blockedReason()).isNotBlank());
    }


    /**
     * The case that made this necessary: the interface was calling an OpenAPI import "optional" on a
     * project where nothing could be measured without it.
     */
    @Test
    @DisplayName("what a required item needs is not optional, however little it gates a run itself")
    void prerequisitesOfRequiredItemsAreNotOptional() {
        ProjectReadiness nothing =
                new ProjectReadiness(false, false, false, false, false, false, false);

        Item catalog = itemNamed(nothing, "API_IMPORTED");
        assertThat(catalog.requiredToRun())
                .as("importing does not itself gate a run")
                .isFalse();
        assertThat(nothing.effectivelyRequired(catalog))
                .as("but the required workload cannot be defined without it")
                .isTrue();

        assertThat(nothing.effectivelyRequired(itemNamed(nothing, "AVERAGE_LOAD_WORKLOAD"))).isFalse();
    }

    /**
     * Widening what counts as required for presentation must not widen what blocks a run — that pair
     * is what the CLI's exit codes are built on.
     */
    @Test
    @DisplayName("being unavoidable does not make something a blocker")
    void effectiveRequirementDoesNotChangeWhatBlocksARun() {
        ProjectReadiness ready =
                new ProjectReadiness(false, true, true, false, false, false, false);

        assertThat(ready.canRun()).isTrue();
        assertThat(ready.blockers()).isEmpty();
        assertThat(ready.effectivelyRequired(itemNamed(ready, "API_IMPORTED"))).isTrue();
    }


    /**
     * Objectives are the case the {@code EVALUATION} kind exists for.
     *
     * <p>They must not gate a run — that is a documented decision and the CLI's exit codes depend on
     * it — and they must not be called optional either. A run without them measures everything it
     * otherwise would and decides nothing, and "optional" is the wrong word for the thing that
     * decides pass from fail in a workbench built to produce pass/fail evidence.
     */
    @Test
    @DisplayName("objectives decide the verdict, so they are neither a blocker nor optional")
    void objectivesAreNeitherBlockingNorOptional() {
        ProjectReadiness nothing =
                new ProjectReadiness(false, false, false, false, false, false, false);

        Item objectives = itemNamed(nothing, "OBJECTIVES");
        assertThat(objectives.kind()).isEqualTo(ProjectReadiness.Kind.EVALUATION);
        assertThat(objectives.requiredToRun())
                .as("a run without objectives still happens")
                .isFalse();
        assertThat(nothing.blockers()).noneMatch(item -> item.key().equals("OBJECTIVES"));
        assertThat(nothing.effectivelyRequired(objectives))
                .as("but nothing it produces answers anything")
                .isTrue();
    }

    /**
     * What separates evaluation from enrichment: one makes there be an answer, the other makes an
     * existing answer stronger. Both are avoidable for a run; only one is avoidable full stop.
     */
    @Test
    @DisplayName("enrichment stays optional, because an answer survives without it")
    void enrichmentRemainsOptional() {
        ProjectReadiness nothing =
                new ProjectReadiness(false, false, false, false, false, false, false);

        Item item = itemNamed(nothing, "AVERAGE_LOAD_WORKLOAD");
        assertThat(item.kind()).isEqualTo(ProjectReadiness.Kind.ENRICHMENT);
        assertThat(nothing.effectivelyRequired(item)).as("optional").isFalse();
    }


    /**
     * The production baseline used to be optional because a service not yet serving anything could
     * not have a record of what it receives. That stopped being true the moment a rough,
     * manually-entered figure became just as valid an answer as an observed one — every project can
     * supply a number, so the one thing left to decide is whether Vortex should keep calling it
     * optional. It should not: an ungrounded workload level is an invented number, and headroom
     * computed against an invented number is not headroom.
     */
    @Test
    @DisplayName("a production baseline is unavoidable but never blocks a run")
    void productionTrafficIsGroundingButNotBlocking() {
        ProjectReadiness nothing =
                new ProjectReadiness(false, false, false, false, false, false, false);

        Item production = itemNamed(nothing, "PRODUCTION_TRAFFIC");
        assertThat(production.kind()).isEqualTo(ProjectReadiness.Kind.GROUNDING);
        assertThat(production.requiredToRun())
                .as("a run without a baseline still happens")
                .isFalse();
        assertThat(nothing.blockers()).noneMatch(item -> item.key().equals("PRODUCTION_TRAFFIC"));
        assertThat(nothing.effectivelyRequired(production))
                .as("but the verdict it feeds should not rest on an invented number")
                .isTrue();
    }

    /**
     * Narrowing is not the same relationship as depending. "Average-load workload defined" needs no
     * separate act of configuration while there is no workload at all — the same composer answers
     * both — and an interface that offered the two side by side would invite doing one thing twice.
     */
    @Test
    @DisplayName("a signal that only narrows an outstanding one is not a separate thing to do")
    void narrowingSignalsAreNotSeparateWork() {
        ProjectReadiness noWorkload =
                new ProjectReadiness(true, true, false, false, false, false, false);

        Item averageLoad = itemNamed(noWorkload, "AVERAGE_LOAD_WORKLOAD");
        assertThat(averageLoad.refines()).isEqualTo("WORKLOAD");
        assertThat(noWorkload.distinctFromWhatItNarrows(averageLoad)).isFalse();

        // Once a workload exists, describing the average-load baseline is its own work again.
        ProjectReadiness withWorkload =
                new ProjectReadiness(true, true, true, false, false, false, false);
        assertThat(withWorkload.distinctFromWhatItNarrows(
                itemNamed(withWorkload, "AVERAGE_LOAD_WORKLOAD"))).isTrue();
    }

    @Test
    @DisplayName("everything that stands on its own is always its own work")
    void standaloneSignalsAreAlwaysDistinct() {
        ProjectReadiness nothing =
                new ProjectReadiness(false, false, false, false, false, false, false);

        assertThat(nothing.items())
                .filteredOn(item -> item.refines() == null)
                .isNotEmpty()
                .allSatisfy(item -> assertThat(nothing.distinctFromWhatItNarrows(item)).isTrue());
    }

    private static Item itemNamed(ProjectReadiness readiness, String key) {
        return readiness.items().stream()
                .filter(item -> item.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no readiness item " + key));
    }
}
