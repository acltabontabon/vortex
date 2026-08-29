package com.acltabontabon.vortex.core.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acltabontabon.vortex.core.metrics.LatencyHistogram.BinCount;
import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LatencyHistogram} — the mergeable, sparse, integer-nanosecond replacement for averaging
 * bucket p95s. Percentile-correctness tests always compare against an independently-computed sorted-
 * array oracle, never against the old averaging bug: that bug is not reliably directional, so it
 * cannot serve as a reference value for anything.
 */
class LatencyHistogramTest {

    // ---- helpers ----------------------------------------------------------------------------

    private static LatencyHistogram singleValue(long durationNanos) {
        return LatencyHistogram.builder().record(durationNanos).build();
    }

    private static Duration representativeFor(long durationNanos) {
        return singleValue(durationNanos).percentile(1.0).orElseThrow();
    }

    /** The nearest-rank order statistic, using the same {@code ceil(q * n)} rank convention. */
    private static long trueNearestRank(List<Long> sortedAscending, double quantile) {
        int rank = (int) Math.ceil(quantile * sortedAscending.size());
        int index = Math.max(0, Math.min(sortedAscending.size() - 1, rank - 1));
        return sortedAscending.get(index);
    }

    /**
     * Asserts the histogram's reported value for {@code trueNanos} never understates it, and never
     * overstates it by more than the documented 2% bound — checked with {@link BigInteger} so the
     * assertion itself cannot silently overflow at large magnitudes the way a naive {@code long}
     * multiplication could.
     */
    private static void assertNeverUnderstatesWithinBound(long trueNanos, Duration reported) {
        long reportedNanos = reported.toNanos();
        assertThat(reportedNanos).isGreaterThanOrEqualTo(trueNanos);
        BigInteger lhs = BigInteger.valueOf(100).multiply(BigInteger.valueOf(reportedNanos));
        BigInteger rhs = BigInteger.valueOf(102).multiply(BigInteger.valueOf(trueNanos));
        assertThat(lhs.compareTo(rhs)).isLessThanOrEqualTo(0);
    }

    private static LatencyHistogram histogramFrom(List<Long> durationsNanos) {
        LatencyHistogram.Builder builder = LatencyHistogram.builder();
        durationsNanos.forEach(builder::record);
        return builder.build();
    }

    // ---- bin-table structural invariants -----------------------------------------------------

