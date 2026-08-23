package com.acltabontabon.vortex.core.plan;

import com.acltabontabon.vortex.core.environment.DependencyMode;
import com.acltabontabon.vortex.core.environment.EnvironmentType;
import com.acltabontabon.vortex.core.environment.TargetUrl;
import com.acltabontabon.vortex.core.environment.TestClassification;
import com.acltabontabon.vortex.core.intent.TestIntent;
import com.acltabontabon.vortex.core.target.ExecutionTarget;
import com.acltabontabon.vortex.core.target.ExternalEndpointTarget;
import com.acltabontabon.vortex.core.validity.ValidityPolicy;
import com.acltabontabon.vortex.core.workload.TestType;
import com.acltabontabon.vortex.core.workload.WorkloadSource;
import com.acltabontabon.vortex.core.shared.LoadLevel;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.shared.TestPlanId;
import com.acltabontabon.vortex.core.threshold.ThresholdSet;
import com.acltabontabon.vortex.core.workload.Stage;
import com.acltabontabon.vortex.core.workload.WorkloadModel;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A fully resolved, immutable description of exactly what will be executed.
 *
 * <p>This is the fundamental reproducibility unit in Vortex, and the difference between it and
 * {@code ProjectConfiguration} is deliberate and load-bearing:
 *
 * <table border="1">
 *   <caption>Configuration versus plan</caption>
 *   <tr><th>{@code vortex.yaml}</th><th>Effective test plan</th></tr>
 *   <tr><td>what the user asked for</td><td>what will actually happen</td></tr>
 *   <tr><td>edited freely, lives in git</td><td>immutable, snapshotted per execution</td></tr>
 *   <tr><td>"production-peak, 120/sec, 15/25/55/5 mix"</td><td>"18/sec createOrder, 30/sec getOrder,
 *       66/sec getOrderStatus, 6/sec cancelOrder, against http://localhost:8080, local k6 binary,
 *       isolated"</td></tr>
 * </table>
 *
 * <p>Resolution never writes back into the user's configuration. The preflight screen is a
 * human-readable rendering of this object, and the same object is written to {@code plan.json}
 * beside the results — so a report from any point in the past describes the test that ran, not the
 * test today's configuration would produce.
 *
 * <p>Secret values are never present. Header values carry references such as
 * {@code ${VORTEX_AUTH_TOKEN}}, which the engine resolves at process launch and nowhere else.
 *
 * <p>{@code k6Options} <em>is</em> fingerprinted, unlike the runner or the safety confirmations. An
 * override changes what the load generator actually does, so two runs that differ by one are not the
 * same experiment and must not be compared as though they were.
 */
