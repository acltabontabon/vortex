package dev.vortex.core.safety;

import dev.vortex.core.environment.EnvironmentType;
import dev.vortex.core.environment.TargetUrl;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.shared.LoadLevel;
import dev.vortex.core.threshold.Durations;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Decides what a user must be told, and must agree to, before a test runs.
 *
 * <p>A performance testing tool can cause an incident. Vortex treats that as a core property rather
 * than a disclaimer: the target and its environment class are always shown, non-local targets need
 * explicit confirmation, traffic rates have per-environment ceilings, and mutating operations are
 * called out by name.
 *
 * <p>Two things this policy deliberately does <em>not</em> do. It does not treat a hostname as
 * evidence of what an environment is — a host containing "prod" raises additional caution, but the
 * environment class the user configured remains authoritative, because guessing from strings is
 * exactly the confidently-wrong behaviour this product exists to avoid. And it does not offer a
 * blanket "Are you sure?": where the consequences are real, the confirmation names the thing being
 * risked.
 */
public final class ExecutionPolicy {

    private static final List<String> PRODUCTION_HINTS =
            List.of("prod", "production", "prd", "live");

    private final SafetyLimits limits;

    public ExecutionPolicy(SafetyLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public static ExecutionPolicy withDefaults() {
        return new ExecutionPolicy(SafetyLimits.defaults());
    }

    public SafetyLimits limits() {
        return limits;
    }

    public SafetyAssessment assess(EffectiveTestPlan plan) {
        List<SafetyFinding> findings = new ArrayList<>();
        assessTarget(plan, findings);
        assessRate(plan, findings);
        assessDuration(plan, findings);
        assessMutations(plan, findings);
        assessClassification(plan, findings);
        return new SafetyAssessment(findings);
    }

    private void assessTarget(EffectiveTestPlan plan, List<SafetyFinding> findings) {
        TargetUrl target = plan.configuredTarget();

        if (!limits.isHostAllowed(target.host())) {
            findings.add(SafetyFinding.blocking("target.allowlist",
                    "Target host is not on the allowlist",
                    "Vortex is configured to only send traffic to specific hosts, and "
                            + target.host() + " is not one of them. Add it under "
                            + "Settings → Safety, or choose a different environment."));
            return;
        }

        if (plan.environmentType().requiresExplicitConfirmation()) {
            findings.add(SafetyFinding.challenge("target.non-local",
                    "This test targets " + plan.environmentName(),
                    "Traffic will be sent to " + target.value() + ", which you have classified as "
                            + plan.environmentType().label() + ". "
                            + plan.environmentType().description()
                            + " Confirm by typing the environment name.",
                    plan.environmentName()));
        }

        String host = target.host().toLowerCase(Locale.ROOT);
        boolean looksProduction = PRODUCTION_HINTS.stream()
                .anyMatch(hint -> host.equals(hint)
                        || host.startsWith(hint + ".")
                        || host.startsWith(hint + "-")
                        || host.contains("." + hint + ".")
                        || host.contains("-" + hint + "."));
        if (looksProduction) {
            findings.add(SafetyFinding.warning("target.production-hint",
                    "This hostname looks production-like",
                    "The host " + target.host() + " contains a word often used for production "
                            + "systems. This is a best-effort hint only — Vortex cannot determine what "
                            + "an environment really is from its name, and your configured environment "
                            + "class (" + plan.environmentType().label() + ") remains authoritative. "
                            + "Check that this is the target you intend."));
        }

        if (plan.targetWasRewritten()) {
            findings.add(SafetyFinding.info("target.rewritten",
                    "The effective target differs from the one you configured",
                    "Configured: " + plan.configuredTarget().value() + "\n"
                            + "Effective: " + plan.effectiveTarget().value() + "\n"
                            + plan.targetRewriteReason()));
        }
    }

    private void assessRate(EffectiveTestPlan plan, List<SafetyFinding> findings) {
        EnvironmentType type = plan.environmentType();
        LoadLevel requested = plan.peakLevel();
        LoadLevel ceiling = limits.ceilingFor(type, requested);

        if (requested.asDouble() > ceiling.asDouble()) {
            findings.add(SafetyFinding.challenge("rate.ceiling",
                    "Requested load exceeds the recommended limit for this environment",
                    "Configured limit for " + type.label() + ": " + ceiling.displayWithUnit()
                            + ".\nRequested: " + requested.displayWithUnit() + ".\n"
                            + "Generating more load than an environment is sized for can disrupt "
                            + "other people's work. Confirm by typing the environment name.",
                    plan.environmentName()));
        }
    }

    private void assessDuration(EffectiveTestPlan plan, List<SafetyFinding> findings) {
        if (plan.totalDuration().compareTo(limits.maximumDuration()) > 0) {
            findings.add(SafetyFinding.blocking("duration.ceiling",
                    "This run would last longer than the configured maximum",
                    "Planned duration: " + Durations.display(plan.totalDuration())
                            + ". Maximum: " + Durations.display(limits.maximumDuration())
                            + ". Shorten the workload, or raise the maximum under Settings → Safety."));
        }
    }

    private void assessMutations(EffectiveTestPlan plan, List<SafetyFinding> findings) {
        List<String> mutating = plan.operations().stream()
                .filter(op -> op.isMutating())
                .map(op -> op.method() + " " + op.pathTemplate()
                        + op.arrivalRateIfPresent().map(rate -> " at " + rate.displayWithUnit()).orElse(""))
                .distinct()
                .toList();

        if (!mutating.isEmpty()) {
            findings.add(SafetyFinding.warning("operation.mutations",
                    mutating.size() + " operation" + (mutating.size() == 1 ? "" : "s")
                            + " in this test can change data",
                    "This run will repeatedly execute:\n  " + String.join("\n  ", mutating)
                            + "\nSustained at " + plan.peakLevel().displayWithUnit() + " for "
                            + Durations.display(plan.totalDuration()) + ", this will create or modify a "
                            + "large amount of data. Make sure the target's data can absorb that, and "
                            + "that the request payloads are appropriate."));
        }
    }

    private void assessClassification(EffectiveTestPlan plan, List<SafetyFinding> findings) {
        findings.add(SafetyFinding.info("classification",
                plan.classification().label(),
                plan.classification().caveat()));
    }
}
