package com.acltabontabon.vortex.core.validity;

import com.acltabontabon.vortex.core.shared.LoadLevel;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A run's validity, and every finding behind it.
 *
 * <p>The grade is derived, never supplied: {@link #of(List)} computes it from the findings, so there
 * is no way to construct a run that claims to be valid while carrying a finding that says otherwise.
 *
 * <h2>Deliberately no isEligibleAsBaseline()</h2>
 * ADR-038 keeps a {@code DEGRADED} run useful as a baseline for the conclusions its degradation does
 * not undermine — refusing every degraded baseline would refuse almost all of them, since partial
 * telemetry is the normal case. A single boolean here would invite callers to treat eligibility as a
 * flat grade check and quietly discard the reason-code table that is the actual authority. Comparison
 * asks {@link #reasons()} and decides which deltas it may conclude on.
 */
public record RunQualityAssessment(RunQuality quality, List<ValidityFinding> findings) {

    public RunQualityAssessment {
        quality = quality == null ? RunQuality.NOT_ASSESSED : quality;
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    /** A run nobody assessed: recorded before this axis existed, or never evaluated. */
    public static RunQualityAssessment notAssessed() {
        return new RunQualityAssessment(RunQuality.NOT_ASSESSED, List.of());
    }

    /** A run assessed and found sound. */
    public static RunQualityAssessment valid() {
        return new RunQualityAssessment(RunQuality.VALID, List.of());
    }

    /**
     * The assessment these findings add up to.
     *
     * <p>No scoring, no weighting, no blended number: the worst effect present decides the grade,
     * and no findings means valid. That is the whole rule.
     */
    public static RunQualityAssessment of(List<ValidityFinding> findings) {
        if (findings == null || findings.isEmpty()) {
            return valid();
        }
        RunQuality grade = findings.stream()
                .map(finding -> finding.effect().grade())
                .reduce(RunQuality.VALID, RunQualityAssessment::worse);
        return new RunQualityAssessment(grade, findings);
    }

    private static RunQuality worse(RunQuality left, RunQuality right) {
        if (left == RunQuality.INVALID || right == RunQuality.INVALID) {
            return RunQuality.INVALID;
        }
        if (left == RunQuality.DEGRADED || right == RunQuality.DEGRADED) {
            return RunQuality.DEGRADED;
        }
        return RunQuality.VALID;
    }

    /**
     * Whether a capacity figure at this level may be quoted.
     *
     * <p>Level-specific, because a generator that fell behind at the top of a ramp says nothing
     * about the levels below it, and withholding those too would discard evidence the run produced.
     */
    public boolean permitsCapacityAt(LoadLevel level) {
        return findings.stream().noneMatch(finding -> finding.withholdsCapacityAt(level));
    }

    /** Whether any capacity claim at all may be quoted from this run. */
    public boolean permitsAnyCapacityClaim() {
        return findings.stream().noneMatch(finding -> finding.effect().withholdsCapacity());
    }

    /**
     * Whether a statement naming a limiting resource may be made.
     *
     * <p>False when telemetry was incomplete: a resource cannot be named as the first to reach its
     * limit when Vortex did not see all of them.
     */
    public boolean permitsLimitingResourceStatement() {
        return !has(ValidityReason.TELEMETRY_INCOMPLETE)
                && !has(ValidityReason.WINDOW_MISALIGNED);
    }

    public Set<ValidityReason> reasons() {
        EnumSet<ValidityReason> reasons = EnumSet.noneOf(ValidityReason.class);
        findings.forEach(finding -> reasons.add(finding.reason()));
        return reasons;
    }

    public boolean has(ValidityReason reason) {
        return findings.stream().anyMatch(finding -> finding.reason() == reason);
    }

    public Optional<ValidityFinding> finding(ValidityReason reason) {
        return findings.stream().filter(f -> f.reason() == reason).findFirst();
    }

    /** Every finding's sentence, for the qualifications a report prints beside its conclusions. */
    public List<String> qualifications() {
        return findings.stream().map(ValidityFinding::statement).toList();
    }

    public boolean isValid() {
        return quality == RunQuality.VALID;
    }

    public boolean isInvalid() {
        return quality == RunQuality.INVALID;
    }
}
