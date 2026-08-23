package dev.vortex.core.shared;

/**
 * A latency percentile such as p50, p95 or p99.
 *
 * <p>Percentiles are held as basis points (hundredths of a percent) so that p99.9 is representable
 * without floating point ambiguity: p95 is 9500, p99.9 is 9990.
 */
public record Percentile(int basisPoints) implements Comparable<Percentile> {

    public static final Percentile P50 = of(50);
    public static final Percentile P90 = of(90);
    public static final Percentile P95 = of(95);
    public static final Percentile P99 = of(99);

    public Percentile {
        if (basisPoints <= 0 || basisPoints >= 10_000) {
            throw new IllegalArgumentException(
                    "percentile must be strictly between 0 and 100 but was " + (basisPoints / 100.0));
        }
    }

    public static Percentile of(double percent) {
        double bp = percent * 100.0;
        long rounded = Math.round(bp);
        if (Math.abs(bp - rounded) > 1e-6) {
            throw new IllegalArgumentException(
                    "percentile must have at most two decimal places but was " + percent);
        }
        return new Percentile((int) rounded);
    }

    public double asPercent() {
        return basisPoints / 100.0;
    }

    /** Canonical short label used in configuration, metrics and the UI: {@code p95}, {@code p99.9}. */
    public String label() {
        if (basisPoints % 100 == 0) {
            return "p" + (basisPoints / 100);
        }
        return "p" + (basisPoints / 100.0);
    }

    @Override
    public int compareTo(Percentile other) {
        return Integer.compare(basisPoints, other.basisPoints);
    }

    @Override
    public String toString() {
        return label();
    }
}
