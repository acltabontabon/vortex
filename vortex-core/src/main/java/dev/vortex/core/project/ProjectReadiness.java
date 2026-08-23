package dev.vortex.core.project;

import java.util.List;
import java.util.Optional;

/**
 * What a project still needs before it can produce useful performance evidence.
 *
 * <p>Presented as a short checklist so that someone opening an unfamiliar project can tell within
 * seconds whether it is performance-testing-ready. Deliberately not a score or a percentage:
 * turning engineering readiness into a number invites optimising the number.
 *
 * <h2>Two different questions</h2>
 *
 * <p>"Is this finished?" and "can this run?" are not the same question, and conflating them is how an
 * interface ends up either hiding the primary action behind five optional items or offering it when
 * it cannot possibly work. Only two items actually block a run — somewhere to send traffic, and
 * traffic to send. The rest make the evidence stronger rather than making it possible, which is why
 * they are reported but never gate anything.
 *
 * <p>{@link #required()} and {@link #blockers()} exist so an interface can explain a blocked action
 * instead of removing it. Setup is not the destination; the test is.
 */
public record ProjectReadiness(
        boolean apiImported,
        boolean environmentConfigured,
        boolean anyWorkloadConfigured,
        boolean averageLoadWorkloadConfigured,
        boolean thresholdsConfigured,
        boolean productionObserved,
        boolean testExecuted) {

    /**
     * What kind of thing a readiness item is, which is a different question from whether it is done.
     *
     * <p>{@code REQUIRED} and {@code ENRICHMENT} split on "does an unsatisfied state stop a test
     * running at all, or only weaken what the resulting evidence can establish". {@code RESULT} is
     * neither: it is not something anybody configures, it is something that becomes true once a run
     * has happened, and an interface that offers to "set it up" alongside the others is lying about
     * what it is. The distinction lives here rather than in a presentation layer because it is a
     * statement about the domain, and three callers guessing at it would arrive at three answers.
     */
    public enum Kind {
        /** Without it a test cannot run at all. */
        REQUIRED,
        /**
         * Without it a test runs and reaches no verdict.
         *
         * <p>Deliberately not {@code REQUIRED}: gating the run on objectives would be the interface
         * deciding on somebody's behalf that weaker evidence is worse than none at all, and a run
         * without them still produces every measurement it otherwise would. But it is not
         * enrichment either — enrichment makes an answer *stronger*, and this is what makes there be
         * an answer. A workbench that exists to produce deterministic pass/fail evidence should not
         * describe the thing that decides pass from fail as optional.
         */
        EVALUATION,
        /**
         * Without it the answer is weaker, and there is still an answer.
         *
         * <p>A production baseline lives here despite mattering a great deal — without one a
         * workload level is an invented number and no headroom can be computed at all. It is
         * optional for a reason that has nothing to do with importance: it is the only signal whose
         * availability depends on facts outside Vortex. An environment, an API description,
         * objectives and a workload can each be provided the moment somebody decides to; a record of
         * what a service actually receives cannot exist for a service that is not serving anything
         * yet. Marking as unavoidable a thing a whole class of legitimate projects can never supply
         * turns the distinction into nagging.
         */
        ENRICHMENT,
        /** Not configured at all — it becomes true once a run has happened. */
        RESULT
    }

    /**
     * A readiness item with its label, the action that would satisfy it, and what has to exist
     * before that action is even possible.
     *
     * @param key           a stable identity, unlike {@code label} — anything switching on which item
     *                      this is switches on this, so rewording a label cannot silently break a
     *                      caller
     * @param kind          what sort of item this is; see {@link Kind}
     * @param requires      the keys of the items that must be satisfied before this one can be, empty
     *                      when it stands alone
     * @param blockedReason why it cannot be done yet, in the domain's own words; null when nothing
     *                      is required
     * @param refines       the key of a broader item this one narrows, or null. Distinct from
     *                      {@code requires}: a prerequisite is a *different* action that must happen
     *                      first, whereas this is the *same* action described more precisely — while
     *                      the broader item is unsatisfied, one act of configuration answers both
     */
    public record Item(String key, String label, boolean satisfied, String nextStep, Kind kind,
            List<String> requires, String blockedReason, String refines) {

        public Item {
            requires = List.copyOf(requires);
            if (key.equals(refines)) {
                throw new IllegalArgumentException("Readiness item " + key + " refines itself.");
            }
            if (!requires.isEmpty() && (blockedReason == null || blockedReason.isBlank())) {
                throw new IllegalArgumentException(
                        "Readiness item " + key + " has prerequisites but no reason to state when "
                                + "they are unmet. An interface that can only say \"not yet\" and "
                                + "never why is worse than one that does not block at all.");
            }
        }

        /**
         * Whether an unsatisfied state of this item stops a test running at all.
         *
         * <p>Derived rather than stored: "required" has exactly one definition, and it is the kind.
         *
         * <p>Orthogonal to {@link ProjectReadiness#available(Item)}. "Does this block a run" and
         * "can this be done yet" are different questions, and an item can be any combination of the
         * two — a required item whose prerequisites are unmet is both.
         */
        public boolean requiredToRun() {
            return kind == Kind.REQUIRED;
        }
    }

    public List<Item> items() {
        return List.of(
                new Item("API_IMPORTED", "API imported", apiImported,
                        "Import an OpenAPI document so Vortex knows which operations exist.",
                        Kind.ENRICHMENT, List.of(), null, null),
                new Item("ENVIRONMENT", "Environment configured", environmentConfigured,
                        "Add a target so Vortex knows where to send traffic, and what that place is.",
                        Kind.REQUIRED, List.of(), null, null),
                new Item("WORKLOAD", "Workload defined", anyWorkloadConfigured,
                        "Describe a workload to apply — one operation is enough to start.",
                        Kind.REQUIRED, List.of("API_IMPORTED"),
                        "A workload spreads traffic across the things a service can do, so Vortex has "
                                + "to know what those are first.", null),
                new Item("AVERAGE_LOAD_WORKLOAD", "Average-load workload defined",
                        averageLoadWorkloadConfigured,
                        "Describe the traffic your service normally receives. This is the run you will "
                                + "compare future releases against.", Kind.ENRICHMENT,
                        List.of("API_IMPORTED"),
                        "A workload spreads traffic across the things a service can do, so Vortex has "
                                + "to know what those are first.", "WORKLOAD"),
                new Item("OBJECTIVES", "Objectives configured", thresholdsConfigured,
                        "State the latency and error objectives this service is expected to meet.",
                        Kind.EVALUATION, List.of(), null, null),
                new Item("PRODUCTION_TRAFFIC", "Production traffic recorded", productionObserved,
                        "Record what the service actually receives, so its workload is grounded in "
                                + "evidence rather than an invented number.", Kind.ENRICHMENT,
                        List.of(), null, null),
                new Item("TEST_EXECUTED", "Test executed", testExecuted,
                        "Run a smoke test to confirm Vortex can reach your service.", Kind.RESULT,
                        List.of("ENVIRONMENT", "WORKLOAD"),
                        "A run needs somewhere to send traffic and a workload to send.", null));
    }

    /**
     * The prerequisites of {@code item} that are not satisfied yet.
     *
     * <p>Here rather than in a presentation layer because it is a question only the whole readiness
     * can answer — an item knows which keys it needs, not whether they are done. Scattering
     * {@code if (!apiImported) disableWorkload} across callers is how three of them end up
     * disagreeing about when a workload can be defined.
     */
    public List<Item> unmetPrerequisites(Item item) {
        if (item.requires().isEmpty()) {
            return List.of();
        }
        return items().stream()
                .filter(candidate -> item.requires().contains(candidate.key()))
                .filter(candidate -> !candidate.satisfied())
                .toList();
    }

    /** Whether {@code item} can be worked on at all yet, as opposed to whether it is done. */
    public boolean available(Item item) {
        return unmetPrerequisites(item).isEmpty();
    }

    /**
     * Whether {@code item} is something the service cannot do without — unavoidable on the way to an
     * answer, rather than merely useful once there is one.
     *
     * <p>Three ways to qualify, and they are different claims. {@link Kind#REQUIRED} means a test
     * cannot run. {@link Kind#EVALUATION} means it runs and decides nothing. And being the
     * prerequisite of either means it cannot be reached at all: importing an API does not itself
     * gate a run — {@link #canRun()} asks for an environment and a workload, and a project with both
     * can run having imported nothing — but a workload cannot be *defined* without operations to
     * spread traffic across, so on a project with no workload the import is every bit as unavoidable
     * as the workload it feeds.
     *
     * <p>Kept out of {@link #required()} and {@link #canRun()} on purpose. Those two answer "can a
     * test run right now", they are what {@code ExitCode} and the CLI are built on, and widening
     * them would change what a blocked run means. This answers a different question, for the
     * interfaces that ask it — chiefly "may I call this optional", where the answer here is no.
     */
    public boolean effectivelyRequired(Item item) {
        if (essential(item)) {
            return true;
        }
        return items().stream()
                .filter(this::essential)
                .anyMatch(root -> dependsOn(root, item.key()));
    }

    /**
     * Whether {@code item} says anything a broader item does not already say.
     *
     * <p>False only while it narrows something still unsatisfied — "define an average-load workload"
     * is not a separate act of configuration on a service with no workload at all, it is the same
     * one described more precisely, and offering both invites somebody to do the same thing twice.
     * Once the broader item is satisfied the narrower one becomes its own action and this is true
     * again.
     */
    public boolean distinctFromWhatItNarrows(Item item) {
        if (item.refines() == null) {
            return true;
        }
        return items().stream()
                .filter(broader -> broader.key().equals(item.refines()))
                .allMatch(Item::satisfied);
    }

    /**
     * Unavoidable in its own right, before prerequisites are considered.
     *
     * <p>Two different failures, one answer. {@link Kind#REQUIRED}: no run at all.
     * {@link Kind#EVALUATION}: a run that decides nothing. {@link Kind#ENRICHMENT} leaves an answer
     * standing, which is why it is the only one this lets an interface call optional.
     */
    private boolean essential(Item item) {
        return switch (item.kind()) {
            case REQUIRED, EVALUATION -> true;
            case ENRICHMENT, RESULT -> false;
        };
    }

    /** Whether {@code root} needs {@code key}, directly or through anything it needs. */
    private boolean dependsOn(Item root, String key) {
        if (root.requires().contains(key)) {
            return true;
        }
        return items().stream()
                .filter(candidate -> root.requires().contains(candidate.key()))
                .anyMatch(candidate -> dependsOn(candidate, key));
    }

    /** The items without which a test cannot run at all. */
    public List<Item> required() {
        return items().stream().filter(Item::requiredToRun).toList();
    }

    /**
     * The required items that are not yet satisfied.
     *
     * <p>What an interface should name when it explains why the primary action is not available yet.
     */
    public List<Item> blockers() {
        return required().stream().filter(item -> !item.satisfied()).toList();
    }

    /**
     * Whether a test could be run right now.
     *
     * <p>Expressed through {@link #blockers()} rather than restating the two conditions, so there is
     * one definition of "required" and a third caller cannot arrive at a different answer.
     */
    public boolean canRun() {
        return blockers().isEmpty();
    }

    public long satisfiedCount() {
        return items().stream().filter(Item::satisfied).count();
    }

    public int totalCount() {
        return items().size();
    }

    /** The single most useful thing to do next, or empty when everything is in place. */
    public Optional<Item> nextAction() {
        return items().stream().filter(item -> !item.satisfied()).findFirst();
    }
}