public record EffectiveTestPlan(
        TestPlanId id,
        ProjectId projectId,
        String projectName,
        String serviceVersion,
        TestIntent intent,
        String workloadName,
        String workloadDescription,
        TestType testType,
        WorkloadModel workloadModel,
        LoadLevel peakLevel,
        List<Stage> stages,
        List<PlannedOperation> operations,
        List<PlannedDataset> datasets,
        WorkloadSource workloadSource,
        ThresholdSet thresholds,
        String environmentName,
        EnvironmentType environmentType,
        ExecutionTarget executionTarget,
        TargetUrl configuredTarget,
        TargetUrl effectiveTarget,
        String targetRewriteReason,
        DependencyMode dependencyMode,
        TestClassification classification,
        Map<String, String> headers,
        Map<String, String> k6Options,
        RunnerKind runner,
        ScriptSource scriptSource,
        List<SafetyDecision> safetyDecisions,
        PlanFingerprint fingerprint,
        ValidityPolicy validityPolicy,
        String workspacePath) {

    public EffectiveTestPlan {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(testType, "testType");
        Objects.requireNonNull(workloadModel, "workloadModel");
        Objects.requireNonNull(peakLevel, "peakLevel");
        Objects.requireNonNull(thresholds, "thresholds");
        Objects.requireNonNull(environmentType, "environmentType");
        Objects.requireNonNull(executionTarget, "executionTarget");
        Objects.requireNonNull(dependencyMode, "dependencyMode");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(runner, "runner");
        Objects.requireNonNull(scriptSource, "scriptSource");
        effectiveTarget = effectiveTarget == null ? configuredTarget : effectiveTarget;
        stages = stages == null ? List.of() : List.copyOf(stages);
        operations = operations == null ? List.of() : List.copyOf(operations);
        datasets = datasets == null ? List.of() : List.copyOf(datasets);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        k6Options = k6Options == null ? Map.of() : Map.copyOf(k6Options);
        safetyDecisions = safetyDecisions == null ? List.of() : List.copyOf(safetyDecisions);
        workloadSource = workloadSource == null ? WorkloadSource.manual() : workloadSource;
        // Held beside the objectives, because the numbers that decide whether a conclusion may be
        // stated belong with the numbers that decide whether it passed. Not configurable in this
        // phase, so a plan read from an older document takes the defaults — which are the same
        // values every plan carries today.
        validityPolicy = validityPolicy == null ? ValidityPolicy.defaults() : validityPolicy;
        projectName = projectName == null ? "" : projectName;
        serviceVersion = serviceVersion == null ? "" : serviceVersion;
        workloadName = workloadName == null ? "" : workloadName;
        workloadDescription = workloadDescription == null ? "" : workloadDescription;
        environmentName = environmentName == null ? "" : environmentName;
        targetRewriteReason = targetRewriteReason == null ? "" : targetRewriteReason;
        // Administrative, like projectName — not one of ExperimentIdentity's fingerprint DIMENSIONS.
        // Needed to resolve a Compose file's path against the service's checkout at target-prepare
        // time; a plan built before this field existed, or built without a project workspace on hand,
        // simply carries no workspace path.
        workspacePath = workspacePath == null ? "" : workspacePath;

        long distinctKeys = operations.stream().map(PlannedOperation::k6ScenarioKey).distinct().count();
        if (distinctKeys != operations.size()) {
            throw new IllegalArgumentException(
                    "two operations in this plan share a workload key, which would make their "
                            + "measurements indistinguishable. Keys are assigned by OperationKeys, "
                            + "which guarantees uniqueness.");
        }
    }

    /**
     * A plan that reads no datasets, which is most of them.
     *
     * <p>Present so that the overwhelmingly common case does not have to write {@code List.of()} at
     * a position nobody can count to, and so that adding dataset support did not require editing
     * every construction of a plan that has nothing to do with datasets.
     */
    public EffectiveTestPlan(
            TestPlanId id,
            ProjectId projectId,
            String projectName,
            String serviceVersion,
            TestIntent intent,
            String workloadName,
            String workloadDescription,
            TestType testType,
            WorkloadModel workloadModel,
            LoadLevel peakLevel,
            List<Stage> stages,
            List<PlannedOperation> operations,
            WorkloadSource workloadSource,
            ThresholdSet thresholds,
            String environmentName,
            EnvironmentType environmentType,
            TargetUrl configuredTarget,
            TargetUrl effectiveTarget,
            String targetRewriteReason,
            DependencyMode dependencyMode,
            TestClassification classification,
            Map<String, String> headers,
            Map<String, String> k6Options,
            RunnerKind runner,
            ScriptSource scriptSource,
            List<SafetyDecision> safetyDecisions,
            PlanFingerprint fingerprint) {
        this(id, projectId, projectName, serviceVersion, intent, workloadName, workloadDescription,
                testType, workloadModel, peakLevel, stages, operations, List.of(), workloadSource,
                thresholds, environmentName, environmentType,
                new ExternalEndpointTarget(configuredTarget), configuredTarget, effectiveTarget,
                targetRewriteReason, dependencyMode, classification, headers, k6Options, runner,
                scriptSource, safetyDecisions, fingerprint, ValidityPolicy.defaults(), null);
    }

    /**
     * A plan built before validity policy was part of one.
     *
     * <p>Kept at the previous arity so widening did not mean editing every construction site, and it
     * supplies the defaults rather than null — which is the same value every plan carries in this
     * phase, since the policy is not yet configurable.
     */
    public EffectiveTestPlan(
            TestPlanId id,
            ProjectId projectId,
            String projectName,
            String serviceVersion,
            TestIntent intent,
            String workloadName,
            String workloadDescription,
            TestType testType,
            WorkloadModel workloadModel,
            LoadLevel peakLevel,
            List<Stage> stages,
            List<PlannedOperation> operations,
            List<PlannedDataset> datasets,
            WorkloadSource workloadSource,
            ThresholdSet thresholds,
            String environmentName,
            EnvironmentType environmentType,
            TargetUrl configuredTarget,
            TargetUrl effectiveTarget,
            String targetRewriteReason,
            DependencyMode dependencyMode,
            TestClassification classification,
            Map<String, String> headers,
            Map<String, String> k6Options,
            RunnerKind runner,
            ScriptSource scriptSource,
            List<SafetyDecision> safetyDecisions,
            PlanFingerprint fingerprint) {
        this(id, projectId, projectName, serviceVersion, intent, workloadName, workloadDescription,
                testType, workloadModel, peakLevel, stages, operations, datasets, workloadSource,
                thresholds, environmentName, environmentType,
                new ExternalEndpointTarget(configuredTarget), configuredTarget, effectiveTarget,
                targetRewriteReason, dependencyMode, classification, headers, k6Options, runner,
                scriptSource, safetyDecisions, fingerprint, ValidityPolicy.defaults(), null);
    }

    /** Returns a copy that reads the given datasets. */
    public EffectiveTestPlan withDatasets(List<PlannedDataset> newDatasets) {
        return new EffectiveTestPlan(id, projectId, projectName, serviceVersion, intent, workloadName,
                workloadDescription, testType, workloadModel, peakLevel, stages, operations,
                newDatasets, workloadSource, thresholds, environmentName, environmentType,
                executionTarget, configuredTarget, effectiveTarget, targetRewriteReason,
                dependencyMode, classification, headers, k6Options, runner, scriptSource,
                safetyDecisions, fingerprint, validityPolicy, workspacePath);
    }

    /** The dataset a request value names, or empty when the plan does not carry it. */
    public Optional<PlannedDataset> dataset(com.acltabontabon.vortex.core.data.DatasetRef ref) {
        return datasets.stream().filter(dataset -> dataset.ref().equals(ref)).findFirst();
    }

    /** Whether any operation in this plan needs values produced while it runs. */
    public boolean hasDynamicRequestData() {
        return operations.stream().anyMatch(PlannedOperation::hasDynamicRequestData);
    }

    public Duration totalDuration() {
        return stages.stream().map(Stage::duration).reduce(Duration.ZERO, Duration::plus);
    }

    /** Whether the target the engine will actually call differs from the one the user configured. */
    public boolean targetWasRewritten() {
        return !Objects.equals(configuredTarget, effectiveTarget);
    }

    /** The pre-run address the user configured, absent when this plan's target has none — see
     *  {@link ExecutionTarget}. */
    public Optional<TargetUrl> configuredTargetIfPresent() {
        return Optional.ofNullable(configuredTarget);
    }

    /** The pre-run address the engine will actually call, absent when this plan's target has none. */
    public Optional<TargetUrl> effectiveTargetIfPresent() {
        return Optional.ofNullable(effectiveTarget);
    }

    public Optional<String> serviceVersionIfPresent() {
        return serviceVersion.isBlank() ? Optional.empty() : Optional.of(serviceVersion);
    }

    public boolean isSingleOperation() {
        return operations.size() == 1;
    }

    public Optional<PlannedOperation> operation(OperationId operationId) {
        return operations.stream().filter(o -> o.operationId().equals(operationId)).findFirst();
    }

    /**
     * The authoritative mapping from k6 scenario key back to the operation it drives.
     *
     * <p>Metric attribution reads this rather than interpreting the tag string. k6 scenario keys are
     * sanitised, and sanitising is lossy: two operation ids can produce the same candidate key, which
     * {@code OperationKeys} resolves by suffixing. Recovering an operation by re-sanitising and
     * comparing would attribute one operation's latency to another in exactly the case the suffix
     * exists to prevent.
     *
     * <p>Empty for an imported script, whose workload keys Vortex did not choose and does not
     * understand.
     */
    public Map<String, OperationId> operationsByScenarioKey() {
        if (scriptSource != ScriptSource.GENERATED) {
            return Map.of();
        }
        Map<String, OperationId> byKey = new LinkedHashMap<>();
        for (PlannedOperation operation : operations) {
            byKey.put(operation.k6ScenarioKey(), operation.operationId());
        }
        return Map.copyOf(byKey);
    }

    /**
     * Total requests this plan will attempt, when that is statically predictable.
     *
     * <p>Empty for imported scripts, whose control flow Vortex does not model, and for concurrency
     * workloads, where the request count depends on the latency this run exists to measure.
     */
    public Optional<Long> estimatedRequests() {
        if (scriptSource != ScriptSource.GENERATED || workloadModel != WorkloadModel.OPEN
                || stages.isEmpty()) {
            return Optional.empty();
        }
        double total = 0;
        Double previous = null;
        for (Stage stage : stages) {
            double seconds = stage.duration().toMillis() / 1000.0;
            double end = stage.target().asDouble();
            double start = previous == null ? end : previous;
            total += (start + end) / 2.0 * seconds;
            previous = end;
        }
        return Optional.of(Math.round(total));
    }

    /** Why a request estimate is unavailable, for display next to the omission. */
    public String requestEstimateCaveat() {
        if (scriptSource != ScriptSource.GENERATED) {
            return "Not estimated: this plan runs an imported k6 script, whose request flow Vortex "
                    + "does not model.";
        }
        if (workloadModel != WorkloadModel.OPEN) {
            return "Not estimated: a concurrency workload issues its next request when the previous "
                    + "one returns, so the total depends on the latency this run is measuring.";
        }
        return "Assumes the service keeps up. Requests it is too slow to accept are reported as a "
                + "shortfall against this figure rather than removed from it.";
    }

    /** The operation mix as human-readable shares, for display and for capacity evidence. */
    public List<String> operationMixSummary() {
        return operations.stream()
                .map(operation -> operation.sharePercent() + "% " + operation.name())
                .toList();
    }

    /** The total arrival rate, when this plan drives one. Empty under a concurrency workload. */
    public Optional<RequestsPerSecond> totalArrivalRate() {
        return peakLevel instanceof RequestsPerSecond rate ? Optional.of(rate) : Optional.empty();
    }

    /**
     * The canonical form used for fingerprinting: this plan's experiment identity.
     *
     * <p>Defined by {@link ExperimentIdentity}, which is also what explains a difference between
     * two plans — so the hash and the explanation are derived from one list of conditions and
     * cannot drift apart. Notably absent: the service version. That is release identity, and it is
     * the thing a regression comparison exists to vary.
     */
    public Map<String, Object> canonicalForm() {
        return ExperimentIdentity.canonicalForm(this);
    }

    /** Returns a copy whose fingerprint is computed from the current content. */
    public EffectiveTestPlan withComputedFingerprint() {
        return withFingerprint(ExperimentIdentity.fingerprintOf(this));
    }

    /** Returns a copy carrying the given fingerprint. */
    public EffectiveTestPlan withFingerprint(PlanFingerprint newFingerprint) {
        return new EffectiveTestPlan(id, projectId, projectName, serviceVersion, intent, workloadName,
                workloadDescription, testType, workloadModel, peakLevel, stages, operations, datasets,
                workloadSource, thresholds, environmentName, environmentType, executionTarget,
                configuredTarget, effectiveTarget, targetRewriteReason, dependencyMode, classification,
                headers, k6Options, runner, scriptSource, safetyDecisions, newFingerprint,
                validityPolicy, workspacePath);
    }

    /**
     * Returns a copy naming a different pre-run address than the one recorded at plan resolution.
     *
     * <p>Exists for exactly one caller: {@code ExecutionService.run()} builds the transient,
     * engine-facing plan copy from this once a run's target has actually been resolved (see {@code
     * com.acltabontabon.vortex.core.target.TargetExecutor}), composed with whatever rewrite {@code
     * PerformanceEngine.targetRewriteFor} still requires on top (e.g. k6 running inside a container).
     * That copy is passed to {@code engine.execute(...)} and nowhere else — it is never persisted,
     * and never replaces the plan a {@code TestExecution} was created with. {@code configuredTarget}/
     * {@code effectiveTarget} keep meaning "what was configured" / "what the engine was actually told
     * to call" throughout; this only lets the second of those be corrected once the real runtime
     * address is known.
     */
    public EffectiveTestPlan withTargetAddress(TargetUrl newConfiguredTarget,
            TargetUrl newEffectiveTarget, String newTargetRewriteReason) {
        return new EffectiveTestPlan(id, projectId, projectName, serviceVersion, intent, workloadName,
                workloadDescription, testType, workloadModel, peakLevel, stages, operations, datasets,
                workloadSource, thresholds, environmentName, environmentType, executionTarget,
                newConfiguredTarget, newEffectiveTarget, newTargetRewriteReason, dependencyMode,
                classification, headers, k6Options, runner, scriptSource, safetyDecisions, fingerprint,
                validityPolicy, workspacePath);
    }

    /** Returns a copy testing the named release. */
    public EffectiveTestPlan withServiceVersion(String newServiceVersion) {
        return new EffectiveTestPlan(id, projectId, projectName, newServiceVersion, intent,
                workloadName, workloadDescription, testType, workloadModel, peakLevel, stages,
                operations, datasets, workloadSource, thresholds, environmentName, environmentType,
                executionTarget, configuredTarget, effectiveTarget, targetRewriteReason,
                dependencyMode, classification, headers, k6Options, runner, scriptSource,
                safetyDecisions, fingerprint, validityPolicy, workspacePath);
    }

    /**
     * Whether two plans describe the same experiment.
     *
     * <p>Derived fresh from both plans rather than compared as stored hashes. A plan read back from
     * an execution recorded under an older identity contract carries the fingerprint that was true
     * when it ran; deriving means such a run still compares correctly against a new one.
     */
    public boolean describesSameTestAs(EffectiveTestPlan other) {
        return other != null && ExperimentIdentity.compare(this, other).compatible();
    }
}
