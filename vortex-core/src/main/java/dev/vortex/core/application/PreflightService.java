package dev.vortex.core.application;

import dev.vortex.core.data.RequestValueOrigin;
import dev.vortex.core.environment.SecretReferences;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.PlannedOperation;
import dev.vortex.core.plan.ScriptSource;
import dev.vortex.core.port.PerformanceEngine;
import dev.vortex.core.safety.ExecutionPolicy;
import dev.vortex.core.safety.SafetyAssessment;
import dev.vortex.core.threshold.Durations;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Verifies that a test can run, and that it should, before any traffic is generated.
 *
 * <p>The checks are ordered by cost: configuration problems are found without touching the network,
 * engine availability without starting a run, and target reachability with a single request rather
 * than a load test. Nothing here sends meaningful traffic.
 */
public final class PreflightService {

    private final PerformanceEngine engine;
    private final ExecutionPolicy policy;
    private final Predicate<String> environmentVariableExists;
    private final TargetProbe targetProbe;

    /** Checks whether a target responds at all. Kept as a port so core stays free of HTTP clients. */
    @FunctionalInterface
    public interface TargetProbe {

        /**
         * @return empty when the target responded, otherwise a description of what went wrong
         */
        java.util.Optional<String> probe(String url);

        static TargetProbe skip() {
            return _ -> java.util.Optional.empty();
        }
    }

