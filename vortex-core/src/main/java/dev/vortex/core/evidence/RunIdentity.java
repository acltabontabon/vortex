package dev.vortex.core.evidence;

import dev.vortex.core.environment.DependencyMode;
import dev.vortex.core.environment.EnvironmentType;
import dev.vortex.core.environment.TestClassification;
import dev.vortex.core.plan.PlanFingerprint;
import dev.vortex.core.workload.TestType;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.shared.ProjectId;
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
