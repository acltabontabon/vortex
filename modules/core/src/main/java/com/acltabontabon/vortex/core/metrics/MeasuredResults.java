package com.acltabontabon.vortex.core.metrics;

import com.acltabontabon.vortex.core.resource.ResourceScope;
import com.acltabontabon.vortex.core.resource.ResourceSignal;
import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.LoadLevel;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The normalised measurements produced by one execution.
 *
 * <p>This is the boundary between the load generator's wire format and the rest of Vortex. Nothing
 * downstream — threshold evaluation, breakpoint detection, reports, the AI assistant — sees a k6
 * data structure. Replacing or adding an engine means producing this type, and nothing else.
 *
 * <p>Target and achieved throughput are carried separately and neither is derived from the other.
 * The gap between them is one of the most important signals a run produces: a service that cannot
 * accept the offered load delivers fewer requests than were asked for, and folding that into a
 * single "throughput" figure would hide the moment the service stopped keeping up.
 *
 * @param window        interval the measurements cover
 * @param targetLoad    the level the workload aimed for at its peak, in whichever quantity the
 *                      workload controlled; absent for an imported script Vortex did not plan
 * @param achievedRate  requests per second actually observed
 * @param requests      total requests issued
 * @param failures      requests that failed
 * @param latency       request latency distribution across all operations
 * @param perOperation  per-operation breakdown, keyed by operation
 * @param series        time series of aggregation buckets
 * @param observations  additional measurements carrying their own provenance, typically from an
 *                      observability provider watching the service under test
 * @param stageTelemetry the same measurements cut by workload stage, so "what changed as load
 *                      increased" can be answered rather than only "what got hot". Empty when no
 *                      provider answered, or when the workload had no stages to align to
 * @param telemetryGaps what a provider was asked for and could not supply, and why. Kept because
 *                      "nobody looked" and "we looked and there was nothing" are different answers,
 *                      and only one of them leaves the question open
 * @param generation    what the load generator itself managed, which is the only way to tell "the
 *                      service could not go faster" from "we could not ask faster". Carried
 *                      separately from the service's counters because it describes the other system
 * @param phases        where each request's time went, when the engine reported a breakdown
 * @param reliability   what kind of outcomes the run produced, beside the count of how many failed
 * @param resourceSignals the subset of {@code observations} a provider classified as a typed
 *                      resource, with the system it describes and the limit it is measured against.
 *                      An index over that list, never a replacement: an unclassified measurement is
 *                      still collected, cited, exported and rendered — it simply cannot become a
 *                      statement about a limit
 */
