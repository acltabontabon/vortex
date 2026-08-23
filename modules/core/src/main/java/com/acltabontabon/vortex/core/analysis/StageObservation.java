package com.acltabontabon.vortex.core.analysis;

import com.acltabontabon.vortex.core.metrics.MetricObservation;
import com.acltabontabon.vortex.core.resource.ResourceSignal;
import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.LoadLevel;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.workload.StageWindowBasis;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What was measured while the workload was holding one level.
 *
 * <p>Stage-level evidence is what makes breakpoint detection possible. An aggregate p95 across a
 * whole stress run averages the healthy stages together with the failing ones and tells you almost
 * nothing; the same data cut by stage tells you where the trouble started.
 *
 * @param targetLoad         the level the workload was aiming for, in whichever quantity it
 *                           controlled — an arrival rate or a virtual-user count
 * @param achievedRate       the request rate actually observed
 * @param p95                p95 request latency during this stage
 * @param errorRate          share of failed requests during this stage
 * @param sampleCount        how many aggregation buckets contributed
 * @param violatedThresholds ids of objectives violated during this stage
 * @param signals            what the service said about itself while this level was held; empty
 *                           when no provider answered. This is the half that turns "latency rose"
 *                           into "latency rose, and the pool saturated at the same level"
 * @param basis              how this stage's interval was established. A signal aligned by a
 *                           computed boundary is still correlated evidence, but it never
 *                           strengthens a finding — see {@link StageWindowBasis}
 * @param rampStartLevel     the level the previous stage in the plan held, when there is one.
 *                           Absent for the first stage — k6 never ramps into it, since {@code
 *                           startRate} is set to its own target from t=0 — or a single-stage run.
 *                           Used only to correct {@link #rateShortfall()}'s comparison basis; the
 *                           raw {@link #achievedRate} this stage actually measured is untouched
 */
public record StageObservation(
        LoadLevel targetLoad,
        RequestsPerSecond achievedRate,
        Duration p95,
        ErrorRate errorRate,
        int sampleCount,
        List<String> violatedThresholds,
        List<MetricObservation> signals,
        StageWindowBasis basis,
        List<ResourceSignal> resourceSignals,
        long requests,
        LoadLevel rampStartLevel) {

    public StageObservation {
        Objects.requireNonNull(targetLoad, "targetLoad");
        violatedThresholds = violatedThresholds == null ? List.of() : List.copyOf(violatedThresholds);
        errorRate = errorRate == null ? ErrorRate.ZERO : errorRate;
        signals = signals == null ? List.of() : List.copyOf(signals);
        resourceSignals = resourceSignals == null ? List.of() : List.copyOf(resourceSignals);
        basis = basis == null ? StageWindowBasis.DERIVED_FROM_PLAN : basis;
        if (requests < 0) {
            throw new IllegalArgumentException("a stage's request count must not be negative");
        }
    }

    /**
     * A stage with no ramp-start level recorded.
     *
     * <p>Kept at the previous arity for the same reason every constructor below it exists: widening
     * the record must not mean editing every caller that has nothing to put in the new field.
     */
    public StageObservation(LoadLevel targetLoad, RequestsPerSecond achievedRate, Duration p95,
            ErrorRate errorRate, int sampleCount, List<String> violatedThresholds,
            List<MetricObservation> signals, StageWindowBasis basis,
            List<ResourceSignal> resourceSignals, long requests) {
        this(targetLoad, achievedRate, p95, errorRate, sampleCount, violatedThresholds, signals,
                basis, resourceSignals, requests, null);
    }

    /** A stage whose request count was not established. */
    public StageObservation(LoadLevel targetLoad, RequestsPerSecond achievedRate, Duration p95,
            ErrorRate errorRate, int sampleCount, List<String> violatedThresholds,
            List<MetricObservation> signals, StageWindowBasis basis,
            List<ResourceSignal> resourceSignals) {
        this(targetLoad, achievedRate, p95, errorRate, sampleCount, violatedThresholds, signals,
                basis, resourceSignals, 0, null);
    }

    /** A stage whose provider classified none of what it reported. */
    public StageObservation(LoadLevel targetLoad, RequestsPerSecond achievedRate, Duration p95,
            ErrorRate errorRate, int sampleCount, List<String> violatedThresholds,
            List<MetricObservation> signals, StageWindowBasis basis) {
        this(targetLoad, achievedRate, p95, errorRate, sampleCount, violatedThresholds, signals,
                basis, List.of(), 0);
    }

    /**
     * A stage observed from the load generator alone.
     *
     * <p>The shape every caller built before the service's own view could be joined to it, kept so
     * that widening the record did not mean editing each of them.
     */
    public StageObservation(LoadLevel targetLoad, RequestsPerSecond achievedRate, Duration p95,
            ErrorRate errorRate, int sampleCount, List<String> violatedThresholds) {
        this(targetLoad, achievedRate, p95, errorRate, sampleCount, violatedThresholds, List.of(),
                StageWindowBasis.DERIVED_FROM_PLAN, List.of(), 0);
    }

    /** Whether the service's own view of this stage is available at all. */
    public boolean hasSignals() {
        return !signals.isEmpty();
    }

    /** One signal by id, for a finding that needs to cite it. */
    public Optional<MetricObservation> signal(String id) {
        return signals.stream().filter(signal -> signal.id().equals(id)).findFirst();
    }

    /**
     * Typed signals describing the service itself, which are the only ones a statement about its
     * limits may rest on.
     *
     * <p>A generator's CPU and a dependency's memory are both real measurements and neither is a
     * constraint on the system under test. Filtering here rather than at each call site is what
     * stops the second kind quietly becoming the first.
     */
    public List<ResourceSignal> serviceResourceSignals() {
        return resourceSignals.stream()
                .filter(signal -> signal.scope().describesTheServiceUnderTest())
                .toList();
    }

    /** Whether any provider classified a resource for this stage at all. */
    public boolean hasTypedResources() {
        return !resourceSignals.isEmpty();
    }

    /**
     * Whether this stage carried enough traffic to be a boundary edge.
     *
     * <p>Distinct from {@link #sampleCount()}, which counts aggregation buckets. Eleven requests and
     * eleven thousand can occupy the same number of buckets and are not the same evidence — a p95
     * drawn from the first is decided by a handful of measurements.
     *
     * @param minimum the floor, from the validity policy rather than from a literal here
     */
    public boolean hasEnoughSamples(long minimum) {
        return requests >= minimum;
    }

    /**
     * Whether a finding resting on this stage's alignment may claim more than coincidence in time.
     *
     * <p>False for a boundary Vortex computed from planned durations: those timestamps are its own
     * arithmetic, not independent corroboration, and letting them raise confidence would manufacture
     * certainty rather than establish it.
     */
    public boolean supportsStrongerEvidence() {
        return basis.canStrengthenAFinding();
    }

    public boolean isCompliant() {
        return violatedThresholds.isEmpty();
    }

    public Optional<RequestsPerSecond> achievedRateIfPresent() {
        return Optional.ofNullable(achievedRate);
    }

    public Optional<Duration> p95IfPresent() {
        return Optional.ofNullable(p95);
    }

    public Optional<LoadLevel> rampStartLevelIfPresent() {
        return Optional.ofNullable(rampStartLevel);
    }

    /**
     * How far the achieved request rate fell short of the offered rate, as a fraction.
     *
     * <p>A load generator that cannot keep up with its own schedule is one of the clearest signs
     * that the system under test has stopped absorbing traffic.
     *
     * <p>Compared against the ramp's own average, not its arrival point, when this stage followed a
     * different level: k6's arrival-rate executor ramps a stage's rate linearly from the previous
     * stage's target to this one's, over this stage's own duration, so a stage's first several
     * seconds are below its nominal target by design. Averaging the whole window and comparing that
     * to the fully-ramped target would charge the ramp itself as a shortfall — which for a linear
     * ramp sampled evenly is always exactly {@code (start+end)/2} below the end, regardless of
     * whether anything actually fell behind. Comparing against that same average instead means a
     * stage that tracked its ramp perfectly reports (near) zero shortfall, which is what actually
     * happened. Reduces to today's exact comparison whenever there is no ramp start recorded, or the
     * previous stage held the identical level — a true plateau, where {@code (X+X)/2 == X}.
     *
     * <p>Empty for a closed workload. There, throughput is an outcome rather than a target: virtual
     * users simply complete fewer iterations when the service is slow, so there is no shortfall to
     * measure and reporting one would invent a target nobody set.
     */
    public Optional<Double> rateShortfall() {
        if (!(targetLoad instanceof RequestsPerSecond target) || achievedRate == null
                || target.asDouble() <= 0) {
            return Optional.empty();
        }
        double comparisonBasis = target.asDouble();
        if (rampStartLevel instanceof RequestsPerSecond rampStart) {
            comparisonBasis = (rampStart.asDouble() + target.asDouble()) / 2.0;
        }
        double shortfall = 1.0 - (achievedRate.asDouble() / comparisonBasis);
        return Optional.of(Math.max(0.0, shortfall));
    }
}
