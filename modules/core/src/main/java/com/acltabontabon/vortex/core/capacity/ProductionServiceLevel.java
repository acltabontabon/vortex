package com.acltabontabon.vortex.core.capacity;

import com.acltabontabon.vortex.core.metrics.ObservationProvenance;
import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.workload.Observation;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * What the service's response time and failure rate actually look like in production.
 *
 * <p>{@link ProductionObservation} models observed <em>traffic volume</em> — every rate there is a
 * rate, never a latency, by explicit design, because neither calibration nor headroom consumes
 * latency. Threshold objectives need the other half of the same picture: how long requests actually
 * take and how often they fail. This is that half, kept as its own type for the reason
 * {@code ProductionObservation}'s own Javadoc gives for keeping rates and latencies apart — both
 * figures appear on the same screens as latency and rate objectives, and a reader who conflates them
 * calibrates from the wrong number.
 *
 * <h2>Every duration here is a latency, never a rate</h2>
 * {@code p95Latency} is the 95th percentile of observed <em>response time</em> — not a request-rate
 * percentile. Absence is meaningful: a service with no latency histogram configured reports no
 * {@code p95Latency} rather than a manufactured zero, per the same honest-gap discipline
 * {@code ObservabilityProvider} uses for telemetry gaps.
 *
 * @param p95Latency     95th-percentile observed response time, when known
 * @param p99Latency     99th-percentile observed response time, when known
 * @param averageLatency mean observed response time, when known
 * @param errorRate      observed share of failed requests over the window, when known
 * @param observation    when this was observed, and over what period
 * @param source         where the observation came from, e.g. a dashboard name
 * @param provenance     the query that produced it; absent when a person typed the numbers in
 */
public record ProductionServiceLevel(
        Duration p95Latency,
        Duration p99Latency,
        Duration averageLatency,
        ErrorRate errorRate,
        Observation observation,
        String source,
        ObservationProvenance provenance) {

    public ProductionServiceLevel {
        observation = observation == null ? Observation.unknown() : observation;
        source = source == null ? "" : source.trim();
        if (provenance != null && provenance.isEmpty()) {
            provenance = null;
        }
        requirePositive(p95Latency, "p95 latency");
        requirePositive(p99Latency, "p99 latency");
        requirePositive(averageLatency, "average latency");
        if (p95Latency != null && p99Latency != null && p95Latency.compareTo(p99Latency) > 0) {
            throw new IllegalArgumentException(
                    "observed p95 latency (" + p95Latency + ") cannot exceed observed p99 latency ("
                            + p99Latency + ")");
        }
        if (p95Latency == null && p99Latency == null && averageLatency == null && errorRate == null) {
            throw new IllegalArgumentException(
                    "a production service level with no latency or error-rate figure at all is not "
                            + "an observation. Remove the section, or record what the service actually "
                            + "experiences.");
        }
    }

    private static void requirePositive(Duration value, String label) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException(
                    "an observed " + label + " of " + value + " is not an observation");
        }
    }

    public Optional<Duration> p95LatencyIfPresent() {
        return Optional.ofNullable(p95Latency);
    }

    public Optional<Duration> p99LatencyIfPresent() {
        return Optional.ofNullable(p99Latency);
    }

    public Optional<Duration> averageLatencyIfPresent() {
        return Optional.ofNullable(averageLatency);
    }

    public Optional<ErrorRate> errorRateIfPresent() {
        return Optional.ofNullable(errorRate);
    }

    public Optional<ObservationProvenance> provenanceIfPresent() {
        return Optional.ofNullable(provenance);
    }

    /** Whether a monitoring system produced this, rather than a person recalling a number. */
    public boolean wasFetched() {
        return provenance != null;
    }

    public boolean hasSource() {
        return !source.isBlank();
    }
}
