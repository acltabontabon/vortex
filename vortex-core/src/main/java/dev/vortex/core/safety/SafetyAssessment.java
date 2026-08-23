package dev.vortex.core.safety;

import java.util.List;

/** Everything the safety policies noticed about a planned run. */
public record SafetyAssessment(List<SafetyFinding> findings) {

    public SafetyAssessment {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static SafetyAssessment clear() {
        return new SafetyAssessment(List.of());
    }

    public boolean isBlocked() {
        return findings.stream().anyMatch(SafetyFinding::isBlocking);
    }

    public boolean requiresConfirmation() {
        return findings.stream().anyMatch(f -> f.severity() == SafetySeverity.WARNING);
    }

    public List<SafetyFinding> blocking() {
        return findings.stream().filter(SafetyFinding::isBlocking).toList();
    }

    public List<SafetyFinding> warnings() {
        return findings.stream().filter(f -> f.severity() == SafetySeverity.WARNING).toList();
    }

    public List<SafetyFinding> notes() {
        return findings.stream().filter(f -> f.severity() == SafetySeverity.INFO).toList();
    }

    /** The typed confirmations the user must supply, in order. */
    public List<String> requiredChallenges() {
        return findings.stream()
                .filter(SafetyFinding::requiresTypedConfirmation)
                .map(SafetyFinding::challenge)
                .distinct()
                .toList();
    }
}
