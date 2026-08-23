package dev.vortex.core.shared;

/**
 * A <em>relative</em> weight within a traffic mix.
 *
 * <p>Weights are relative positive integers, not percentages. {@code 60/30/10} and {@code 6/3/1}
 * describe the same traffic mix. Vortex normalises them for display and for rate allocation, which
 * means users are never forced to make their numbers add up to exactly 100.
 */
public record Weight(int value) implements Comparable<Weight> {

    public Weight {
        if (value <= 0) {
            throw new IllegalArgumentException("weight must be greater than 0 but was " + value);
        }
        if (value > 1_000_000) {
            throw new IllegalArgumentException("weight must be at most 1000000 but was " + value);
        }
    }

    public static Weight of(int value) {
        return new Weight(value);
    }

    @Override
    public int compareTo(Weight other) {
        return Integer.compare(value, other.value);
    }

    @Override
    public String toString() {
        return Integer.toString(value);
    }
}
