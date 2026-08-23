package dev.vortex.core.application;

import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.safety.SafetyAssessment;
import java.util.List;
import java.util.Objects;

/**
 * What Vortex checked before running a test, and what it found.
 *
 * <p>Preflight exists because the most expensive performance test is one that runs for fifteen
 * minutes against the wrong target, or fails after ten seconds for a reason that could have been
 * detected in advance. It is also the last point at which a person sees exactly what is about to
 * happen, stated in plain language.
 *
 * @param plan   the resolved plan, rendered on screen as the human-readable preflight summary
 * @param checks individual verifications and their outcomes
 * @param safety what the safety policies found
 */
public record PreflightReport(
        EffectiveTestPlan plan,
        List<PreflightCheck> checks,
        SafetyAssessment safety) {

    public PreflightReport {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(safety, "safety");
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    /** Whether the run may proceed, assuming any required confirmations are given. */
    public boolean canRun() {
        return checks.stream().noneMatch(PreflightCheck::isFailure) && !safety.isBlocked();
    }

    public boolean requiresConfirmation() {
        return safety.requiresConfirmation();
    }

    public List<PreflightCheck> failures() {
        return checks.stream().filter(PreflightCheck::isFailure).toList();
    }

    public List<PreflightCheck> warnings() {
        return checks.stream().filter(c -> c.status() == PreflightCheck.Status.WARN).toList();
    }

    /**
     * A plain-English description of what is about to happen.
     *
     * <p>Deliberately a sentence rather than a table. Someone who has never run a performance test
     * should be able to read this and know whether it is what they meant.
     */
    public String plainEnglishSummary() {
        StringBuilder text = new StringBuilder();
        if (plan.workloadModel() == dev.vortex.core.workload.WorkloadModel.OPEN) {
            text.append("Vortex will send approximately ")
                    .append(plan.peakLevel().displayWithUnit())
                    .append(" for ");
        } else {
            // Phrased as clients rather than traffic on purpose: a concurrency workload does not
            // choose how much traffic arrives, and saying that it does would be the first sentence
            // of a misreading that ends in a wrong capacity number.
            text.append("Vortex will hold ")
                    .append(plan.peakLevel().displayWithUnit())
                    .append(" calling as fast as the service allows, for ");
        }
        text.append(dev.vortex.core.threshold.Durations.display(plan.totalDuration()))
                .append(" against ")
                .append(plan.effectiveTarget().value())
                .append(".\n\n");

        if (plan.operations().size() > 1) {
            text.append("Operation mix: ");
            text.append(plan.operations().stream()
                    .map(operation -> operation.sharePercent() + "% " + operation.name()
                            + operation.arrivalRateIfPresent().map(r -> " (" + r.display() + "/sec)").orElse(""))
                    .reduce((a, b) -> a + ", " + b)
                    .orElse(""));
            text.append(".\n\n");
        }

        text.append("This is an ").append(plan.classification().label().toLowerCase(java.util.Locale.ROOT))
                .append(". ").append(plan.classification().caveat()).append("\n\n");

        if (plan.thresholds().isEmpty()) {
            text.append("No objectives are configured, so this run cannot pass or fail.");
        } else {
            text.append("The test will fail if:\n");
            plan.thresholds().thresholds().forEach(threshold ->
                    text.append("  • ").append(threshold.describe()).append(" is not met\n"));
        }

        return text.toString().trim();
    }
}
