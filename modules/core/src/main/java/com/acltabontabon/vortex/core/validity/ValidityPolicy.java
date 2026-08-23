package com.acltabontabon.vortex.core.validity;

import com.acltabontabon.vortex.core.workload.TestType;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * The numbers the validity rules compare against, in one place, written down.
 *
 * <p>"Materially below", "the declared minimum" and "within tolerance" are policy. They decide
 * whether Vortex will quote a capacity, so they may not be literals scattered through the code where
 * nobody can find them to argue with. Every value here carries the reasoning beside it, following
 * what the codebase already does with {@code SystemSaturation.MINIMUM_CORROBORATING_SIGNALS} and
 * {@code RegressionEvaluator.NOISE_THRESHOLD_PERCENT} — a named constant with its justification,
 * rather than a configuration key with no default anyone trusts.
 *
 * <h2>Not configurable in this phase</h2>
 * Deliberately. A tuning surface introduced before anybody has run against the defaults produces
 * arguments instead of evidence. The type is shaped so an organisational policy pack can supply one
 * later without touching a call site — which is also why it is held on the plan beside the
 * objectives rather than injected into the assessor.
 *
 * <p>It is <em>not</em> an {@code ExperimentIdentity} dimension. Since every plan carries the
 * identical value, adding it would distinguish no two experiments while moving every stored
 * fingerprint. That changes when a policy pack can vary it; see ADR-038.
 *
 * @param minimumRunDuration        shortest run of each type whose conclusions stand unqualified
 * @param sustainDuration           how long a level must be held before it can be quoted as capacity
 * @param minimumRequestsPerStage   below which a stage is too thin to be a boundary edge
 * @param minimumBucketsPerStage    below which a stage's compliance rests on too few independent
 *                                  samples to trust, regardless of how many requests it carried
 * @param materialShortfallFraction how far achieved load may fall below offered before the gap is a
 *                                  finding rather than noise
 * @param telemetryWindowTolerance  how far a provider's window may miss the run's before its
 *                                  measurements stop corroborating anything
 * @param targetUnavailableShare    share of classified outcomes that failed without reaching the
 *                                  service, above which the target's availability is in question
 */
