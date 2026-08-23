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
        long requests) {

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

    /** A stage whose request count was not established. */
    public StageObservation(LoadLevel targetLoad, RequestsPerSecond achievedRate, Duration p95,
            ErrorRate errorRate, int sampleCount, List<String> violatedThresholds,
            List<MetricObservation> signals, StageWindowBasis basis,
            List<ResourceSignal> resourceSignals) {
        this(targetLoad, achievedRate, p95, errorRate, sampleCount, violatedThresholds, signals,
                basis, resourceSignals, 0);
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

    /**
     * How far the achieved request rate fell short of the offered rate, as a fraction.
     *
     * <p>A load generator that cannot keep up with its own schedule is one of the clearest signs
     * that the system under test has stopped absorbing traffic.
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
        double shortfall = 1.0 - (achievedRate.asDouble() / target.asDouble());
        return Optional.of(Math.max(0.0, shortfall));
    }
}
