package dev.vortex.core.data;

import java.util.Objects;

/**
 * A value Vortex produces, from a closed set of generators.
 *
 * <p>The generator says <em>what shape</em> the value has; the lifecycle says <em>how often</em> a
 * new one is produced. Both matter, and conflating them is the usual way this goes wrong: a UUID
 * generated once per virtual user is a perfectly well-formed UUID that turns every request after the
 * first into a duplicate submission.
 *
 * <p>{@code range} and {@code length} are read only by the generators that declare they use them
 * ({@link Generator#usesRange()}, {@link Generator#usesLength()}). They are validated here rather
 * than ignored, so a configuration that sets a range on a UUID is rejected instead of quietly
 * meaning nothing.
 *
 * @param generator what to produce
 * @param lifecycle how often to produce it
 * @param minimum   inclusive lower bound for {@link Generator#RANDOM_INTEGER}
 * @param maximum   inclusive upper bound for {@link Generator#RANDOM_INTEGER}
 * @param length    character count for {@link Generator#RANDOM_STRING}
 */
public record GeneratedValue(
        Generator generator,
        ValueLifecycle lifecycle,
        long minimum,
        long maximum,
        int length) implements RequestValue {

    /** Bounds a random string, so a generator cannot be asked for a megabyte of text per request. */
    public static final int MAX_LENGTH = 512;

    static final long DEFAULT_MINIMUM = 1L;
    static final long DEFAULT_MAXIMUM = 1_000_000L;
    static final int DEFAULT_LENGTH = 12;

    public GeneratedValue {
        Objects.requireNonNull(generator, "generator");
        lifecycle = lifecycle == null ? ValueLifecycle.defaultLifecycle() : lifecycle;

        if (generator.usesRange() && minimum > maximum) {
            throw new IllegalArgumentException(
                    "a random integer cannot have a minimum of " + minimum + " above its maximum of "
                            + maximum + ". Swap them, or set them equal for a constant.");
        }
        if (generator.usesLength() && (length < 1 || length > MAX_LENGTH)) {
            throw new IllegalArgumentException(
                    "a random string must be between 1 and " + MAX_LENGTH + " characters, but "
                            + length + " was configured.");
        }
        if (generator == Generator.SEQUENCE && lifecycle == ValueLifecycle.PER_VU) {
            throw new IllegalArgumentException(
                    "a sequence cannot be generated per virtual user: every user would produce the "
                            + "same series, and the values would repeat once per user. A sequence is "
                            + "unique across the run by definition.");
        }
    }

    /** The common case: this generator, a new value for every request. */
    public static GeneratedValue of(Generator generator) {
        return of(generator, ValueLifecycle.defaultLifecycle());
    }

    public static GeneratedValue of(Generator generator, ValueLifecycle lifecycle) {
        return new GeneratedValue(generator, lifecycle, DEFAULT_MINIMUM, DEFAULT_MAXIMUM,
                DEFAULT_LENGTH);
    }

    public static GeneratedValue integerBetween(long minimum, long maximum,
            ValueLifecycle lifecycle) {
        return new GeneratedValue(Generator.RANDOM_INTEGER, lifecycle, minimum, maximum,
                DEFAULT_LENGTH);
    }

    public static GeneratedValue stringOfLength(int length, ValueLifecycle lifecycle) {
        return new GeneratedValue(Generator.RANDOM_STRING, lifecycle, DEFAULT_MINIMUM,
                DEFAULT_MAXIMUM, length);
    }

    @Override
    public String describeSource() {
        return "generated: " + generator.label() + " (" + lifecycle.label() + ")";
    }

    @Override
    public boolean isDynamic() {
        return true;
    }
}
