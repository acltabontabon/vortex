package com.acltabontabon.vortex.core.capacity;

import com.acltabontabon.vortex.core.metrics.ObservationProvenance;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.threshold.Durations;
import com.acltabontabon.vortex.core.workload.Observation;
import com.acltabontabon.vortex.core.workload.OperationMix;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the service actually experiences in production, as observed by a person or a monitoring
 * system.
 *
 * <p>This is the difference between a performance test that is production-informed and one that
 * tests an invented number. Without it, "the service handled 200 requests/sec" is a fact with no
 * meaning; with it, the same fact becomes "the service has roughly three times the headroom it
 * currently needs".
 *
 * <p>The observed <em>composition</em> matters as much as the volume. A service handling 120
 * requests/sec of cheap status polling is doing something entirely different from one handling 120
 * requests/sec of order submission, and a workload built from the volume alone reproduces a workload
 * the service has never actually seen.
 *
 * <h2>Every rate here is a rate</h2>
 * {@code p95ObservedRate} is the 95th percentile of <em>sampled request rate</em> across the
 * observation window — not a response-time percentile. The distinction is not pedantry: this figure
 * appears on the same screens as latency objectives written as p95, and a reader who conflates the
 * two will calibrate a workload from the wrong number entirely. Observed latency percentiles are
 * deliberately absent, because neither calibration nor headroom consumes them.
 *
 * @param averageRate      observed mean request rate across the window
 * @param p95ObservedRate  95th percentile of the sampled request rate — a rate, never a latency
 * @param peakRate         highest observed request rate
 * @param observedMix      how that traffic was distributed across operations, when known
 * @param mixCoverage      how much of the observed traffic that mix accounts for; absent when the
 *                         source could not establish a total to compare against
 * @param sampleResolution the interval each rate sample averaged over. A peak taken from one-minute
 *                         samples and one taken from hourly samples are different claims about the
 *                         same traffic, so the figure is meaningless without it
 * @param source           where the observation came from, e.g. a dashboard name — recorded so a
 *                         stale or unattributed number is visible as such
 * @param observation      when the observation was taken, and over what period. A window rather than
 *                         a timestamp: the peak of a rolling thirty days and the peak of one evening
 *                         are different claims, and the reader has to be able to tell them apart
 * @param provenance       the query that produced it and where to check it; absent when a person
 *                         typed the numbers in. This is where a PromQL expression lives, which is
 *                         what keeps it off the workloads calibrated from it
 * @param note             anything else qualifying the observation
 */
public record ProductionObservation(
        RequestsPerSecond averageRate,
        RequestsPerSecond p95ObservedRate,
        RequestsPerSecond peakRate,
        OperationMix observedMix,
        OperationMixCoverage mixCoverage,
        Duration sampleResolution,
        String source,
        Observation observation,
        ObservationProvenance provenance,
        String note) {

    public ProductionObservation {
        Objects.requireNonNull(peakRate, "peakRate");
        observation = observation == null ? Observation.unknown() : observation;
        source = source == null ? "" : source.trim();
        note = note == null ? "" : note.trim();
        if (provenance != null && provenance.isEmpty()) {
            provenance = null;
        }
        if (sampleResolution != null && (sampleResolution.isZero() || sampleResolution.isNegative())) {
            throw new IllegalArgumentException(
                    "a sample resolution of " + sampleResolution + " describes no interval");
        }
        if (!peakRate.isPositive()) {
            throw new IllegalArgumentException(
                    "an observed production peak of 0 requests/sec is not an observation. Remove the "
                            + "production section, or record what the service actually receives.");
        }
        if (averageRate != null && averageRate.compareTo(peakRate) > 0) {
            throw new IllegalArgumentException(
                    "observed average rate (" + averageRate.display() + ") cannot exceed the observed peak ("
                            + peakRate.display() + ")");
        }
        if (p95ObservedRate != null && p95ObservedRate.compareTo(peakRate) > 0) {
            throw new IllegalArgumentException(
                    "observed p95 request rate (" + p95ObservedRate.display()
                            + ") cannot exceed the observed peak (" + peakRate.display() + ")");
        }
    }

    /**
     * An observation somebody typed in: rates, a mix, an attribution, and nothing machine-derived.
     *
     * <p>Retained as a constructor rather than folded into the canonical one because it is how every
     * hand-entry path builds an observation, and widening a record should not mean editing every site
     * that never had anything to put in the new fields.
     */
    public ProductionObservation(RequestsPerSecond averageRate, RequestsPerSecond p95ObservedRate,
            RequestsPerSecond peakRate, OperationMix observedMix, String source,
            Observation observation, String note) {
        this(averageRate, p95ObservedRate, peakRate, observedMix, null, null, source, observation,
                null, note);
    }

    public Optional<RequestsPerSecond> averageRateIfPresent() {
        return Optional.ofNullable(averageRate);
    }

    public Optional<RequestsPerSecond> p95ObservedRateIfPresent() {
        return Optional.ofNullable(p95ObservedRate);
    }

    public Optional<OperationMix> observedMixIfPresent() {
        return Optional.ofNullable(observedMix);
    }

    public Optional<OperationMixCoverage> mixCoverageIfPresent() {
        return Optional.ofNullable(mixCoverage);
    }

    public Optional<Duration> sampleResolutionIfPresent() {
        return Optional.ofNullable(sampleResolution);
    }

    public Optional<ObservationProvenance> provenanceIfPresent() {
        return Optional.ofNullable(provenance);
    }

    public boolean hasSource() {
        return !source.isBlank();
    }

    /** Whether a monitoring system produced this, rather than a person recalling a number. */
    public boolean wasFetched() {
        return provenance != null;
    }

    /**
     * Whether this observation can be attributed.
     *
     * <p>An unattributed, undated traffic figure is a number somebody remembered. It is still a
     * reasonable starting point for a workload, but it is not evidence about production, and the
     * interface has to be able to say which one it is holding.
     */
    public boolean isAttributed() {
        return hasSource() && observation.isKnown();
    }

    /**
     * The facts that let an engineer judge how far to trust this baseline.
     *
     * <p>Facts rather than a score. "How trustworthy is this?" is answered by saying where the
     * numbers came from, over what window, at what resolution and how much of the traffic they
     * describe — not by reducing all of that to HIGH, which discards exactly the detail somebody
     * would need in order to disagree.
     */
    public List<String> qualityFacts() {
        List<String> facts = new ArrayList<>();
        facts.add("Recorded by: " + (wasFetched()
                ? "fetched from " + provenance.providerId()
                : "entered by hand"));
        facts.add("Attributed to: " + (hasSource() ? source : "no source recorded"));
        facts.add("Window: " + (observation.isKnown() ? observation.describe() : "not recorded"));
        sampleResolutionIfPresent().ifPresent(
                resolution -> facts.add("Sample resolution: " + Durations.display(resolution)));
        facts.add("Operation mix: " + observedMixIfPresent()
                .map(mix -> mix.size() + " operation" + (mix.size() == 1 ? "" : "s"))
                .orElse("not recorded"));
        mixCoverageIfPresent().ifPresent(coverage -> facts.add("Mix coverage: " + coverage.describe()));
        provenanceIfPresent().flatMap(ObservationProvenance::entityIdIfPresent)
                .ifPresent(entity -> facts.add("Entity: " + entity));
        provenanceIfPresent().filter(p -> !p.query().isEmpty())
                .ifPresent(p -> facts.add("Query: " + p.query()));
        return List.copyOf(facts);
    }
}
