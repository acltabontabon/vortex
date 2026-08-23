package dev.vortex.core.capacity;

import dev.vortex.core.analysis.EvidenceStrength;
import dev.vortex.core.environment.DependencyMode;
import dev.vortex.core.environment.TestClassification;
import dev.vortex.core.plan.PlanFingerprint;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.shared.LoadLevel;
import dev.vortex.core.shared.ProjectId;
import dev.vortex.core.workload.WorkloadModel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Evidence that a service met its objectives at a particular level of load, under stated conditions.
 *
 * <h2>Why this is not called a baseline</h2>
 * A "capacity baseline" sounds like a property of the service — a number you look up. It is not.
 * Tested capacity changes with the version, the configuration, the infrastructure, the dependencies,
 * the operation mix, the data shape and the size of the database. Calling it an observation keeps it
 * honest: this is something that was measured, once, under conditions that are recorded alongside
 * it, and it accumulates as history rather than being overwritten.
 *
 * <p>This is also why "baseline" is not one of Vortex's test types. A baseline is not a kind of
 * workload you run; it is the run you later choose to compare against.
 *
 * <p>Every field here exists so the number cannot be quoted without its conditions. That is
 * deliberate. "118 requests/sec" on a slide, detached from the environment and operation mix that
 * produced it, is how a performance test stops being evidence and becomes a rumour.
 *
 * @param projectId        the service
 * @param executionId      the run that produced this evidence
 * @param serviceVersion   the version under test
 * @param compliantLevel   the highest tested level at which every objective was met
 * @param workloadModel    whether that level was an arrival rate or a virtual-user count
 * @param environmentName  the environment it was measured in
 * @param classification   whether that environment could answer integrated questions
 * @param dependencyMode   whether dependencies were real
 * @param operationMix     the operation composition, as human-readable shares
 * @param workloadName     which workload produced it
 * @param thresholdSummary the objectives it was measured against
 * @param duration         how long the compliant load was sustained
 * @param planFingerprint  fingerprint of the plan, so an equivalent run can be identified later
 * @param observedAt       when it was measured (UTC)
 * @param firstNonCompliant the lowest tested level at which an objective was not met, and what the
 *                         service looked like there; null when nothing was violated. The other half
 *                         of the same evidence: "it sustained 400" invites "and what happened at
 *                         450?", and a report that cannot answer has kept the more quotable half
 * @param boundaryStatus   whether the two edges actually form a boundary. A run whose compliance did
 *                         not move monotonically with load has not established one, however
 *                         confidently its highest passing level could be quoted
 * @param boundaryStrength how well the <em>boundary</em> is established — deliberately not named
 *                         {@code strength}, because it says nothing about any constraint candidate
 *                         below it, and a renderer putting "HIGH" beside a CPU figure would invite
 *                         exactly that reading
 * @param constraintCandidates resources close to their limit where the boundary was found. Stored
 *                         with the observation rather than recomputed from a report, so history and
 *                         report cannot drift apart. Correlated, never causal
 */
