package dev.vortex.core.execution;

import dev.vortex.core.analysis.DeterministicSummary;
import dev.vortex.core.metrics.MeasuredResults;
import dev.vortex.core.validity.RunQualityAssessment;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.ToolVersions;
import dev.vortex.core.resource.ResolvedLoadGeneratorBudget;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.shared.ProjectId;
import dev.vortex.core.target.ResolvedTarget;
import dev.vortex.core.threshold.Verdict;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One run of a performance test, from request through to verdict.
 *
 * <p>An execution owns the plan it ran, what was measured, what the versions of everything were,
 * and the deterministic conclusion. It does not own any AI interpretation: those are separate
 * {@code dev.vortex.core.analysis.Analysis} records pointing back at this one.
 *
 * <p>Once {@link ExecutionState#COMPLETED}, the measurements are immutable. They can be re-read,
 * re-reported and re-analysed, but never revised.
 *
 * @param id           stable identifier, also the artifact directory name
 * @param projectId    the project this run belongs to
 * @param plan         the immutable resolved plan that was executed
 * @param state        lifecycle state
 * @param requestedAt  when the run was requested (UTC)
 * @param startedAt    when traffic started, when it did
 * @param finishedAt   when the run reached a terminal state
 * @param results      normalised measurements, present once collected
 * @param summary      the deterministic conclusion, present once evaluated
 * @param toolVersions what carried out the run
 * @param artifacts    references to raw evidence on disk
 * @param failureReason why the run did not complete, when applicable
 * @param failureDetail diagnostic detail preserved for troubleshooting
 * @param resolvedTarget the runtime fact produced once this run's target has been prepared, absent
 *                       until then
 * @param resolvedLoadGeneratorBudget the load generator's resource budget, resolved once this run's
 *                       target ownership is known (so an automatic budget can reason about a
 *                       colocated system under test), absent until then. Recorded for the same reason
 *                       {@code resolvedTarget} is: a later Settings change must never be mistaken for
 *                       what a specific run actually used.
 */
public record TestExecution(
        ExecutionId id,
        ProjectId projectId,
        EffectiveTestPlan plan,
        ExecutionState state,
        Instant requestedAt,
        Instant startedAt,
        Instant finishedAt,
        MeasuredResults results,
        DeterministicSummary summary,
        ToolVersions toolVersions,
        ExecutionArtifacts artifacts,
        FailureReason failureReason,
        String failureDetail,
        RunQualityAssessment quality,
        ResolvedTarget resolvedTarget,
        ResolvedLoadGeneratorBudget resolvedLoadGeneratorBudget) {

    public TestExecution {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(requestedAt, "requestedAt");
        toolVersions = toolVersions == null ? ToolVersions.unknown() : toolVersions;
        artifacts = artifacts == null ? ExecutionArtifacts.empty() : artifacts;
        failureDetail = failureDetail == null ? "" : failureDetail;
        // Not assessed, rather than valid. A run recorded before this axis existed was never
        // graded, and defaulting it to VALID would manufacture a judgement nobody made — on
        // precisely the runs whose evidence Vortex has the least of.
        quality = quality == null ? RunQualityAssessment.notAssessed() : quality;
    }

    /** An execution built before validity was assessed at all. */
    public TestExecution(ExecutionId id, ProjectId projectId, EffectiveTestPlan plan,
            ExecutionState state, Instant requestedAt, Instant startedAt, Instant finishedAt,
            MeasuredResults results, DeterministicSummary summary, ToolVersions toolVersions,
            ExecutionArtifacts artifacts, FailureReason failureReason, String failureDetail) {
        this(id, projectId, plan, state, requestedAt, startedAt, finishedAt, results, summary,
                toolVersions, artifacts, failureReason, failureDetail,
                RunQualityAssessment.notAssessed(), null, null);
    }

    public static TestExecution create(ExecutionId id, EffectiveTestPlan plan, Instant now) {
        return new TestExecution(id, plan.projectId(), plan, ExecutionState.CREATED, now, null, null,
                null, null, ToolVersions.unknown(), ExecutionArtifacts.empty(), null, "");
    }

    /**
     * Moves to a new state, rejecting transitions the lifecycle does not permit.
     *
     * @throws IllegalStateException when the transition is invalid
     */
    public TestExecution transitionTo(ExecutionState next, Instant now) {
        state.requireTransitionTo(next);
        Instant newStartedAt = next == ExecutionState.RUNNING && startedAt == null ? now : startedAt;
        Instant newFinishedAt = next.isTerminal() ? now : finishedAt;
        return new TestExecution(id, projectId, plan, next, requestedAt, newStartedAt, newFinishedAt,
                results, summary, toolVersions, artifacts, failureReason, failureDetail, quality,
                resolvedTarget, resolvedLoadGeneratorBudget);
    }

    public TestExecution withResults(MeasuredResults newResults) {
        return new TestExecution(id, projectId, plan, state, requestedAt, startedAt, finishedAt,
                newResults, summary, toolVersions, artifacts, failureReason, failureDetail, quality,
                resolvedTarget, resolvedLoadGeneratorBudget);
    }

    public TestExecution withSummary(DeterministicSummary newSummary) {
        return new TestExecution(id, projectId, plan, state, requestedAt, startedAt, finishedAt,
                results, newSummary, toolVersions, artifacts, failureReason, failureDetail, quality,
                resolvedTarget, resolvedLoadGeneratorBudget);
    }

    public TestExecution withToolVersions(ToolVersions newVersions) {
        return new TestExecution(id, projectId, plan, state, requestedAt, startedAt, finishedAt,
                results, summary, newVersions, artifacts, failureReason, failureDetail, quality,
                resolvedTarget, resolvedLoadGeneratorBudget);
    }

    /**
     * Records whether this experiment was carried out as specified.
     *
     * <p>Held on the execution rather than on the summary, because it grades the <em>run</em>
     * including its terminal state — and a cancelled run has no summary to hang it from, while
     * {@code EXECUTION_INTERRUPTED} is exactly the code that has to fire for one.
     */
    public TestExecution withQuality(RunQualityAssessment newQuality) {
        return new TestExecution(id, projectId, plan, state, requestedAt, startedAt, finishedAt,
                results, summary, toolVersions, artifacts, failureReason, failureDetail, newQuality,
                resolvedTarget, resolvedLoadGeneratorBudget);
    }

    public TestExecution withArtifacts(ExecutionArtifacts newArtifacts) {
        return new TestExecution(id, projectId, plan, state, requestedAt, startedAt, finishedAt,
                results, summary, toolVersions, newArtifacts, failureReason, failureDetail, quality,
                resolvedTarget, resolvedLoadGeneratorBudget);
    }

    /** Records the runtime fact produced once this run's target has been prepared. */
    public TestExecution withResolvedTarget(ResolvedTarget newResolvedTarget) {
        return new TestExecution(id, projectId, plan, state, requestedAt, startedAt, finishedAt,
                results, summary, toolVersions, artifacts, failureReason, failureDetail, quality,
                newResolvedTarget, resolvedLoadGeneratorBudget);
    }

    /** Records the load generator's resource budget, resolved once this run's target ownership is
     *  known. */
    public TestExecution withResolvedLoadGeneratorBudget(
            ResolvedLoadGeneratorBudget newResolvedLoadGeneratorBudget) {
        return new TestExecution(id, projectId, plan, state, requestedAt, startedAt, finishedAt,
                results, summary, toolVersions, artifacts, failureReason, failureDetail, quality,
                resolvedTarget, newResolvedLoadGeneratorBudget);
    }

    public TestExecution failed(FailureReason reason, String detail, Instant now) {
        state.requireTransitionTo(ExecutionState.FAILED);
        return new TestExecution(id, projectId, plan, ExecutionState.FAILED, requestedAt, startedAt,
                now, results, summary, toolVersions, artifacts, reason, detail, quality,
                resolvedTarget, resolvedLoadGeneratorBudget);
    }

    public TestExecution cancelled(Instant now) {
        state.requireTransitionTo(ExecutionState.CANCELLED);
        return new TestExecution(id, projectId, plan, ExecutionState.CANCELLED, requestedAt, startedAt,
                now, results, summary, toolVersions, artifacts, null, "Cancelled by user", quality,
                resolvedTarget, resolvedLoadGeneratorBudget);
    }

    public Optional<MeasuredResults> resultsIfPresent() {
        return Optional.ofNullable(results);
    }

    public Optional<DeterministicSummary> summaryIfPresent() {
        return Optional.ofNullable(summary);
    }

    public Optional<FailureReason> failureReasonIfPresent() {
        return Optional.ofNullable(failureReason);
    }

    public Optional<ResolvedTarget> resolvedTargetIfPresent() {
        return Optional.ofNullable(resolvedTarget);
    }

    public Optional<ResolvedLoadGeneratorBudget> resolvedLoadGeneratorBudgetIfPresent() {
        return Optional.ofNullable(resolvedLoadGeneratorBudget);
    }

    public Optional<Duration> duration() {
        if (startedAt == null || finishedAt == null) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(startedAt, finishedAt));
    }

    /** The deterministic verdict, or {@link Verdict#NOT_EVALUATED} when the run produced none. */
    public Verdict verdict() {
        return summary == null ? Verdict.NOT_EVALUATED : summary.verdict();
    }

    public boolean isTerminal() {
        return state.isTerminal();
    }
}
