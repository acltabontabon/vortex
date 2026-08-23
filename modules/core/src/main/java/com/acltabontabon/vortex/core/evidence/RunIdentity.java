package com.acltabontabon.vortex.core.evidence;

import com.acltabontabon.vortex.core.environment.DependencyMode;
import com.acltabontabon.vortex.core.environment.EnvironmentType;
import com.acltabontabon.vortex.core.environment.TestClassification;
import com.acltabontabon.vortex.core.plan.PlanFingerprint;
import com.acltabontabon.vortex.core.workload.TestType;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.shared.ProjectId;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What was tested, when, and under which conditions the numbers hold.
 *
 * <p>Every field here is a qualifier on the measurements rather than a measurement. They travel
 * together because a throughput figure separated from its environment, its classification and its
 * dependency mode is a rumour: "118 requests/sec" says nothing until it also says that it was
 * against a local service with mocked dependencies.
 *
 * @param serviceVersion which release was under test; empty when the project never recorded one,
 *                       which is stated in the report rather than hidden
 * @param fingerprint    the hash of the resolved plan, so two runs can be recognised as the same
 *                       experiment
 * @param targetKind     {@code EXTERNAL_ENDPOINT}, {@code DOCKER_IMAGE} or {@code DOCKER_COMPOSE} —
 *                       the declared target's own kind, from the plan; always known, since a
 *                       historical plan predating this feature normalizes to {@code
 *                       EXTERNAL_ENDPOINT} rather than leaving this absent
 * @param targetSummary  the declared target's own summary, e.g. {@code "Docker:
 *                       payment-service:1.4.2"} — always known, for the same reason as {@code
 *                       targetKind}
 * @param targetOwnershipLabel "Vortex managed" or "Externally managed" — the run's actual resolved
 *                       ownership where one was recorded, falling back to the declared target's own
 *                       ownership for a run that never reached a resolved target (a historical run,
 *                       or one that failed before target preparation completed)
 * @param resourceSummary the run's confirmed resource envelope, e.g. {@code "0.5 CPU · 512 MiB"} —
 *                       empty whenever no envelope was confirmed (an external endpoint, a Compose
 *                       target, or any run with no resolved target at all), never fabricated
 */
public record RunIdentity(
        ExecutionId executionId,
        ProjectId projectId,
        String serviceName,
        String serviceVersion,
        String workloadName,
        String workloadDescription,
        TestType testType,
        String environmentName,
        EnvironmentType environmentType,
        TestClassification classification,
        DependencyMode dependencyMode,
        String targetUrl,
        String targetRewriteReason,
        String targetKind,
        String targetSummary,
        String targetOwnershipLabel,
        String resourceSummary,
        PlanFingerprint fingerprint,
        Instant requestedAt,
        Instant startedAt,
        Instant finishedAt,
        Duration duration) {

    public RunIdentity {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(testType, "testType");
        Objects.requireNonNull(classification, "classification");
        serviceName = blankToEmpty(serviceName);
        serviceVersion = blankToEmpty(serviceVersion);
        workloadName = blankToEmpty(workloadName);
        workloadDescription = blankToEmpty(workloadDescription);
        environmentName = blankToEmpty(environmentName);
        targetUrl = blankToEmpty(targetUrl);
        targetRewriteReason = blankToEmpty(targetRewriteReason);
        targetKind = blankToEmpty(targetKind);
        targetSummary = blankToEmpty(targetSummary);
        targetOwnershipLabel = blankToEmpty(targetOwnershipLabel);
        resourceSummary = blankToEmpty(resourceSummary);
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Whether the release under test is known.
     *
     * <p>When it is not, a report can still be read but cannot be attributed to a change — which is
     * worth saying out loud rather than leaving as a blank line.
     */
    public Optional<String> serviceVersionIfPresent() {
        return serviceVersion.isEmpty() ? Optional.empty() : Optional.of(serviceVersion);
    }

    public Optional<Duration> durationIfPresent() {
        return Optional.ofNullable(duration);
    }

    public boolean targetWasRewritten() {
        return !targetRewriteReason.isEmpty();
    }

    public Optional<String> resourceSummaryIfPresent() {
        return resourceSummary.isEmpty() ? Optional.empty() : Optional.of(resourceSummary);
    }

    /** A one-line identification, used in report headers and export filenames. */
    public String describe() {
        StringBuilder description = new StringBuilder(serviceName.isEmpty() ? "service" : serviceName);
        serviceVersionIfPresent().ifPresent(version -> description.append(" ").append(version));
        if (!workloadName.isEmpty()) {
            description.append(" · ").append(workloadName);
        }
        if (!environmentName.isEmpty()) {
            description.append(" · ").append(environmentName);
        }
        return description.toString();
    }

    /** The short run reference a reader quotes back, e.g. {@code a1b2c3d4}. */
    public String shortId() {
        String value = executionId.value();
        return value.length() <= 8 ? value : value.substring(0, 8);
    }
}
