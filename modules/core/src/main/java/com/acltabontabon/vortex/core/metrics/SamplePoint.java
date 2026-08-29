package com.acltabontabon.vortex.core.metrics;

import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.LoadLevel;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
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
 * @param latencyHistogram the bucket's pooled latency distribution, absent for a bucket recorded
 *                     before this field existed. Stage-level percentiles merge these across a
 *                     stage's buckets; a bucket with none forces the whole stage onto the legacy,
 *                     permanently-approximate averaging path, because pooling and averaging cannot
 *                     be mixed within one stage
 * @param requestCount the bucket's raw request count, absent for a bucket recorded before this
 *                     field existed. Preserved so a stage's total and rate can be summed from
 *                     primitive counts rather than reconstructed from {@code requestRate}
 * @param failureCount the bucket's raw failure count, absent under the same condition as
 *                     {@code requestCount}. Preserved so a stage's error rate can be
 *                     {@code sum(failures) / sum(requests)} rather than an unweighted average of
 *                     per-bucket fractions, which is wrong whenever bucket traffic volume differs
 */
public record SamplePoint(
        Instant at,
        Duration duration,
        RequestsPerSecond requestRate,
        ErrorRate errorRate,
        Duration p95,
        LoadLevel targetLoad,
        Integer observedVus,
        Long iterationsDropped,
        LatencyHistogram latencyHistogram,
        Long requestCount,
        Long failureCount) {

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
        if (requestCount != null && requestCount < 0) {
            throw new IllegalArgumentException("request count must not be negative");
        }
        if (failureCount != null && failureCount < 0) {
            throw new IllegalArgumentException("failure count must not be negative");
        }
        if (requestCount != null && failureCount != null && failureCount > requestCount) {
            throw new IllegalArgumentException(
                    "failure count must not exceed request count in the same bucket");
        }
    }

    /**
     * A bucket recorded before pooled latency distributions and primitive counts were preserved.
     *
     * <p>Retained at the previous arity for the same reason every constructor below it exists —
     * widening the record must not mean editing every caller that has nothing to put in the new
     * fields, and a row already stored in {@code ~/.vortex/vortex.db} deserializes to exactly this
     * shape, with the three new fields defaulting to {@code null} rather than a fabricated value.
     */
    public SamplePoint(Instant at, Duration duration, RequestsPerSecond requestRate,
            ErrorRate errorRate, Duration p95, LoadLevel targetLoad, Integer observedVus,
            Long iterationsDropped) {
        this(at, duration, requestRate, errorRate, p95, targetLoad, observedVus, iterationsDropped,
                null, null, null);
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

    public Optional<LatencyHistogram> latencyHistogramIfPresent() {
        return Optional.ofNullable(latencyHistogram);
    }

    public Optional<Long> requestCountIfPresent() {
        return Optional.ofNullable(requestCount);
    }

    public Optional<Long> failureCountIfPresent() {
        return Optional.ofNullable(failureCount);
    }
}