public record MeasuredResults(
        TimeWindow window,
        LoadLevel targetLoad,
        RequestsPerSecond achievedRate,
        long requests,
        long failures,
        LatencyPercentiles latency,
        Map<OperationId, OperationMetrics> perOperation,
        MetricSeries series,
        List<MetricObservation> observations,
        List<StageTelemetry> stageTelemetry,
        List<TelemetryGap> telemetryGaps,
        LoadGeneration generation,
        RequestPhases phases,
        ReliabilityBreakdown reliability,
        List<ResourceSignal> resourceSignals) {

    public MeasuredResults {
        Objects.requireNonNull(window, "window");
        latency = latency == null ? LatencyPercentiles.empty() : latency;
        perOperation = perOperation == null ? Map.of() : Map.copyOf(perOperation);
        series = series == null ? MetricSeries.empty() : series;
        observations = observations == null ? List.of() : List.copyOf(observations);
        stageTelemetry = stageTelemetry == null ? List.of() : List.copyOf(stageTelemetry);
        telemetryGaps = telemetryGaps == null ? List.of() : List.copyOf(telemetryGaps);
        // Absent, not empty-and-therefore-fine: a null here means an execution recorded before
        // these were collected, and "the generator kept up" must never be the default answer to a
        // question nobody asked.
        generation = generation == null ? LoadGeneration.notReported() : generation;
        phases = phases == null ? RequestPhases.empty() : phases;
        reliability = reliability == null ? ReliabilityBreakdown.notReported() : reliability;
        resourceSignals = resourceSignals == null ? List.of() : List.copyOf(resourceSignals);
        if (requests < 0 || failures < 0) {
            throw new IllegalArgumentException("measurement counters must not be negative");
        }
        if (failures > requests) {
            throw new IllegalArgumentException(
                    "failures (" + failures + ") cannot exceed requests (" + requests + ")");
        }
    }

    /**
     * Results from an engine that reported nothing about its own throughput, its request phases or
     * its outcome distribution.
     *
     * <p>Kept as a constructor for the same reason the one below it is: widening a record should not
     * mean editing every site that has nothing to put in the new fields. What it must not do is
     * invent values for them — all three default to their "not reported" form.
     */
    public MeasuredResults(TimeWindow window, LoadLevel targetLoad, RequestsPerSecond achievedRate,
            long requests, long failures, LatencyPercentiles latency,
            Map<OperationId, OperationMetrics> perOperation, MetricSeries series,
            List<MetricObservation> observations, List<StageTelemetry> stageTelemetry,
            List<TelemetryGap> telemetryGaps) {
        this(window, targetLoad, achievedRate, requests, failures, latency, perOperation, series,
                observations, stageTelemetry, telemetryGaps, LoadGeneration.notReported(),
                RequestPhases.empty(), ReliabilityBreakdown.notReported(), List.of());
    }

    /** Results whose providers classified none of what they reported as a typed resource. */
    public MeasuredResults(TimeWindow window, LoadLevel targetLoad, RequestsPerSecond achievedRate,
            long requests, long failures, LatencyPercentiles latency,
            Map<OperationId, OperationMetrics> perOperation, MetricSeries series,
            List<MetricObservation> observations, List<StageTelemetry> stageTelemetry,
            List<TelemetryGap> telemetryGaps, LoadGeneration generation, RequestPhases phases,
            ReliabilityBreakdown reliability) {
        this(window, targetLoad, achievedRate, requests, failures, latency, perOperation, series,
                observations, stageTelemetry, telemetryGaps, generation, phases, reliability,
                List.of());
    }

    /**
     * Results with no stage-aligned telemetry.
     *
     * <p>Kept as a constructor rather than folded into the canonical one because it is how every
     * engine and every fixture already builds results, and widening a record should not mean editing
     * every site that has nothing to put in the new field.
     */
    public MeasuredResults(TimeWindow window, LoadLevel targetLoad, RequestsPerSecond achievedRate,
            long requests, long failures, LatencyPercentiles latency,
            Map<OperationId, OperationMetrics> perOperation, MetricSeries series,
            List<MetricObservation> observations) {
        this(window, targetLoad, achievedRate, requests, failures, latency, perOperation, series,
                observations, List.of(), List.of());
    }

    public ErrorRate errorRate() {
        return ErrorRate.of(failures, requests);
    }

    public long successes() {
        return requests - failures;
    }

    public Duration duration() {
        return window.duration();
    }

    public Optional<RequestsPerSecond> achievedRateIfPresent() {
        return Optional.ofNullable(achievedRate);
    }

    public Optional<LoadLevel> targetLoadIfPresent() {
        return Optional.ofNullable(targetLoad);
    }

    public Optional<OperationMetrics> forOperation(OperationId operationId) {
        return Optional.ofNullable(perOperation.get(operationId));
    }

    /**
     * How much of a given target rate the service actually accepted, as a fraction of one.
     *
     * <p>{@code MeasuredResults} carries no notion of a workload's stage sequence — it is the
     * boundary to the engine's wire format, not to the plan — so a caller comparing against
     * anything other than the raw {@link #targetLoad} (e.g. a ramp-aware average) supplies that
     * basis explicitly.
     */
    public Optional<Double> deliveredFraction(LoadLevel comparisonBasis) {
        if (!(comparisonBasis instanceof RequestsPerSecond target) || achievedRate == null
                || target.asDouble() <= 0) {
            return Optional.empty();
        }
        return Optional.of(achievedRate.asDouble() / target.asDouble());
    }

    /**
     * How much of the offered load the service actually accepted, as a fraction of one, compared
     * against the workload's raw peak. See {@link #deliveredFraction(LoadLevel)} for comparing
     * against a different basis, such as a ramp's own time-weighted average.
     *
     * <p>Only meaningful when the workload controlled an arrival rate. A closed workload's
     * throughput is an outcome rather than a target, so there is no shortfall to compute — the
     * virtual users simply went slower.
     */
    public Optional<Double> deliveredFraction() {
        return deliveredFraction(targetLoad);
    }

    public Optional<MetricObservation> observation(String id) {
        return observations.stream().filter(o -> o.id().equals(id)).findFirst();
    }

    public boolean hasSeries() {
        return !series.isEmpty();
    }

    public boolean hasPerOperationBreakdown() {
        return !perOperation.isEmpty();
    }

    /** The typed signals describing one system, in the order the providers reported them. */
    public List<ResourceSignal> resourcesScopedTo(ResourceScope scope) {
        return resourceSignals.stream().filter(signal -> signal.scope() == scope).toList();
    }

    /**
     * Whether anything was observed about the machine that generated the traffic.
     *
     * <p>Consulted before a conclusion is drawn about whose limit was reached. False means nobody
     * looked — never that the generator was healthy.
     */
    public boolean observedTheLoadGenerator() {
        return !resourcesScopedTo(ResourceScope.LOAD_GENERATOR).isEmpty()
                || !resourcesScopedTo(ResourceScope.LOAD_GENERATOR_HOST).isEmpty();
    }
}
