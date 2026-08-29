package com.acltabontabon.vortex.core.metrics;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * A mergeable, pooled distribution of request latencies, encoded as a sparse integer-nanosecond
 * histogram.
 *
 * <p>Averaging one bucket's p95 with another's is not the pooled p95 of anything — percentiles do not
 * compose under arithmetic averaging, and a stage's true tail latency can come out either better or
 * worse than that average depending on how traffic happened to fall across buckets. A histogram's bin
 * counts, on the other hand, sum exactly regardless of how the underlying observations were batched:
 * merging is what makes a mathematically valid cross-bucket percentile possible at all.
 *
 * <p>Scheme v1's bin boundaries are a precomputed table of plain {@code long} nanosecond values
 * (see {@link #binIndexFor}), not a per-sample logarithm — this is persisted, versioned evidence, and a
 * floating-point identity like {@code pow(1.02, i+1) == pow(1.02, i) * 1.02} is not guaranteed to hold
 * exactly across the whole {@code long} domain. No floating-point value participates in bin encoding,
 * lookup, merging, or bin-representative decoding; the table itself is generated once, with exact
 * integer/{@link BigInteger} ratio arithmetic, at class-load time.
 *
 * <p>Every bin's representative is reported as its own inclusive upper bound, which is what guarantees
 * a bin can only ever overstate a latency it holds, never understate one — the direction this product
 * must never round the other way.
 *
 * @param schemeVersion identifies which bin table {@code bins} was encoded against. A histogram must
 *                       only ever be interpreted, merged, or decoded using the table its own version
 *                       names — reinterpreting one scheme's bin indexes under another's constants would
 *                       silently relabel every observation it holds
 * @param zeroCount      exact-zero observations, held apart from the logarithmic bins. A zero-latency
 *                       response is real (an in-process or cached answer), and reporting it back needs
 *                       no approximation at all
 * @param bins           non-zero bin counts only, sorted by strictly increasing {@link BinCount#binIndex()}.
 *                       A bin absent from this list is a bin with nothing in it, not a zero stored
 */
public record LatencyHistogram(int schemeVersion, long zeroCount, List<BinCount> bins) {

    /** One non-empty bin: how many observations fell in the range {@code binIndexFor} assigns it. */
    public record BinCount(int binIndex, long count) {

        public BinCount {
            if (count <= 0) {
                throw new IllegalArgumentException(
                        "a stored histogram bin must have a positive count but was " + count);
            }
        }
    }

    /** The only scheme this build understands: integer nanoseconds, geometric bins, ~2% bound. */
    public static final int SCHEME_VERSION_1 = 1;

    private static final Set<Integer> KNOWN_SCHEME_VERSIONS = Set.of(SCHEME_VERSION_1);

    /**
     * Every representable {@code binIndex} for scheme v1, precomputed once with exact integer ratio
     * arithmetic rather than evaluated per sample.
     *
     * <p>{@code UPPER_BOUNDS_V1[i]} is bin {@code i}'s own inclusive upper bound; its lower bound is
     * {@code i == 0 ? 1 : UPPER_BOUNDS_V1[i - 1] + 1}. Bins below index 49 are exactly one nanosecond
     * wide — {@code floor(lower * 1.02)} only exceeds {@code lower} once {@code lower >= 50} — so small
     * durations are reported with zero error, not merely a small one.
     *
     * <p>Package-private, not private, so tests can verify the generated table's structural invariants
     * (contiguous, no gaps, no overlap, the documented relative bound) directly rather than only
     * indirectly through {@link #percentile}.
     */
    static final long[] UPPER_BOUNDS_V1 = generateScheme1Table();

    public LatencyHistogram {
        if (!KNOWN_SCHEME_VERSIONS.contains(schemeVersion)) {
            throw new IllegalArgumentException("unrecognized latency histogram scheme version "
                    + schemeVersion + " — refusing to reinterpret its bins under current constants");
        }
        if (zeroCount < 0) {
            throw new IllegalArgumentException("zeroCount must not be negative but was " + zeroCount);
        }
        bins = List.copyOf(bins == null ? List.of() : bins);
        for (int i = 0; i < bins.size(); i++) {
            validateForScheme(schemeVersion, bins.get(i));
            if (i > 0 && bins.get(i).binIndex() <= bins.get(i - 1).binIndex()) {
                throw new IllegalArgumentException(
                        "histogram bins must be sorted by strictly increasing binIndex");
            }
        }
    }

    private static void validateForScheme(int schemeVersion, BinCount bin) {
        // Scheme-specific, not a rule assumed to hold forever: v1's construction makes a negative or
        // out-of-table binIndex impossible from any real v1 construction path, so one can only mean
        // corrupted or hand-edited persisted data. A future scheme would define its own rule here.
        if (schemeVersion == SCHEME_VERSION_1
                && (bin.binIndex() < 0 || bin.binIndex() >= UPPER_BOUNDS_V1.length)) {
            throw new IllegalArgumentException("scheme v1 binIndex " + bin.binIndex()
                    + " is outside the generated table's range [0, " + UPPER_BOUNDS_V1.length + ")");
        }
    }

    /** An empty histogram under scheme v1 — the version {@link Builder} always produces. */
    public static LatencyHistogram empty() {
        return empty(SCHEME_VERSION_1);
    }

    /** An empty histogram under an explicit scheme, for callers that need to name one. */
    public static LatencyHistogram empty(int schemeVersion) {
        return new LatencyHistogram(schemeVersion, 0, List.of());
    }

    public boolean isEmpty() {
        return zeroCount == 0 && bins.isEmpty();
    }

    public long totalCount() {
        long total = zeroCount;
        for (BinCount bin : bins) {
            total = Math.addExact(total, bin.count());
        }
        return total;
    }

    /**
     * Pools this histogram with another's.
     *
     * <p>Exact, not merely approximate: which bin a duration falls into depends only on the duration
     * itself, never on which bucket it arrived in, so summing two histograms' bin counts is identical
     * to classifying their combined raw observations into one histogram directly. The only
     * approximation anywhere in this design happens once, at {@link #binIndexFor}; merging never adds
     * to it.
     *
     * @throws IllegalArgumentException if the two histograms were encoded under different schemes —
     *         their bin indexes are not comparable, and merging them would silently mislabel data
     */
    public LatencyHistogram merge(LatencyHistogram other) {
        if (schemeVersion != other.schemeVersion) {
            throw new IllegalArgumentException("cannot merge latency histograms of schemes "
                    + schemeVersion + " and " + other.schemeVersion);
        }
        long mergedZero = Math.addExact(zeroCount, other.zeroCount);

        List<BinCount> merged = new ArrayList<>(bins.size() + other.bins.size());
        int i = 0;
        int j = 0;
        while (i < bins.size() && j < other.bins.size()) {
            BinCount a = bins.get(i);
            BinCount b = other.bins.get(j);
            if (a.binIndex() == b.binIndex()) {
                merged.add(new BinCount(a.binIndex(), Math.addExact(a.count(), b.count())));
                i++;
                j++;
            } else if (a.binIndex() < b.binIndex()) {
                merged.add(a);
                i++;
            } else {
                merged.add(b);
                j++;
            }
        }
        while (i < bins.size()) {
            merged.add(bins.get(i++));
        }
        while (j < other.bins.size()) {
            merged.add(other.bins.get(j++));
        }
        return new LatencyHistogram(schemeVersion, mergedZero, merged);
    }

    /**
     * Pools a list of histograms.
     *
     * <p>Seeded from the first real histogram in the list rather than an injected {@link #empty()} —
     * a hard-coded scheme-v1 identity element would fail a genuinely non-empty, mutually-compatible
     * list the moment a second scheme exists. An empty input list has no data to derive a scheme from,
     * so it returns a scheme-v1 empty histogram as a documented convenience only.
     */
    public static LatencyHistogram merge(List<LatencyHistogram> histograms) {
        if (histograms.isEmpty()) {
            return empty(SCHEME_VERSION_1);
        }
        LatencyHistogram result = histograms.get(0);
        for (int i = 1; i < histograms.size(); i++) {
            result = result.merge(histograms.get(i));
        }
        return result;
    }

    /**
     * The nearest-rank estimate of the pooled distribution at {@code quantile}.
     *
     * <p>A bounded approximation of the true pooled nearest-rank percentile, not the percentile
     * itself: for a positive duration {@code v} landing in bin {@code i} (whose stored representative
     * is its own upper bound), {@code v <= representative <= v * 1.02}, exact below 50ns and for zero.
     * See {@link #binIndexFor} for why this holds without relying on any continuous logarithmic
     * identity.
     *
     * @param quantile in {@code (0, 1]} — {@code 0} has no well-defined nearest-rank order statistic
     *                 under this rule, and Vortex only ever asks for p50/p95/p99, all safely positive
     */
    public Optional<Duration> percentile(double quantile) {
        if (!(quantile > 0) || quantile > 1 || Double.isNaN(quantile)) {
            throw new IllegalArgumentException(
                    "quantile must satisfy 0 < quantile <= 1 but was " + quantile);
        }
        long total = totalCount();
        if (total == 0) {
            return Optional.empty();
        }
        // BigDecimal.valueOf, not `new BigDecimal(quantile)`: honours the quantile as this double API
        // parameter's own canonical decimal value (e.g. 0.95 behaves as 0.95), not its exact binary
        // expansion. Exact across the whole long domain, unlike a double multiplication, which loses
        // integer precision once total exceeds 2^53 — unreachable in practice, but the histogram's own
        // counts are checked up to Long.MAX_VALUE, so ranking should not have a precision gap they
        // don't.
        long targetRank = BigDecimal.valueOf(quantile)
                .multiply(BigDecimal.valueOf(total))
                .setScale(0, RoundingMode.CEILING)
                .longValueExact();

        if (targetRank <= zeroCount) {
            return Optional.of(Duration.ZERO);
        }
        long remaining = targetRank - zeroCount;
        long cumulative = 0;
        for (BinCount bin : bins) {
            cumulative += bin.count();
            if (cumulative >= remaining) {
                return Optional.of(representativeOf(bin.binIndex()));
            }
        }
        throw new IllegalStateException(
                "latency histogram bin counts do not sum to its own totalCount()");
    }

    /**
     * Which bin a positive duration belongs to.
     *
     * <p>A binary search over a precomputed table, not a logarithm evaluated per sample: exact integer
     * comparison only, {@code O(log(binCount))} — trivial against roughly 2,175 bins — deliberately
     * chosen to keep transcendental math off Vortex's metrics-ingestion path.
     */
    static int binIndexFor(long durationNanos) {
        int index = Arrays.binarySearch(UPPER_BOUNDS_V1, durationNanos);
        return index >= 0 ? index : -index - 1;
    }

    private static Duration representativeOf(int binIndex) {
        return Duration.ofNanos(UPPER_BOUNDS_V1[binIndex]);
    }

    /**
     * Builds scheme v1's bin table once: every bin's inclusive upper bound, covering
     * {@code [1, Long.MAX_VALUE]} nanoseconds with no gaps and no overlap.
     *
     * <p>{@code BigInteger} only here, never on the ingestion path — {@code lower * 102} overflows a
     * {@code long} well before the top of the domain, so the one-time generation needs arithmetic wide
     * enough to walk all the way to {@code Long.MAX_VALUE} without it. For a bin whose lower bound is
     * {@code lower}, its upper bound is {@code max(lower, floor(lower * 102 / 100))} — the {@code max}
     * is what makes bins below 50ns collapse to exactly one nanosecond wide, since a 2% expansion of a
     * small integer floors back down to the same integer.
     */
    private static long[] generateScheme1Table() {
        List<Long> upperBounds = new ArrayList<>();
        BigInteger longMax = BigInteger.valueOf(Long.MAX_VALUE);
        BigInteger lower = BigInteger.ONE;
        BigInteger growthNumerator = BigInteger.valueOf(102);
        BigInteger growthDenominator = BigInteger.valueOf(100);
        while (true) {
            BigInteger candidate = lower.multiply(growthNumerator).divide(growthDenominator);
            BigInteger upper = candidate.max(lower);
            if (upper.compareTo(longMax) >= 0) {
                upperBounds.add(Long.MAX_VALUE);
                break;
            }
            upperBounds.add(upper.longValueExact());
            lower = upper.add(BigInteger.ONE);
        }
        long[] table = new long[upperBounds.size()];
        for (int i = 0; i < table.length; i++) {
            table[i] = upperBounds.get(i);
        }
        return table;
    }

    /** Accumulates raw durations into a histogram, without capping or sampling. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mutable accumulator for one histogram.
     *
     * <p>A plain {@link HashMap} on the hot path, not the canonical sorted form — recording is O(1)
     * amortized per observation, with no cap and no reservoir replacement, unlike the raw-duration list
     * this replaces. Sorting happens once, in {@link #build()}, not per sample.
     */
    public static final class Builder {

        private long zeroCount;
        private final Map<Integer, Long> counts = new HashMap<>();

        /**
         * Records one observation.
         *
         * @param durationNanos a non-negative nanosecond duration, already validated and normalized by
         *                      the caller (see {@code K6RawMetricsAggregator.Bucket.addDuration}) — a
         *                      {@code long} cannot itself be {@code NaN} or infinite, so this method
         *                      only ever needs to guard negativity
         */
        public Builder record(long durationNanos) {
            if (durationNanos < 0) {
                throw new IllegalArgumentException("a duration must not be negative but was "
                        + durationNanos);
            }
            if (durationNanos == 0) {
                zeroCount = Math.incrementExact(zeroCount);
            } else {
                counts.merge(binIndexFor(durationNanos), 1L, Math::addExact);
            }
            return this;
        }

        public LatencyHistogram build() {
            Map<Integer, Long> sorted = new TreeMap<>(counts);
            List<BinCount> bins = new ArrayList<>(sorted.size());
            sorted.forEach((binIndex, count) -> bins.add(new BinCount(binIndex, count)));
            return new LatencyHistogram(SCHEME_VERSION_1, zeroCount, bins);
        }
    }
}
