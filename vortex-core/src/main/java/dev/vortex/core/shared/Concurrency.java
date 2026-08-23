package dev.vortex.core.shared;

/**
 * A number of concurrent virtual users: the control quantity of a <em>closed</em> workload.
 *
 * <p>Whole numbers, because a virtual user is a thing that either exists or does not. k6 schedules
 * VUs as integers and there is no meaningful interpretation of 12.5 of them.
 *
 * <p>Concurrency is not a throughput. Fifty virtual users against a 100 ms operation produce roughly
 * 500 requests/sec; against a 2 s operation, roughly 25 — and the figure moves during the run as the
 * service slows down. That is precisely why {@link RequestsPerSecond} and this type are separate
 * members of {@link LoadLevel} with no conversion between them.
 *
 * @see RequestsPerSecond
 */
public record Concurrency(int vus) implements LoadLevel, Comparable<Concurrency> {

    /** Above this, a single k6 process is the bottleneck rather than the service under test. */
    public static final int MAXIMUM = 100_000;

    public Concurrency {
        if (vus <= 0) {
            throw new IllegalArgumentException(
                    "concurrency must be at least 1 virtual user but was " + vus);
        }
        if (vus > MAXIMUM) {
            throw new IllegalArgumentException(
                    "concurrency must be at most " + MAXIMUM + " virtual users but was " + vus
                            + ". Beyond this the load generator becomes the bottleneck rather than "
                            + "the service under test.");
        }
    }

    public static Concurrency of(int vus) {
        return new Concurrency(vus);
    }

    @Override
    public double asDouble() {
        return vus;
    }

    @Override
    public String display() {
        return Integer.toString(vus);
    }

    @Override
    public String unit() {
        return "VUs";
    }

    @Override
    public int compareTo(Concurrency other) {
        return Integer.compare(vus, other.vus);
    }

    @Override
    public String toString() {
        return displayWithUnit();
    }
}
