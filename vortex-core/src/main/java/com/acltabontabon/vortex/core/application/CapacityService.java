package com.acltabontabon.vortex.core.application;

import com.acltabontabon.vortex.core.analysis.StageObservation;
import com.acltabontabon.vortex.core.analysis.EvidenceStrength;
import com.acltabontabon.vortex.core.analysis.ResourcePressure;
import com.acltabontabon.vortex.core.capacity.BoundaryEdge;
import com.acltabontabon.vortex.core.capacity.BoundaryStatus;
import com.acltabontabon.vortex.core.capacity.CapacityObservation;
import com.acltabontabon.vortex.core.capacity.ConstraintCandidate;
import com.acltabontabon.vortex.core.capacity.HeadroomCalculator;
import com.acltabontabon.vortex.core.capacity.ProductionObservation;
import com.acltabontabon.vortex.core.capacity.SustainableCapacityCalculator;
import com.acltabontabon.vortex.core.environment.TestClassification;
import com.acltabontabon.vortex.core.execution.ExecutionState;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.port.Clock;
import com.acltabontabon.vortex.core.port.Repositories.CapacityObservationRepository;
import com.acltabontabon.vortex.core.shared.LoadLevel;
import com.acltabontabon.vortex.core.shared.ProjectId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Records what traffic a service was shown to sustain, under exactly which conditions.
 *
 * <p>Capacity is never stored as a bare number. Every observation carries the version, environment,
 * classification, dependency mode, operation mix, objectives and duration that produced it, because
 * those conditions are what make the figure mean anything. "118 requests/sec" quoted without them
 * is how a measurement turns into folklore.
 *
 * <p>Observations accumulate as history rather than overwriting a stored capacity, since tested
 * capacity moves with the version, the configuration, the infrastructure and the size of the data.
 */
public final class CapacityService {

    private final CapacityObservationRepository observations;
    private final HeadroomCalculator headroomCalculator;

    /**
     * The five-condition capacity, computed once and stored with the observation.
     *
     * <p>A pure calculator, constructed here for the same reason the validity assessor is: there is
     * one definition of sustainable, and a second implementation of it would eventually disagree
     * with the stored evidence.
     */
    private final SustainableCapacityCalculator sustainableCapacity =
            new SustainableCapacityCalculator();
    private final Clock clock;

