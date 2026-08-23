package dev.vortex.core.safety;

import java.util.Objects;
import java.util.Optional;

/**
 * Something about a planned run that the user should know before it starts.
 *
 * <p>A finding says what was noticed, why it matters, and — when confirmation is required — what
 * exactly the user is agreeing to. Vortex avoids the generic "Are you sure?", which trains people
 * to click through warnings without reading them. Where the consequences are real, the confirmation
 * names the thing being risked and requires it to be typed.
 *
 * @param policyId    which policy raised this, e.g. {@code target.non-local}
 * @param severity    how serious it is
 * @param title       one line describing what was noticed
 * @param detail      why it matters and what the consequences could be
 * @param challenge   the exact text the user must type to proceed, when required
 */
public record SafetyFinding(
        String policyId,
        SafetySeverity severity,
        String title,
        String detail,
        String challenge) {

    public SafetyFinding {
        Objects.requireNonNull(severity, "severity");
        if (policyId == null || policyId.isBlank()) {
            throw new IllegalArgumentException("a safety finding must name the policy that raised it");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("a safety finding must have a title");
        }
        detail = detail == null ? "" : detail;
        challenge = challenge == null ? "" : challenge;
    }

    public static SafetyFinding info(String policyId, String title, String detail) {
        return new SafetyFinding(policyId, SafetySeverity.INFO, title, detail, "");
    }

    public static SafetyFinding warning(String policyId, String title, String detail) {
        return new SafetyFinding(policyId, SafetySeverity.WARNING, title, detail, "");
    }

    public static SafetyFinding blocking(String policyId, String title, String detail) {
        return new SafetyFinding(policyId, SafetySeverity.BLOCKING, title, detail, "");
    }

    /** A warning that can only be passed by typing the named value. */
    public static SafetyFinding challenge(String policyId, String title, String detail, String challenge) {
        return new SafetyFinding(policyId, SafetySeverity.WARNING, title, detail, challenge);
    }

    public boolean requiresTypedConfirmation() {
        return !challenge.isBlank();
    }

    public Optional<String> challengeIfPresent() {
        return challenge.isBlank() ? Optional.empty() : Optional.of(challenge);
    }

    public boolean isBlocking() {
        return severity == SafetySeverity.BLOCKING;
    }
}