    public PreflightService(PerformanceEngine engine, ExecutionPolicy policy,
            Predicate<String> environmentVariableExists, TargetProbe targetProbe) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.environmentVariableExists =
                Objects.requireNonNull(environmentVariableExists, "environmentVariableExists");
        this.targetProbe = Objects.requireNonNull(targetProbe, "targetProbe");
    }

    public PreflightReport check(EffectiveTestPlan plan) {
        List<PreflightCheck> checks = new ArrayList<>();

        checkEngine(plan, checks);
        checkOperations(plan, checks);
        checkThresholds(plan, checks);
        checkSecrets(plan, checks);
        checkRequestData(plan, checks);
        checkScript(plan, checks);
        checkTarget(plan, checks);

        SafetyAssessment safety = policy.assess(plan);
        return new PreflightReport(plan, checks, safety);
    }

    private void checkEngine(EffectiveTestPlan plan, List<PreflightCheck> checks) {
        var availability = engine.availability();
        if (availability.available()) {
            checks.add(PreflightCheck.pass("Execution engine",
                    availability.version() + " (" + plan.runner().label() + ")"));
        } else {
            checks.add(PreflightCheck.fail("Execution engine",
                    availability.problem(), availability.remedy()));
        }
    }

    private void checkOperations(EffectiveTestPlan plan, List<PreflightCheck> checks) {
        if (plan.operations().isEmpty() && plan.scriptSource() == ScriptSource.GENERATED) {
            checks.add(PreflightCheck.fail("Operations",
                    "This plan contains no operations, so there is nothing to execute.",
                    "Add at least one operation to the workload. One is enough — a single operation "
                            + "is a complete performance target."));
            return;
        }

        String mix = plan.operations().stream()
                .map(operation -> operation.name() + " " + operation.sharePercent() + "%"
                        + operation.arrivalRateIfPresent().map(r -> " at " + r.display() + "/sec").orElse(""))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        checks.add(PreflightCheck.pass(
                plan.isSingleOperation() ? "Operation" : "Operation mix", mix));
    }

    private void checkThresholds(EffectiveTestPlan plan, List<PreflightCheck> checks) {
        if (plan.thresholds().isEmpty()) {
            checks.add(PreflightCheck.warn("Objectives",
                    "No latency or error-rate objectives are configured.",
                    "Without objectives this run will produce measurements but no verdict, so it "
                            + "cannot serve as evidence that the service meets its requirements. Add "
                            + "thresholds on the workload page."));
            return;
        }
        checks.add(PreflightCheck.pass("Objectives",
                plan.thresholds().thresholds().stream()
                        .map(t -> t.describe())
                        .reduce((a, b) -> a + "; " + b)
                        .orElse("")));
    }

    private void checkSecrets(EffectiveTestPlan plan, List<PreflightCheck> checks) {
        Set<String> required = new java.util.LinkedHashSet<>();
        plan.headers().values().forEach(value -> required.addAll(SecretReferences.referencedNames(value)));
        // And every request value that resolves from the environment. A token is as likely to be
        // bound to a query parameter or a body field as to a header, and a variable nobody exported
        // becomes an empty string and a puzzling 401 rather than an obvious failure.
        plan.operations().forEach(operation -> required.addAll(operation.referencedEnvironmentNames()));

        if (required.isEmpty()) {
            checks.add(PreflightCheck.skipped("Secrets", "This test uses no secret references."));
            return;
        }

        List<String> missing = required.stream()
                .filter(name -> !environmentVariableExists.test(name))
                .toList();

        if (missing.isEmpty()) {
            checks.add(PreflightCheck.pass("Secrets",
                    required.size() + " environment variable"
                            + (required.size() == 1 ? " is" : "s are") + " available: "
                            + String.join(", ", required)));
        } else {
            checks.add(PreflightCheck.fail("Secrets",
                    "These environment variables are referenced by the configuration but are not set: "
                            + String.join(", ", missing),
                    "Export them before running, for example: export " + missing.getFirst()
                            + "=... — Vortex never stores secret values, only references to them."));
        }
    }

    /**
     * What each request will actually carry, before any of it is sent.
     *
     * <p>Preflight's job is to let somebody see what is about to happen while nothing has happened
     * yet. A run that is about to create records from a dataset of five thousand customers is
     * exactly the kind of thing that should be visible on this page rather than discoverable
     * afterwards.
     *
     * <p>Sources, never values: a fixed literal is shown through the same masking the export path
     * uses, and an environment reference appears as the reference.
     */
    private void checkRequestData(EffectiveTestPlan plan, List<PreflightCheck> checks) {
        List<String> described = new ArrayList<>();
        for (PlannedOperation operation : plan.operations()) {
            for (var origin : RequestValueOrigin.of(operation.requestData())) {
                described.add(operation.name() + " · " + origin.describe());
            }
        }
        if (described.isEmpty()) {
            checks.add(PreflightCheck.skipped("Request data",
                    "Every request sends the values recorded in the configuration."));
            return;
        }

        String datasets = plan.datasets().stream()
                .map(dataset -> dataset.name() + " (" + dataset.recordCount() + " record"
                        + (dataset.recordCount() == 1 ? "" : "s") + ")")
                .reduce((a, b) -> a + ", " + b)
                .map(list -> " Reading " + list + ".")
                .orElse("");

        checks.add(PreflightCheck.pass("Request data",
                String.join("; ", described) + "." + datasets));
    }

    private void checkScript(EffectiveTestPlan plan, List<PreflightCheck> checks) {
        if (plan.scriptSource() == ScriptSource.IMPORTED) {
            checks.add(PreflightCheck.warn("Script source",
                    "This plan runs an imported k6 script.",
                    "Imported scripts execute code with the permissions of the selected runner. Only "
                            + "run scripts you trust. Per-operation metrics and request-count estimates "
                            + "are unavailable for imported scripts, because Vortex did not assign "
                            + "their workload keys and does not model their control flow."));
            return;
        }

        var engineValidation = engine.validate(plan);
        if (engineValidation.valid()) {
            checks.add(PreflightCheck.pass("Generated script", "The generated script is valid."));
        } else {
            checks.add(PreflightCheck.fail("Generated script",
                    String.join("; ", engineValidation.problems()),
                    "This usually indicates a configuration Vortex could not translate into a valid "
                            + "k6 scenario. The generated script is available under advanced details."));
        }
    }

    private void checkTarget(EffectiveTestPlan plan, List<PreflightCheck> checks) {
        var problem = targetProbe.probe(plan.effectiveTarget().value());
        if (problem.isEmpty()) {
            checks.add(PreflightCheck.pass("Target reachable", plan.effectiveTarget().value()));
        } else {
            checks.add(PreflightCheck.fail("Target reachable",
                    plan.effectiveTarget().value() + " did not respond: " + problem.get(),
                    "Check that the service is running and that the address and port are correct. "
                            + (plan.targetWasRewritten()
                            ? "Note that the effective target differs from the one you configured, "
                            + "because " + plan.targetRewriteReason()
                            : "")));
        }
    }

    /**
     * The estimates shown on the preflight screen, with their caveats.
     *
     * @param requests    total requests the run will attempt, or {@code null} when that cannot be
     *                    predicted from the workload alone
     * @param caveat      why the figure is an estimate, or why there is none
     * @param offeredLoad the level being applied, with its unit
     * @param duration    how long it will run
     */
    public record Estimates(Long requests, String caveat, String offeredLoad, String duration) {
    }

    public Estimates estimate(EffectiveTestPlan plan) {
        return new Estimates(
                plan.estimatedRequests().orElse(null),
                plan.requestEstimateCaveat(),
                plan.peakLevel().displayWithUnit(),
                Durations.display(plan.totalDuration()));
    }

    /** Mutating operations in the plan, listed so they can be shown by name before the run. */
    public List<String> mutatingOperations(EffectiveTestPlan plan) {
        return plan.operations().stream()
                .filter(PlannedOperation::isMutating)
                .map(op -> op.method() + " " + op.pathTemplate())
                .distinct()
                .toList();
    }
}