public record ValidityPolicy(
        Map<TestType, Duration> minimumRunDuration,
        Map<TestType, Duration> sustainDuration,
        long minimumRequestsPerStage,
        long minimumBucketsPerStage,
        double materialShortfallFraction,
        Duration telemetryWindowTolerance,
        double targetUnavailableShare) {

    public ValidityPolicy {
        minimumRunDuration = copy(minimumRunDuration);
        sustainDuration = copy(sustainDuration);
        if (minimumRequestsPerStage < 0) {
            throw new IllegalArgumentException("a sample floor must not be negative");
        }
        // Defaulted rather than rejected, like telemetryWindowTolerance below: this field did not
        // exist before this policy was ever persisted, so a plan stored before it has no value
        // recorded for it, and Jackson supplies a primitive long's zero for that absence. ValidityPolicy
        // is not yet user-configurable (see the class javadoc), so zero here is never a deliberate
        // choice that needs distinguishing from a missing one.
        minimumBucketsPerStage = minimumBucketsPerStage < 1
                ? DEFAULT_MINIMUM_BUCKETS_PER_STAGE : minimumBucketsPerStage;
        if (materialShortfallFraction <= 0 || materialShortfallFraction >= 1) {
            throw new IllegalArgumentException(
                    "a material shortfall is a fraction between zero and one; " + materialShortfallFraction
                            + " would make every run either always or never short of its offered load");
        }
        if (targetUnavailableShare <= 0 || targetUnavailableShare > 1) {
            throw new IllegalArgumentException(
                    "the unreached-request share is a fraction between zero and one");
        }
        telemetryWindowTolerance = telemetryWindowTolerance == null
                ? Duration.ofSeconds(30) : telemetryWindowTolerance;
    }

    /** The values Vortex ships with. See the javadoc on each for why it is what it is. */
    public static ValidityPolicy defaults() {
        return new ValidityPolicy(defaultMinimumDurations(), defaultSustainDurations(),
                DEFAULT_MINIMUM_REQUESTS_PER_STAGE, DEFAULT_MINIMUM_BUCKETS_PER_STAGE,
                DEFAULT_MATERIAL_SHORTFALL, DEFAULT_WINDOW_TOLERANCE, DEFAULT_TARGET_UNAVAILABLE_SHARE);
    }

    /**
     * A stage below this many requests is too thin to be a boundary edge.
     *
     * <p>One hundred is a floor rather than a recommendation: it is roughly where a p95 stops being
     * decided by a handful of samples. Deriving it from the percentile actually being evaluated — a
     * p99 needs far more than a p50 to mean anything — is the refinement, and it is P1 work.
     */
    public static final long DEFAULT_MINIMUM_REQUESTS_PER_STAGE = 100;

    /**
     * A stage below this many independent sample buckets is too thin to trust, however many requests
     * it carried.
     *
     * <p>Distinct from {@link #DEFAULT_MINIMUM_REQUESTS_PER_STAGE}: a busy stage can clear that floor
     * in a single 5-second bucket at a high enough rate, but a single bucket is one reading, and one
     * reading is indistinguishable from a one-off blip — a GC pause, a rate-step transition,
     * connection churn. Two, not one, is the floor below which "the stage failed" and "one sample was
     * noisy" are the same observation.
     */
    public static final long DEFAULT_MINIMUM_BUCKETS_PER_STAGE = 2;

    /**
     * How far achieved load may fall below offered before it is worth a finding.
     *
     * <p>Ten percent, matching {@code RegressionEvaluator.NOISE_THRESHOLD_PERCENT}, because the two
     * are answering the same underlying question: below what difference is this run-to-run variance
     * rather than a signal? Two different answers to that in one product would be worse than either.
     */
    public static final double DEFAULT_MATERIAL_SHORTFALL = 0.10;

    /**
     * How far a telemetry window may miss the execution window before it corroborates nothing.
     *
     * <p>Thirty seconds is roughly six sampling intervals. Closer than that and the overlap is a
     * clock skew; further and the provider is describing a different period of the service's life.
     */
    public static final Duration DEFAULT_WINDOW_TOLERANCE = Duration.ofSeconds(30);

    /**
     * Share of classified outcomes that never reached the service, above which its availability is
     * in question.
     *
     * <p>Five percent. Deliberately low, because a timeout or a connection reset is not an ordinary
     * failure mode for a healthy target — but note that a saturated <em>generator</em> produces the
     * same shape, which is why this qualifies a run rather than diagnosing the service.
     */
    public static final double DEFAULT_TARGET_UNAVAILABLE_SHARE = 0.05;

    /**
     * The shortest run of each type whose conclusions stand without qualification.
     *
     * <p>Distinct from a sustain duration: this is about the run as a whole rather than about one
     * level within it, and a run below it is qualified rather than refused.
     */
    private static Map<TestType, Duration> defaultMinimumDurations() {
        Map<TestType, Duration> minimums = new EnumMap<>(TestType.class);
        // A smoke test is meant to be tiny; it exists to check the workload is valid at all.
        minimums.put(TestType.SMOKE, Duration.ZERO);
        minimums.put(TestType.AVERAGE_LOAD, Duration.ofMinutes(5));
        minimums.put(TestType.STRESS, Duration.ofMinutes(2));
        minimums.put(TestType.BREAKPOINT, Duration.ofMinutes(2));
        // A spike is over quickly by design — abrupt arrival is the subject of the test.
        minimums.put(TestType.SPIKE, Duration.ZERO);
        minimums.put(TestType.SOAK, Duration.ofMinutes(30));
        return minimums;
    }

    /**
     * How long a level must be held before it can be quoted as a capacity, by test type (ADR-039).
     *
     * <p>Absent means never quotable, which is a different statement from a duration of zero.
     */
    private static Map<TestType, Duration> defaultSustainDurations() {
        Map<TestType, Duration> holds = new EnumMap<>(TestType.class);
        // AVERAGE_LOAD is the run future releases are compared against, so warm-up must be a small
        // fraction of it.
        holds.put(TestType.AVERAGE_LOAD, Duration.ofMinutes(5));
        // Two minutes is the floor below which a stepped level is measuring warm-up rather than the
        // service. Ten levels at five minutes each is a fifty-minute run nobody will schedule, and
        // for these two the boundary matters more than any single level.
        holds.put(TestType.STRESS, Duration.ofMinutes(2));
        holds.put(TestType.BREAKPOINT, Duration.ofMinutes(2));
        // Below thirty minutes a soak is an average-load test with a longer name.
        holds.put(TestType.SOAK, Duration.ofMinutes(30));
        // SMOKE and SPIKE are deliberately absent: neither holds a level, so neither can produce a
        // capacity figure at all.
        return holds;
    }

    public Duration minimumRunDuration(TestType type) {
        return minimumRunDuration.getOrDefault(type, Duration.ZERO);
    }

    /**
     * How long this kind of test must hold a level before it is quotable as capacity.
     *
     * <p>Empty for the types that never hold one. Empty is not zero: a caller reading zero would
     * conclude any level qualifies, which is the opposite of what a spike test can support.
     */
    public Optional<Duration> sustainDuration(TestType type) {
        return Optional.ofNullable(sustainDuration.get(type));
    }

    private static Map<TestType, Duration> copy(Map<TestType, Duration> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<TestType, Duration> copy = new EnumMap<>(TestType.class);
        source.forEach((type, duration) -> {
            if (type != null && duration != null) {
                copy.put(type, duration);
            }
        });
        return Map.copyOf(copy);
    }
}