    public CapacityService(CapacityObservationRepository observations,
            HeadroomCalculator headroomCalculator, Clock clock) {
        this.observations = Objects.requireNonNull(observations, "observations");
        this.headroomCalculator = Objects.requireNonNull(headroomCalculator, "headroomCalculator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Records capacity evidence from a completed run, when it established any.
     *
     * <p>A run only establishes capacity if it actually met its objectives at some level. A test
     * that violated its thresholds everywhere has demonstrated a limit, not a capacity.
     */
    public Optional<CapacityObservation> recordFrom(TestExecution execution,
            List<StageObservation> stages) {

        if (execution.state() != ExecutionState.COMPLETED || execution.summaryIfPresent().isEmpty()) {
            return Optional.empty();
        }

        // A run whose objectives could not be evaluated has not demonstrated a capacity. It may
        // have sustained the load comfortably, but nobody checked — and "unevaluated is not
        // passed" has to hold here too, or the one number most likely to be quoted out of context
        // becomes the one with the least evidence behind it. A FAIL is different: a ramp that
        // eventually breached its objectives still established what it sustained before it did.
        if (execution.summary().verdict() == com.acltabontabon.vortex.core.threshold.Verdict.NOT_EVALUATED) {
            return Optional.empty();
        }

        Optional<LoadLevel> compliantLevel = highestCompliantLevel(execution, stages);
        if (compliantLevel.isEmpty()) {
            return Optional.empty();
        }

        // A capacity observation is history: it is compared against later runs and quoted months
        // afterwards. Recording one from a run that did not measure what it claims to would put a
        // number nobody can defend into the record permanently — so it is not recorded, while
        // everything the run did measure is stored and rendered exactly as any other run's is.
        if (!execution.quality().permitsCapacityAt(compliantLevel.get())) {
            return Optional.empty();
        }

        EffectiveTestPlan plan = execution.plan();
        BoundaryStatus status = BoundaryStatus.of(stages);
        StageObservation failing = firstNonCompliantStage(stages);

        CapacityObservation observation = new CapacityObservation(
                execution.projectId(),
                execution.id(),
                plan.serviceVersion(),
                compliantLevel.get(),
                plan.workloadModel(),
                plan.environmentName(),
                plan.classification(),
                plan.dependencyMode(),
                plan.operationMixSummary(),
                plan.workloadName(),
                plan.thresholds().thresholds().stream().map(t -> t.describe()).toList(),
                execution.duration().orElse(plan.totalDuration()),
                plan.fingerprint(),
                clock.now(),
                edgeOf(failing),
                status,
                boundaryStrength(execution, status),
                candidatesFrom(failing),
                // The stricter figure, computed here so the stored observation and the report cannot
                // disagree about it later. Frequently lower than the compliant level above, and
                // sometimes absent where that exists — which is the point of having both.
                sustainableCapacity.calculate(plan, stages, execution.quality(),
                        execution.summaryIfPresent()
                                .map(com.acltabontabon.vortex.core.analysis.DeterministicSummary::limits)
                                .orElse(null)));

        return Optional.of(observations.save(observation));
    }

    /**
     * The lowest tested level at which an objective was not met.
     *
     * <p>By load rather than by the order the stages ran in: a workload may ramp down as well as up,
     * and the question is about level.
     */
    private StageObservation firstNonCompliantStage(List<StageObservation> stages) {
        if (stages == null) {
            return null;
        }
        return stages.stream()
                .filter(stage -> !stage.isCompliant())
                .min(java.util.Comparator.comparingDouble(stage -> stage.targetLoad().asDouble()))
                .orElse(null);
    }

    private BoundaryEdge edgeOf(StageObservation stage) {
        return stage == null ? null : new BoundaryEdge(stage.targetLoad(), stage.p95(),
                stage.errorRate(), stage.violatedThresholds(), stage.signals());
    }

    /**
     * How well the boundary itself is established.
     *
     * <p>Taken from the SLO breakpoint the analyzer already computed, which knows how many stages
     * were observed and how densely. A boundary that is not established at all carries no strength:
     * grading the confidence of something Vortex declined to claim would be nonsense.
     */
    private EvidenceStrength boundaryStrength(TestExecution execution, BoundaryStatus status) {
        if (!status.isQuotable()) {
            return EvidenceStrength.INSUFFICIENT;
        }
        return execution.summaryIfPresent()
                .map(summary -> summary.sloBreakpoint())
                .map(breakpoint -> breakpoint.strength())
                .orElse(EvidenceStrength.LOW);
    }

    /**
     * Resources close to their limit where objectives stopped being met.
     *
     * <p>Uses the same rule the finding detector uses, from the one place it lives, so a report and
     * the stored observation cannot name different candidates for the same run.
     */
    private List<ConstraintCandidate> candidatesFrom(StageObservation stage) {
        if (stage == null) {
            return List.of();
        }
        // Only typed signals scoped to the service, measured against a limit somebody declared.
        // Before this, the rule was "the unit is percent and the value is at least ninety", which
        // admitted an error rate of 92% and — once the generator became a measured system — would
        // have admitted Vortex's own CPU as a constraint on the service it was testing.
        return stage.serviceResourceSignals().stream()
                .filter(ResourcePressure::isUnderPressure)
                .map(signal -> new ConstraintCandidate(
                        signal.signalId(),
                        signal.name(),
                        signal.observation().display(),
                        // Crossing at the failing edge is the strongest case this rule allows, and
                        // a stage aligned by computed boundaries still cannot reach it.
                        ResourcePressure.strength(
                                signal.observation().traceIfPresent().isPresent(), true,
                                stage.basis()),
                        stage.basis(),
                        "Observed at " + signal.describe() + " while the workload held "
                                + stage.targetLoad().displayWithUnit() + ".",
                        signal.kind(),
                        signal.scope()))
                .toList();
    }

    /**
     * The highest level at which the run still met every objective.
     *
     * <p>For a ramping workload this comes from stage evidence. For a steady workload it is simply
     * the level that was held, provided the run passed.
     */
    private Optional<LoadLevel> highestCompliantLevel(TestExecution execution,
            List<StageObservation> stages) {

        if (stages != null && !stages.isEmpty()) {
            Optional<LoadLevel> fromStages = stages.stream()
                    .filter(StageObservation::isCompliant)
                    .max(java.util.Comparator.comparingDouble(
                            stage -> stage.targetLoad().asDouble()))
                    .map(StageObservation::targetLoad);
            if (fromStages.isPresent()) {
                return fromStages;
            }
        }

        return execution.summaryIfPresent()
                .filter(summary -> summary.verdict() == com.acltabontabon.vortex.core.threshold.Verdict.PASS)
                .map(_ -> execution.plan().peakLevel());
    }

    public List<CapacityObservation> history(ProjectId projectId) {
        return observations.findByProject(projectId);
    }

    public Optional<CapacityObservation> latest(ProjectId projectId) {
        return observations.findLatest(projectId);
    }

    /**
     * Each test's own most recent capacity observation, keyed by workload name.
     *
     * <p>The per-test sibling of {@link #latest(ProjectId)}'s single service-wide reading — a
     * service's "tested capacity" is not one number, it is one number per test, and a page showing
     * more than one test must not guess which test a service-wide reading belongs to.
     *
     * <p>No new query: {@link #history(ProjectId)} already returns every observation newest first,
     * so keeping the first occurrence per workload name is enough.
     */
    public Map<String, CapacityObservation> latestPerWorkload(ProjectId projectId) {
        Map<String, CapacityObservation> byWorkload = new LinkedHashMap<>();
        for (CapacityObservation observation : history(projectId)) {
            byWorkload.putIfAbsent(observation.workloadName(), observation);
        }
        return byWorkload;
    }

    /**
     * Compares tested capacity with observed production traffic.
     *
     * <p>Frequently declines to produce a number. Comparing capacity measured against simulated
     * dependencies with real production traffic would yield a confident multiple that overstates
     * the headroom a service actually has, so that case returns the reason instead.
     */
    public HeadroomCalculator.Result headroom(ProjectId projectId, ProductionObservation production) {
        return headroom(latest(projectId).orElse(null), production);
    }

    /**
     * Headroom against one particular observation, for a report about the run that produced it.
     *
     * <p>The numerator is sustainable capacity — the level demonstrated to be held, not merely the
     * highest that passed. That is a deliberate change to an existing figure, and figures recorded
     * before it are not comparable with figures recorded after.
     *
     * <p>Which is why an observation that predates the calculation keeps its old numerator rather
     * than being refused. Those rows did not fail the five conditions; nobody evaluated them, and
     * retroactively deleting a number from every historical page reads as a bug rather than as an
     * announced change.
     */
    public HeadroomCalculator.Result headroom(CapacityObservation latest,
            ProductionObservation production) {

        boolean integrated = latest != null
                && latest.classification() == TestClassification.INTEGRATED;
        BoundaryStatus boundary = latest == null
                ? BoundaryStatus.ESTABLISHED : latest.boundaryStatus();

        if (latest != null && !latest.sustainable().conditions().isEmpty()) {
            return headroomCalculator.calculate(latest.sustainable(), integrated, boundary,
                    production);
        }
        return headroomCalculator.calculateFromTestedCapacity(
                latest == null ? null : latest.compliantLevel(), integrated, boundary, production);
    }

    /**
     * Capacity history grouped by the release it was measured against.
     *
     * <p>Grouped rather than flat because tested capacity moves with the version, and a list that
     * interleaves two releases invites a comparison between numbers that were never comparable.
     */
    public java.util.SequencedMap<String, List<CapacityObservation>> historyByVersion(
            ProjectId projectId) {
        java.util.SequencedMap<String, List<CapacityObservation>> byVersion =
                new java.util.LinkedHashMap<>();
        for (CapacityObservation observation : history(projectId)) {
            String version = observation.serviceVersion().isBlank()
                    ? "not recorded" : observation.serviceVersion();
            byVersion.computeIfAbsent(version, _ -> new java.util.ArrayList<>()).add(observation);
        }
        return byVersion;
    }
}