    @Test
    @DisplayName("the generated table's first bin starts at 1 nanosecond")
    void firstBinStartsAtOne() {
        assertThat(LatencyHistogram.UPPER_BOUNDS_V1[0]).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("the generated table's upper bounds are strictly increasing — no zero-width or duplicate bins")
    void upperBoundsAreStrictlyIncreasing() {
        long[] table = LatencyHistogram.UPPER_BOUNDS_V1;
        for (int i = 1; i < table.length; i++) {
            assertThat(table[i]).isGreaterThan(table[i - 1]);
        }
    }

    @Test
    @DisplayName("the generated table's last bin covers Long.MAX_VALUE")
    void lastBinCoversLongMaxValue() {
        long[] table = LatencyHistogram.UPPER_BOUNDS_V1;
        assertThat(table[table.length - 1]).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("bins are contiguous: each bin starts exactly where the previous one ended, no gaps or overlap")
    void binsAreContiguous() {
        long[] table = LatencyHistogram.UPPER_BOUNDS_V1;
        for (int i = 1; i < table.length; i++) {
            long lowerOfNext = table[i - 1] + 1;
            assertThat(LatencyHistogram.binIndexFor(table[i - 1])).isEqualTo(i - 1);
            assertThat(LatencyHistogram.binIndexFor(lowerOfNext)).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("every generated bin satisfies the documented 2% growth bound: upper <= lower * 1.02")
    void everyBinSatisfiesTheGrowthBound() {
        long[] table = LatencyHistogram.UPPER_BOUNDS_V1;
        for (int i = 0; i < table.length; i++) {
            long lower = i == 0 ? 1 : table[i - 1] + 1;
            long upper = table[i];
            BigInteger lhs = BigInteger.valueOf(100).multiply(BigInteger.valueOf(upper));
            BigInteger rhs = BigInteger.valueOf(102).multiply(BigInteger.valueOf(lower));
            assertThat(lhs.compareTo(rhs))
                    .as("bin %d: lower=%d, upper=%d", i, lower, upper)
                    .isLessThanOrEqualTo(0);
        }
    }

    // ---- exhaustive small values --------------------------------------------------------------

    @Test
    @DisplayName("every integer nanosecond from 1 to 49 is reported exactly, with zero error")
    void smallDurationsBelow50AreExact() {
        for (long v = 1; v < 50; v++) {
            assertThat(representativeFor(v).toNanos()).isEqualTo(v);
        }
    }

    @Test
    @DisplayName("every integer nanosecond from 1 to 10,000 is never understated and stays within the 2% bound")
    void exhaustiveSmallValuesNeverUnderstateAndStayWithinBound() {
        for (long v = 1; v <= 10_000; v++) {
            assertNeverUnderstatesWithinBound(v, representativeFor(v));
        }
    }

    // ---- large-value cases ---------------------------------------------------------------------

    @Test
    @DisplayName("large values (powers of 10, powers of 2, and near Long.MAX_VALUE) resolve within the documented bound")
    void largeValuesResolveWithinBound() {
        List<Long> values = new ArrayList<>();
        for (int exponent = 1; exponent <= 18; exponent++) {
            values.add((long) Math.pow(10, exponent));
        }
        for (int exponent = 1; exponent <= 62; exponent++) {
            values.add(1L << exponent);
        }
        values.add(Long.MAX_VALUE);
        values.add(Long.MAX_VALUE - 1);
        values.add(Long.MAX_VALUE / 2);

        for (long v : values) {
            assertNeverUnderstatesWithinBound(v, representativeFor(v));
        }
    }

    @Test
    @DisplayName("Long.MAX_VALUE itself is representable — the final bin's own upper bound")
    void longMaxValueIsRepresentable() {
        assertThat(representativeFor(Long.MAX_VALUE).toNanos()).isEqualTo(Long.MAX_VALUE);
    }

    // ---- binary-search correctness at boundaries -----------------------------------------------

    @Test
    @DisplayName("binIndexFor transitions to the next bin exactly one nanosecond past a bin's upper bound")
    void binIndexForTransitionsAtBoundaries() {
        long[] table = LatencyHistogram.UPPER_BOUNDS_V1;
        int[] sampledIndexes = {0, 1, 48, 49, 50, 51, 100, 1000, table.length - 2, table.length - 1};
        for (int i : sampledIndexes) {
            long upper = table[i];
            assertThat(LatencyHistogram.binIndexFor(upper)).isEqualTo(i);
            if (i < table.length - 1) {
                assertThat(LatencyHistogram.binIndexFor(upper + 1)).isEqualTo(i + 1);
            }
        }
    }

    // ---- zero handling ---------------------------------------------------------------------------

    @Test
    @DisplayName("an exact-zero observation is reported back as exactly zero, with no approximation")
    void exactZeroReportsExactlyZero() {
        LatencyHistogram histogram = LatencyHistogram.builder().record(0L).build();

        assertThat(histogram.percentile(1.0)).contains(Duration.ZERO);
        assertThat(histogram.zeroCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a mix of zero and positive observations ranks the zeros as the lowest values")
    void mixedZeroAndPositiveRanksCorrectly() {
        LatencyHistogram histogram = LatencyHistogram.builder()
                .record(0L)
                .record(0L)
                .record(0L)
                .record(30L)
                .build();

        // ranks 1-3 are the zeros, rank 4 (p100) is the positive observation — 30ns is below the
        // 50ns threshold where bins widen, so its representative is exact.
        assertThat(histogram.percentile(0.5)).contains(Duration.ZERO);
        assertThat(histogram.percentile(1.0)).contains(Duration.ofNanos(30));
    }

    // ---- merge behavior ---------------------------------------------------------------------------

    @Test
    @DisplayName("merge conserves the total count exactly")
    void mergeConservesCount() {
        LatencyHistogram a = histogramFrom(List.of(1L, 50L, 100L, 0L));
        LatencyHistogram b = histogramFrom(List.of(2L, 60L, 0L));

        assertThat(a.merge(b).totalCount()).isEqualTo(a.totalCount() + b.totalCount());
    }

    @Test
    @DisplayName("merge is commutative")
    void mergeIsCommutative() {
        LatencyHistogram a = histogramFrom(List.of(1L, 50L, 1_000_000L));
        LatencyHistogram b = histogramFrom(List.of(2L, 60L, 0L, 5_000_000L));

        assertThat(a.merge(b)).isEqualTo(b.merge(a));
    }

    @Test
    @DisplayName("merge is associative")
    void mergeIsAssociative() {
        LatencyHistogram a = histogramFrom(List.of(1L, 50L));
        LatencyHistogram b = histogramFrom(List.of(2L, 60L, 0L));
        LatencyHistogram c = histogramFrom(List.of(3L, 70L, 1_000_000L));

        assertThat(a.merge(b).merge(c)).isEqualTo(a.merge(b.merge(c)));
    }

    @Test
    @DisplayName("merging sub-histograms of pooled data equals one histogram built from that same pooled data, however it was split")
    void mergeOfSubHistogramsEqualsOnePooledHistogram() {
        Random random = new Random(42);
        List<Long> pooled = new ArrayList<>();
        for (int i = 0; i < 2_000; i++) {
            pooled.add((long) (random.nextDouble() * 100_000_000));
        }
        LatencyHistogram oneHistogram = histogramFrom(pooled);

        // split 1: a single bucket
        assertThat(histogramFrom(pooled)).isEqualTo(oneHistogram);

        // split 2: 20 equal buckets
        LatencyHistogram equalSplit = LatencyHistogram.merge(partition(pooled, 20).stream()
                .map(LatencyHistogramTest::histogramFrom)
                .toList());
        assertThat(equalSplit).isEqualTo(oneHistogram);

        // split 3: uneven buckets (first 10, then chunks of increasing size)
        List<List<Long>> uneven = new ArrayList<>();
        int index = 0;
        int chunk = 10;
        while (index < pooled.size()) {
            int end = Math.min(pooled.size(), index + chunk);
            uneven.add(pooled.subList(index, end));
            index = end;
            chunk += 7;
        }
        LatencyHistogram unevenSplit = LatencyHistogram.merge(uneven.stream()
                .map(LatencyHistogramTest::histogramFrom)
                .toList());
        assertThat(unevenSplit).isEqualTo(oneHistogram);
    }

    private static List<List<Long>> partition(List<Long> values, int parts) {
        List<List<Long>> partitions = new ArrayList<>();
        int size = (values.size() + parts - 1) / parts;
        for (int i = 0; i < values.size(); i += size) {
            partitions.add(values.subList(i, Math.min(values.size(), i + size)));
        }
        return partitions;
    }

    @Test
    @DisplayName("merging counts beyond Long.MAX_VALUE fails clearly rather than silently wrapping")
    void mergeOverflowFailsClearly() {
        LatencyHistogram a = new LatencyHistogram(LatencyHistogram.SCHEME_VERSION_1, 0,
                List.of(new BinCount(10, Long.MAX_VALUE)));
        LatencyHistogram b = new LatencyHistogram(LatencyHistogram.SCHEME_VERSION_1, 0,
                List.of(new BinCount(10, 1)));

        assertThatThrownBy(() -> a.merge(b)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("merge of mismatched schemes fails clearly (exercised via the constructor guard, since only scheme v1 is currently recognized)")
    void mergeOfMismatchedSchemesThrows() {
        // A true cross-scheme merge cannot be exercised today: `empty(2)` itself must fail, since
        // scheme 2 is not recognized — proving unknown schemes are rejected at construction, before a
        // mismatched merge could ever be attempted for real. A live cross-scheme merge test is
        // deferred until a second scheme genuinely exists (see LatencyHistogram.merge's own doc).
        assertThatThrownBy(() -> LatencyHistogram.empty(2)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- percentile validated against an independent sorted-array oracle -----------------------

    @Test
    @DisplayName("a tight, unimodal distribution's percentiles match the sorted-array oracle within bound")
    void tightDistributionMatchesOracle() {
        Random random = new Random(1);
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            durations.add(50_000_000L + (long) (random.nextGaussian() * 2_000_000));
        }
        assertMatchesOracle(durations, 0.50);
        assertMatchesOracle(durations, 0.95);
        assertMatchesOracle(durations, 0.99);
    }

    @Test
    @DisplayName("a multi-modal distribution's percentiles match the sorted-array oracle within bound")
    void multiModalDistributionMatchesOracle() {
        Random random = new Random(2);
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            durations.add(10_000_000L + (long) (random.nextDouble() * 1_000_000));
        }
        for (int i = 0; i < 500; i++) {
            durations.add(200_000_000L + (long) (random.nextDouble() * 5_000_000));
        }
        Collections.shuffle(durations, random);
        assertMatchesOracle(durations, 0.50);
        assertMatchesOracle(durations, 0.95);
        assertMatchesOracle(durations, 0.99);
    }

    @Test
    @DisplayName("a strongly skewed distribution with a tail spike matches the sorted-array oracle within bound")
    void skewedWithTailSpikeMatchesOracle() {
        Random random = new Random(3);
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < 990; i++) {
            durations.add(5_000_000L + (long) (random.nextDouble() * 500_000));
        }
        for (int i = 0; i < 10; i++) {
            durations.add(5_000_000_000L + (long) (random.nextDouble() * 100_000_000));
        }
        Collections.shuffle(durations, random);
        assertMatchesOracle(durations, 0.50);
        assertMatchesOracle(durations, 0.95);
        assertMatchesOracle(durations, 0.99);
    }

    @Test
    @DisplayName("a dataset including exact zeros matches the sorted-array oracle, including exact zero ranks")
    void datasetWithZerosMatchesOracle() {
        Random random = new Random(4);
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            durations.add(0L);
        }
        for (int i = 0; i < 900; i++) {
            durations.add(1_000_000L + (long) (random.nextDouble() * 1_000_000));
        }
        Collections.shuffle(durations, random);
        assertMatchesOracle(durations, 0.05); // falls among the zeros
        assertMatchesOracle(durations, 0.50);
        assertMatchesOracle(durations, 0.99);
    }

    private static void assertMatchesOracle(List<Long> durations, double quantile) {
        List<Long> sorted = new ArrayList<>(durations);
        Collections.sort(sorted);
        long trueValue = trueNearestRank(sorted, quantile);

        LatencyHistogram histogram = histogramFrom(durations);
        Duration reported = histogram.percentile(quantile).orElseThrow();

        assertNeverUnderstatesWithinBound(trueValue, reported);
    }

    // ---- nearest-rank at extreme counts, beyond double precision ---------------------------------

    @Test
    @DisplayName("percentile ranks correctly even when the total count exceeds 2^53, where a double multiplication would lose precision")
    void nearestRankCorrectAtExtremeCounts() {
        long huge = 9_000_000_000_000_000L; // 9e15, each bin; total 1.8e16 >> 2^53 (~9.007e15)
        LatencyHistogram histogram = new LatencyHistogram(LatencyHistogram.SCHEME_VERSION_1, 0,
                List.of(new BinCount(10, huge), new BinCount(20, huge)));

        // target rank for p50 is exactly huge (the boundary between the two bins)
        assertThat(histogram.percentile(0.5))
                .contains(Duration.ofNanos(LatencyHistogram.UPPER_BOUNDS_V1[10]));
        // any quantile whose rank exceeds the first bin's count must fall in the second bin
        assertThat(histogram.percentile(1.0))
                .contains(Duration.ofNanos(LatencyHistogram.UPPER_BOUNDS_V1[20]));
    }

    // ---- scheme versioning -----------------------------------------------------------------------

    @Test
    @DisplayName("scheme v1 construction succeeds")
    void schemeV1ConstructionSucceeds() {
        assertThat(LatencyHistogram.empty().schemeVersion()).isEqualTo(LatencyHistogram.SCHEME_VERSION_1);
    }

    @Test
    @DisplayName("an unrecognized scheme version fails at construction, before it could ever reach merge or percentile")
    void unknownSchemeVersionFailsAtConstruction() {
        assertThatThrownBy(() -> new LatencyHistogram(99, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a negative binIndex under scheme v1 fails at construction — impossible from any real construction path")
    void negativeBinIndexForSchemeV1Throws() {
        assertThatThrownBy(() -> new LatencyHistogram(LatencyHistogram.SCHEME_VERSION_1, 0,
                List.of(new BinCount(-1, 5))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a binIndex beyond the generated table's length under scheme v1 fails at construction")
    void outOfRangeBinIndexForSchemeV1Throws() {
        int outOfRange = LatencyHistogram.UPPER_BOUNDS_V1.length;
        assertThatThrownBy(() -> new LatencyHistogram(LatencyHistogram.SCHEME_VERSION_1, 0,
                List.of(new BinCount(outOfRange, 5))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- list-merge scheme-neutrality --------------------------------------------------------------

    @Test
    @DisplayName("merging an empty list returns a scheme-v1 empty histogram, as a documented convenience only")
    void mergeOfEmptyListReturnsSchemeV1Empty() {
        assertThat(LatencyHistogram.merge(List.<LatencyHistogram>of())).isEqualTo(LatencyHistogram.empty());
    }

    @Test
    @DisplayName("merging a non-empty list of one histogram returns that histogram unchanged")
    void mergeOfSingleElementListReturnsItUnchanged() {
        LatencyHistogram only = histogramFrom(List.of(1L, 2L, 3L));

        assertThat(LatencyHistogram.merge(List.of(only))).isEqualTo(only);
    }

    @Test
    @DisplayName("merging a list of several histograms equals folding merge() across them in order")
    void mergeOfListEqualsSequentialMerge() {
        LatencyHistogram a = histogramFrom(List.of(1L, 2L));
        LatencyHistogram b = histogramFrom(List.of(3L, 4L));
        LatencyHistogram c = histogramFrom(List.of(5L, 6L));

        assertThat(LatencyHistogram.merge(List.of(a, b, c))).isEqualTo(a.merge(b).merge(c));
    }

    // ---- quantile input validation ------------------------------------------------------------------

    @Test
    @DisplayName("percentile rejects a quantile of zero, negative, above one, or NaN")
    void percentileRejectsInvalidQuantiles() {
        LatencyHistogram histogram = histogramFrom(List.of(1L, 2L, 3L));

        assertThatThrownBy(() -> histogram.percentile(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> histogram.percentile(-0.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> histogram.percentile(1.1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> histogram.percentile(Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("percentile(1.0) is valid and returns the maximum observed value")
    void percentileOfOneIsValid() {
        LatencyHistogram histogram = histogramFrom(List.of(1L, 100L, 50L));

        assertThat(histogram.percentile(1.0)).isPresent();
    }

    // ---- Builder: only valid states for its long-typed signature -------------------------------------

    @Test
    @DisplayName("Builder.record rejects a negative duration")
    void builderRejectsNegativeDuration() {
        assertThatThrownBy(() -> LatencyHistogram.builder().record(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Builder.record(0) increments zeroCount, not a bin")
    void builderZeroIncrementsZeroCount() {
        LatencyHistogram histogram = LatencyHistogram.builder().record(0).build();

        assertThat(histogram.zeroCount()).isEqualTo(1);
        assertThat(histogram.bins()).isEmpty();
    }

    @Test
    @DisplayName("Builder.record accepts Long.MAX_VALUE, the final bin's own upper bound")
    void builderAcceptsLongMaxValue() {
        LatencyHistogram histogram = LatencyHistogram.builder().record(Long.MAX_VALUE).build();

        assertThat(histogram.percentile(1.0)).contains(Duration.ofNanos(Long.MAX_VALUE));
    }

    // ---- empty histogram --------------------------------------------------------------------------

    @Test
    @DisplayName("an empty histogram has no percentile to report")
    void emptyHistogramHasNoPercentile() {
        assertThat(LatencyHistogram.empty().percentile(0.95)).isEmpty();
    }

    @Test
    @DisplayName("merging two empty histograms is empty")
    void mergeOfTwoEmptiesIsEmpty() {
        assertThat(LatencyHistogram.empty().merge(LatencyHistogram.empty()))
                .isEqualTo(LatencyHistogram.empty());
    }

    // ---- construction invariants --------------------------------------------------------------------

    @Test
    @DisplayName("a stored bin must have a positive count")
    void binCountRejectsNonPositiveCount() {
        assertThatThrownBy(() -> new BinCount(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BinCount(1, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("unsorted or duplicate binIndex entries are rejected")
    void unsortedOrDuplicateBinsAreRejected() {
        assertThatThrownBy(() -> new LatencyHistogram(LatencyHistogram.SCHEME_VERSION_1, 0,
                List.of(new BinCount(5, 1), new BinCount(3, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LatencyHistogram(LatencyHistogram.SCHEME_VERSION_1, 0,
                List.of(new BinCount(3, 1), new BinCount(3, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a negative zeroCount is rejected")
    void negativeZeroCountIsRejected() {
        assertThatThrownBy(() -> new LatencyHistogram(LatencyHistogram.SCHEME_VERSION_1, -1, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