public record CapacityObservation(
        ProjectId projectId,
        ExecutionId executionId,
        String serviceVersion,
        LoadLevel compliantLevel,
        WorkloadModel workloadModel,
        String environmentName,
        TestClassification classification,
        DependencyMode dependencyMode,
        List<String> operationMix,
        String workloadName,
        List<String> thresholdSummary,
        Duration duration,
        PlanFingerprint planFingerprint,
        Instant observedAt,
        BoundaryEdge firstNonCompliant,
        BoundaryStatus boundaryStatus,
        EvidenceStrength boundaryStrength,
        List<ConstraintCandidate> constraintCandidates,
        SustainableCapacity sustainable) {

    public CapacityObservation {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(compliantLevel, "compliantLevel");
        Objects.requireNonNull(workloadModel, "workloadModel");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(dependencyMode, "dependencyMode");
        Objects.requireNonNull(observedAt, "observedAt");
        serviceVersion = serviceVersion == null ? "" : serviceVersion;
        environmentName = environmentName == null ? "" : environmentName;
        workloadName = workloadName == null ? "" : workloadName;
        operationMix = operationMix == null ? List.of() : List.copyOf(operationMix);
        thresholdSummary = thresholdSummary == null ? List.of() : List.copyOf(thresholdSummary);
        duration = duration == null ? Duration.ZERO : duration;
        boundaryStatus = boundaryStatus == null ? BoundaryStatus.NOT_EVALUATED : boundaryStatus;
        boundaryStrength = boundaryStrength == null ? EvidenceStrength.INSUFFICIENT : boundaryStrength;
        constraintCandidates = constraintCandidates == null
                ? List.of() : List.copyOf(constraintCandidates);
        // Not evaluated, rather than not established. An observation recorded before the five
        // conditions existed did not fail them; nobody checked, and headroom must be able to tell
        // those apart when it reads a historical row.
        sustainable = sustainable == null ? SustainableCapacity.notEvaluated() : sustainable;
    }

    /** An observation from before sustainable capacity was computed. */
    public CapacityObservation(ProjectId projectId, ExecutionId executionId, String serviceVersion,
            LoadLevel compliantLevel, WorkloadModel workloadModel, String environmentName,
            TestClassification classification, DependencyMode dependencyMode,
            List<String> operationMix, String workloadName, List<String> thresholdSummary,
            Duration duration, PlanFingerprint planFingerprint, Instant observedAt,
            BoundaryEdge firstNonCompliant, BoundaryStatus boundaryStatus,
            EvidenceStrength boundaryStrength, List<ConstraintCandidate> constraintCandidates) {
        this(projectId, executionId, serviceVersion, compliantLevel, workloadModel, environmentName,
                classification, dependencyMode, operationMix, workloadName, thresholdSummary,
                duration, planFingerprint, observedAt, firstNonCompliant, boundaryStatus,
                boundaryStrength, constraintCandidates, SustainableCapacity.notEvaluated());
    }

    /**
     * An observation that recorded only the compliant edge.
     *
     * <p>Kept so widening the record did not mean editing every caller and fixture that predates the
     * far edge being recorded at all.
     */
    public CapacityObservation(ProjectId projectId, ExecutionId executionId, String serviceVersion,
            LoadLevel compliantLevel, WorkloadModel workloadModel, String environmentName,
            TestClassification classification, DependencyMode dependencyMode,
            List<String> operationMix, String workloadName, List<String> thresholdSummary,
            Duration duration, PlanFingerprint planFingerprint, Instant observedAt) {
        this(projectId, executionId, serviceVersion, compliantLevel, workloadModel, environmentName,
                classification, dependencyMode, operationMix, workloadName, thresholdSummary,
                duration, planFingerprint, observedAt, null, BoundaryStatus.NOT_EVALUATED,
                EvidenceStrength.INSUFFICIENT, List.of(), SustainableCapacity.notEvaluated());
    }

    /**
     * The label to use wherever this number appears.
     *
     * <p>Never "capacity" or "maximum throughput" on its own — those imply a guarantee that a test
     * cannot give.
     */
    public String label() {
        return "Tested SLO-compliant capacity";
    }

    /** The label for the failing edge. Fixed wording, used by every renderer. */
    public static final String FAILING_EDGE_LABEL = "First observed non-compliant load";

    /** The label for the pair. Fixed wording, used by every renderer. */
    public static final String BOUNDARY_LABEL = "Tested capacity boundary";

    /** Instance accessors for the fixed labels, so a template needs no static reference. */
    public String boundaryLabel() {
        return BOUNDARY_LABEL;
    }

    public String failingEdgeLabel() {
        return FAILING_EDGE_LABEL;
    }

    public Optional<BoundaryEdge> firstNonCompliantIfPresent() {
        return Optional.ofNullable(firstNonCompliant);
    }

    /**
     * Both edges in one statement, or an explanation of why there is only one.
     *
     * <p>Rendered here rather than in each template so the vocabulary cannot drift: nothing anywhere
     * should imply Vortex found the service's absolute maximum, and the surest way to keep that true
     * is for there to be one sentence to keep true.
     */
    public String boundary() {
        return switch (boundaryStatus) {
            case ESTABLISHED -> compliantLevel.displayWithUnit() + " compliant → "
                    + firstNonCompliant.display() + " non-compliant";
            case FAR_EDGE_NOT_REACHED -> compliantLevel.displayWithUnit()
                    + " compliant; no tested level failed, so the boundary is above what this run "
                    + "reached";
            case UNSTABLE -> "A stable tested capacity boundary was not established by this run: "
                    + "compliance did not move consistently with load.";
            case NOT_EVALUATED -> "A tested capacity boundary was not established by this run: "
                    + "its objectives were not evaluated.";
        };
    }

    /** Whether the compliant level may be quoted as a tested capacity figure. */
    public boolean isQuotable() {
        return boundaryStatus.isQuotable();
    }

    /** The conditions that must be displayed with the number. */
    public List<String> conditions() {
        return List.of(
                "Service version: " + (serviceVersion.isBlank() ? "not recorded" : serviceVersion),
                "Environment: " + environmentName + " (" + classification.label() + ")",
                "Dependencies: " + dependencyMode.label(),
                "LoadShape model: " + workloadModel.label(),
                "Operation mix: " + (operationMix.isEmpty() ? "not recorded" : String.join(", ", operationMix)),
                "Workload: " + workloadName,
                "Objectives: " + (thresholdSummary.isEmpty() ? "none" : String.join(", ", thresholdSummary)),
                "Sustained for: " + dev.vortex.core.threshold.Durations.display(duration));
    }

    /** Whether this observation can be compared with production traffic. */
    public boolean supportsHeadroom() {
        return classification == TestClassification.INTEGRATED;
    }
}
