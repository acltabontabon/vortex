package dev.vortex.core.metrics;

import dev.vortex.core.shared.ErrorRate;
import dev.vortex.core.shared.LoadLevel;
import dev.vortex.core.shared.RequestsPerSecond;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One aggregation bucket of a running or completed test.
 *
 * <p>Buckets — rather than individual samples — are what Vortex stores and streams. A test at
 * 200 requests/sec produces hundreds of thousands of raw samples; forwarding those to a browser
 * would cost more than the test itself and risk perturbing the very measurement being taken.
 *
 * @param at           bucket start
 * @param duration     bucket width
 * @param requestRate  requests per second observed in this bucket
 * @param errorRate    share of failed requests in this bucket
 * @param p95          p95 request latency in this bucket, when enough samples were seen
 * @param targetLoad   the level the workload was aiming for during this bucket, in whichever
 *                     quantity it controlled
 * @param observedVus  virtual users the load generator actually had running, when it reported them.
 *                     Distinct from {@code targetLoad}: that is the intent, this is what happened.
 *                     For a concurrency workload the difference is what lets stage boundaries be
 *                     measured rather than computed from planned durations
 * @param iterationsDropped units of work the generator could not begin during this bucket, when it
 *                     reported them. Carried per bucket rather than only per run so a validity
 *                     finding can name the <em>level</em> at which the generator fell behind, which
 *                     is the difference between withholding one capacity claim and withholding all
 *                     of them. Absent means the engine said nothing, never that nothing was dropped
 */
public record SamplePoint(
        Instant at,
        Duration duration,
        RequestsPerSecond requestRate,
        ErrorRate errorRate,
        Duration p95,
        LoadLevel targetLoad,
        Integer observedVus,
        Long iterationsDropped) {

    public SamplePoint {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(errorRate, "errorRate");
        if (observedVus != null && observedVus < 0) {
            throw new IllegalArgumentException("observed virtual users must not be negative");
        }
        if (iterationsDropped != null && iterationsDropped < 0) {
            throw new IllegalArgumentException("dropped iterations must not be negative");
        }
    }

    /**
     * A bucket from an engine that did not report dropped work.
     *
     * <p>Retained at the previous arity for the same reason the one below it exists — and it must
     * pass {@code null} rather than {@code 0}, because a bucket nobody measured and a bucket where
     * the generator kept up are different facts.
     */
    public SamplePoint(Instant at, Duration duration, RequestsPerSecond requestRate,
            ErrorRate errorRate, Duration p95, LoadLevel targetLoad, Integer observedVus) {
        this(at, duration, requestRate, errorRate, p95, targetLoad, observedVus, null);
    }

    /**
     * A bucket from an engine that did not report virtual users.
     *
     * <p>Kept so widening the record did not mean editing every caller that has nothing to put in
     * the new field — including every imported script, where k6's own scenario shape is unknown.
     */
    public SamplePoint(Instant at, Duration duration, RequestsPerSecond requestRate,
            ErrorRate errorRate, Duration p95, LoadLevel targetLoad) {
        this(at, duration, requestRate, errorRate, p95, targetLoad, null, null);
    }

    public Optional<Integer> observedVusIfPresent() {
        return Optional.ofNullable(observedVus);
    }

    public Optional<Long> iterationsDroppedIfPresent() {
        return Optional.ofNullable(iterationsDropped);
    }

    public Optional<Duration> p95IfPresent() {
        return Optional.ofNullable(p95);
    }

    public Optional<RequestsPerSecond> requestRateIfPresent() {
        return Optional.ofNullable(requestRate);
    }

    public Optional<LoadLevel> targetLoadIfPresent() {
        return Optional.ofNullable(targetLoad);
    }
}
