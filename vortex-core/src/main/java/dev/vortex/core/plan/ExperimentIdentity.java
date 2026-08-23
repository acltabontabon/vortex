package dev.vortex.core.plan;

import dev.vortex.core.threshold.Durations;
import dev.vortex.core.workload.Stage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * What makes two executions the same experiment.
 *
 * <h2>Three identities, deliberately not one</h2>
 * <table border="1">
 *   <caption>The identities a run carries</caption>
 *   <tr><th>Identity</th><th>Answers</th><th>Example</th></tr>
 *   <tr><td>Execution</td><td>which physical run was this?</td><td>{@code 969e6a95…}</td></tr>
 *   <tr><td>Release</td><td>which build of the service was tested?</td><td>{@code 3f97a82}</td></tr>
 *   <tr><td>Experiment</td><td>under what conditions was it tested?</td><td>this class</td></tr>
 * </table>
 *
 * <p>Experiment identity answers one question: <em>would these two runs be expected to produce
 * comparable evidence if the service implementation were unchanged?</em> Everything that would
 * change the answer belongs in it, and nothing else does.
 *
 * <p>Which is why <strong>release identity is excluded</strong>. The service version is the thing a
 * regression comparison exists to vary; folding it into experiment identity would make every
 * release its own incomparable experiment and defeat the entire purpose. The same reasoning excludes
 * timestamps, execution ids and tool versions — they describe a run, not the conditions it ran
 * under. Labels are excluded too: renaming a workload changes nothing about what was measured, and
 * severing a service's comparison history over a rename would be an unpleasant surprise.
 *
 * <h2>One list, two consumers</h2>
 * The dimensions below are the single definition. {@link #canonicalForm} hashes them;
 * {@link #compare} explains them. Both walk the same list, so the fingerprint and the
 * human-readable difference can never disagree — the invariant
 * {@code compare(a, b).compatible() == fingerprintOf(a).equals(fingerprintOf(b))} holds by
 * construction rather than by discipline, and is asserted directly in the tests.
 *
 * <p>That structure exists because the alternative was tried. A content fingerprint and a
 * field-by-field equivalence check were maintained separately and drifted immediately: the
 * fingerprint covered stage shape and target URL, the equivalence check did not, and two plans with
 * different fingerprints could still be declared comparable.
 */
public final class ExperimentIdentity {

    /**
     * The version of this contract, included in the hash.
     *
     * <p>Bumped when the dimensions change, which necessarily changes every fingerprint. Explicit
     * rather than implicit so a hash computed under an older contract is identifiably different
     * rather than merely different.
     *
     * <p>{@code /2} added request data. Version 1 recorded only whether an operation <em>had</em> a
     * body, so two runs sending different payloads to the same endpoint fingerprinted identically
     * and were compared as one experiment. Existing executions are re-indexed on startup by
     * {@code ExecutionRepository.reindexExperimentFingerprints}; comparisons made under the old
     * contract are not retro-actively corrected, because the plans they were made from are what they
     * are.
     */
    public static final String SCHEMA = "vortex.experiment/2";

    private ExperimentIdentity() {
    }

    /**
     * One condition that defines an experiment.
     *
     * @param key      the canonical-form key, and the name used when reporting a difference
     * @param extract  the comparable value for a plan; {@code null} is omitted from the hash
     * @param describe how to phrase a difference between two plans, in plain language
     */
    private record Dimension(
            String key,
            Function<EffectiveTestPlan, Object> extract,
            BiFunction<EffectiveTestPlan, EffectiveTestPlan, String> describe) {
    }

    /**
     * The conditions, in the order differences are reported.
     *
     * <p>Ordered from the coarsest to the most specific, because when several things changed at
     * once the first line should be the one that most obviously explains why the numbers moved.
     */
    private static final List<Dimension> DIMENSIONS = List.of(

            new Dimension("testType",
                    plan -> plan.testType().name(),
                    (a, b) -> "test type changed from " + a.testType().label()
                            + " to " + b.testType().label()),

            // "50" under an arrival-rate workload and "50" under a concurrency workload are not the
            // same quantity, let alone the same experiment: one offers 50 requests every second
            // regardless of what the service does, the other lets 50 clients go as fast as it
            // allows. Comparing their latencies produces a percentage that reads like a regression
            // and measures nothing.
            new Dimension("workloadModel",
                    plan -> plan.workloadModel().name(),
                    (a, b) -> "workload model changed from " + a.workloadModel().label()
                            + " to " + b.workloadModel().label()
                            + ", which controls a different quantity entirely"),

            new Dimension("peakLevel",
                    plan -> plan.peakLevel().display(),
                    (a, b) -> "offered load changed from " + a.peakLevel().displayWithUnit()
                            + " to " + b.peakLevel().displayWithUnit()),

            // The unit travels separately so a rate and a virtual-user count can never collide on
            // the bare number.
            new Dimension("peakUnit",
                    plan -> plan.peakLevel().unit(),
                    (a, b) -> "offered load changed from " + a.peakLevel().displayWithUnit()
                            + " to " + b.peakLevel().displayWithUnit()),

            // Stage shape, not just the peak. A run held at 100/sec for ten minutes and a ramp that
            // arrives at 100/sec after ten minutes share a peak and a duration, and are different
            // experiments: the second reaches that level with an already-loaded, warmed-up system.
            new Dimension("stages",
                    ExperimentIdentity::canonicalStages,
                    ExperimentIdentity::describeStages),

            new Dimension("operations",
                    ExperimentIdentity::canonicalOperations,
                    ExperimentIdentity::describeOperations),

            new Dimension("thresholds",
                    ExperimentIdentity::canonicalThresholds,
                    (a, b) -> "the objectives being measured against changed"),

            new Dimension("environment",
                    plan -> plan.environmentName().toLowerCase(java.util.Locale.ROOT),
                    (a, b) -> "environment changed from " + a.environmentName()
                            + " to " + b.environmentName()),

            new Dimension("environmentType",
                    plan -> plan.environmentType().name(),
                    (a, b) -> "environment class changed from " + a.environmentType().label()
                            + " to " + b.environmentType().label()),

            // The declared configuration (executionTarget), not the resolved runtime endpoint: a
            // Docker runner's localhost rewrite is a property of how the run was carried out, not of
            // what was tested. Two environments may share a name and point somewhere entirely
            // different. summary() is used rather than configuredTarget().value() because a
            // Docker/Compose target has no pre-run TargetUrl at all — summary() is always present and
            // distinguishes target configuration the same way the configured URL did before, now
            // covering Docker/Compose targets too, not just endpoints.
            new Dimension("target",
                    plan -> plan.executionTarget().summary(),
                    (a, b) -> "target changed from " + a.executionTarget().summary()
                            + " to " + b.executionTarget().summary()),

            new Dimension("dependencyMode",
                    plan -> plan.dependencyMode().name(),
                    (a, b) -> "dependency mode changed from " + a.dependencyMode().label()
                            + " to " + b.dependencyMode().label()),

            new Dimension("classification",
                    plan -> plan.classification().name(),
                    (a, b) -> "test classification changed from " + a.classification().label()
                            + " to " + b.classification().label()),

            // Header values as written — that is, as secret references. A fingerprint must never
            // depend on a credential, and a changed reference name is a changed experiment.
            new Dimension("headers",
                    plan -> new LinkedHashMap<>(plan.headers()),
                    (a, b) -> "the request headers changed"),

            new Dimension("k6Options",
                    plan -> new LinkedHashMap<>(plan.k6Options()),
                    (a, b) -> "the raw engine options changed"),

            new Dimension("scriptSource",
                    plan -> plan.scriptSource().name(),
                    (a, b) -> "the script source changed from " + a.scriptSource()
                            + " to " + b.scriptSource()));

    /**
     * The canonical form hashed into a fingerprint.
     *
     * <p>Excludes identifiers, timestamps, tool versions, runner selection, safety confirmations,
     * k6 scenario keys, the service version, and every label — see the class comment.
     */
    public static Map<String, Object> canonicalForm(EffectiveTestPlan plan) {
        Objects.requireNonNull(plan, "plan");
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("schema", SCHEMA);
        for (Dimension dimension : DIMENSIONS) {
            form.put(dimension.key(), dimension.extract().apply(plan));
        }
        return form;
    }

    /** The fingerprint of a plan's experiment identity. */
    public static PlanFingerprint fingerprintOf(EffectiveTestPlan plan) {
        return PlanFingerprint.of(canonicalForm(plan));
    }

    /**
     * Whether two plans describe the same experiment, and what differs when they do not.
     *
     * <p>Conservative: any dimension that differs makes the pair incompatible. Vortex would rather
     * decline a verdict it could have given than give one it could not support.
     */
    public static ExperimentCompatibility compare(EffectiveTestPlan baseline,
            EffectiveTestPlan candidate) {

        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(candidate, "candidate");

        List<String> differences = new ArrayList<>();
        List<String> differingKeys = new ArrayList<>();

        for (Dimension dimension : DIMENSIONS) {
            Object left = dimension.extract().apply(baseline);
            Object right = dimension.extract().apply(candidate);
            if (Objects.equals(left, right)) {
                continue;
            }
            differingKeys.add(dimension.key());

            // A dimension may decline to describe itself when another has already said the same
            // thing more clearly — a single-stage workload's only stage is its peak. Declining
            // suppresses a duplicate sentence; it never suppresses the incompatibility, which is
            // decided below by whether any dimension differed at all.
            String described = dimension.describe().apply(baseline, candidate);
            if (!described.isBlank() && !differences.contains(described)) {
                differences.add(described);
            }
        }

        if (differingKeys.isEmpty()) {
            return ExperimentCompatibility.COMPATIBLE;
        }
        if (differences.isEmpty()) {
            // Every differing dimension declined to speak. Should not happen, and if it ever does
            // the honest outcome is to refuse the comparison and name the dimensions, rather than
            // to declare two demonstrably different experiments compatible because nothing found
            // the words for it.
            differences.add("these runs differ in " + String.join(", ", differingKeys));
        }
        return ExperimentCompatibility.of(differences);
    }

    // ------------------------------------------------------------------ stages

    private static Object canonicalStages(EffectiveTestPlan plan) {
        List<Object> stages = new ArrayList<>();
        for (Stage stage : plan.stages()) {
            stages.add(CanonicalJson.map(
                    "target", stage.target().display(),
                    "unit", stage.target().unit(),
                    "durationMs", stage.duration().toMillis()));
        }
        return stages;
    }

    /**
     * Names what actually changed about the shape, rather than saying "stages differ".
     *
     * <p>A different number of steps, a different total duration and a different ramp profile are
     * three distinguishable mistakes, and an engineer looking at two runs wants to know which one
     * they made.
     */
    private static String describeStages(EffectiveTestPlan a, EffectiveTestPlan b) {
        if (a.stages().size() != b.stages().size()) {
            return "workload shape changed from " + describeShape(a) + " to " + describeShape(b);
        }
        if (!a.totalDuration().equals(b.totalDuration())) {
            return "run duration changed from " + Durations.display(a.totalDuration())
                    + " to " + Durations.display(b.totalDuration());
        }
        // A single-stage workload's only stage is its peak, which the level dimension has already
        // reported. Saying it again as a "ramp profile" change would be both redundant and wrong:
        // a run held at one level has no ramp. Blank descriptions are skipped by the caller.
        if (a.stages().size() <= 1) {
            return "";
        }
        return "the ramp profile changed: " + describeProfile(a) + " became " + describeProfile(b);
    }

    private static String describeShape(EffectiveTestPlan plan) {
        int stages = plan.stages().size();
        return switch (stages) {
            case 0 -> "no stages";
            case 1 -> "a single level held for " + Durations.display(plan.totalDuration());
            default -> stages + " stages over " + Durations.display(plan.totalDuration());
        };
    }

    private static String describeProfile(EffectiveTestPlan plan) {
        return plan.stages().stream()
                .map(stage -> stage.target().display() + " for " + Durations.display(stage.duration()))
                .reduce((left, right) -> left + " → " + right)
                .orElse("no stages");
    }

    // ------------------------------------------------------------------ operations

    /**
     * The operations, as independent conditions only.
     *
     * <p>The allocated per-operation rate is deliberately absent. It is <em>derived</em> — the total
     * divided by the shares, by {@code RateAllocator} — so including it would make every change of
     * total offered load report itself twice: once truthfully as a changed level, and once as "the
     * operations being driven changed", which is not what happened and sends the reader to diff two
     * operation lists that are identical.
     *
     * <p>Identity carries the conditions somebody set, not the arithmetic Vortex did with them.
     *
     * <p>Request data is a condition somebody set, and it is carried in full. Recording only that an
     * operation had a body — which is what version 1 of this contract did — made two runs submitting
     * different payloads to the same endpoint indistinguishable, so a change of test data reported
     * itself as a change in the service. See {@link RequestDataIdentity}.
     */
    private static Object canonicalOperations(EffectiveTestPlan plan) {
        List<Object> operations = new ArrayList<>();
        for (PlannedOperation operation : plan.operations()) {
            operations.add(CanonicalJson.map(
                    "operation", operation.operationId().value(),
                    "method", operation.method().name(),
                    "path", operation.pathTemplate(),
                    "share", operation.share(),
                    "expect", operation.expect().describe(),
                    // The request data in full, not merely its shape. See RequestDataIdentity for
                    // what each source contributes and, more importantly, what it must not.
                    "requestData", RequestDataIdentity.canonicalForm(operation.requestData(), plan)));
        }
        return operations;
    }

    /**
     * Names the operation that moved, rather than reporting "the traffic changed".
     *
     * <p>"getOrder share changed from 30% to 45%" is a difference an engineer can act on. A bare
     * statement that the mix differs sends them to diff two YAML files by eye.
     */
    private static String describeOperations(EffectiveTestPlan a, EffectiveTestPlan b) {
        List<String> left = a.operations().stream().map(PlannedOperation::name).sorted().toList();
        List<String> right = b.operations().stream().map(PlannedOperation::name).sorted().toList();
        if (!left.equals(right)) {
            return "the set of operations changed from " + left + " to " + right;
        }

        List<String> moved = new ArrayList<>();
        for (PlannedOperation before : a.operations()) {
            b.operations().stream()
                    .filter(after -> after.operationId().equals(before.operationId()))
                    .findFirst()
                    .ifPresent(after -> {
                        if (before.share().compareTo(after.share()) != 0) {
                            moved.add(before.name() + " share changed from " + before.sharePercent()
                                    + "% to " + after.sharePercent() + "%");
                        } else if (!before.expect().describe().equals(after.expect().describe())) {
                            moved.add("what counts as a successful response for " + before.name()
                                    + " changed");
                        } else if (!before.pathTemplate().equals(after.pathTemplate())
                                || before.method() != after.method()) {
                            moved.add(before.name() + " now issues a different request");
                        } else if (!before.requestData().equals(after.requestData())) {
                            moved.add(describeRequestDataChange(before, after));
                        }
                    });
        }
        return moved.isEmpty()
                ? "the operations being driven changed"
                : String.join("; ", moved);
    }

    /**
     * Which part of an operation's request data moved.
     *
     * <p>"the request data changed" sends an engineer to diff two YAML files by eye. Naming the
     * position that moved — a header, a path value, a body field — is usually the whole answer.
     */
    private static String describeRequestDataChange(PlannedOperation before, PlannedOperation after) {
        List<String> parts = new ArrayList<>();
        if (!before.headers().equals(after.headers())) {
            parts.add("headers");
        }
        if (!before.pathValues().equals(after.pathValues())) {
            parts.add("path values");
        }
        if (!before.queryValues().equals(after.queryValues())) {
            parts.add("query parameters");
        }
        if (!before.body().equals(after.body())) {
            parts.add("the request body");
        }
        if (!before.bodyValues().equals(after.bodyValues())) {
            parts.add("body field values");
        }
        return parts.isEmpty()
                ? "the request data for " + before.name() + " changed"
                : "the request data for " + before.name() + " changed: "
                        + String.join(", ", parts);
    }

    // ------------------------------------------------------------------ thresholds

    /**
     * Objectives, sorted so that declaration order is not part of identity.
     *
     * <p>Reordering a threshold list in {@code vortex.yaml} does not change what the run is judged
     * against, and must not sever a comparison.
     */
    private static Object canonicalThresholds(EffectiveTestPlan plan) {
        return plan.thresholds().thresholds().stream()
                .map(threshold -> (Object) CanonicalJson.map(
                        "id", threshold.id(),
                        "describe", threshold.describe()))
                .sorted(Comparator.comparing(Object::toString))
                .toList();
    }
}
