package com.acltabontabon.vortex.core.data;

/**
 * The values Vortex knows how to produce.
 *
 * <p>Deliberately small and closed. The alternative — a Faker-style expression language — turns
 * request data back into a program, which is the thing this feature exists to avoid. When a
 * generator is genuinely missing it is added here, once, with a name, a documented meaning and a
 * test; it does not arrive as a string somebody typed into a field.
 *
 * <p>Every generator has an exact, documented rendering. "A timestamp" is not a specification;
 * "RFC 3339 in UTC, to the millisecond" is.
 */
public enum Generator {

    /** A random version 4 UUID, e.g. {@code 8f14e45f-ceea-4e0a-9d2c-8b13f04b0f1a}. */
    UUID("uuid", "UUID", "a random UUID (version 4)"),

    /** The current instant, RFC 3339 in UTC to the millisecond, e.g. {@code 2026-08-22T09:41:07.318Z}. */
    TIMESTAMP("timestamp", "Timestamp", "the current instant, RFC 3339 in UTC"),

    /** The current date in UTC, e.g. {@code 2026-08-22}. */
    DATE("date", "Date", "the current date in UTC"),

    /** A random integer within a configured inclusive range. */
    RANDOM_INTEGER("random-integer", "Random integer", "a random integer in a range you choose"),

    /** A random lowercase alphanumeric string of a configured length. */
    RANDOM_STRING("random-string", "Random string", "a random alphanumeric string"),

    /** A syntactically valid, non-routable address, e.g. {@code vortex-4821@example.com}. */
    EMAIL("email", "Email", "a unique address at example.com, which is reserved and cannot receive mail"),

    /** A digit string shaped like a phone number. Shaped like one — it belongs to nobody. */
    PHONE("phone", "Phone number", "a digit string shaped like a phone number"),

    /**
     * A number that increases by one for every execution of this operation.
     *
     * <p>Unique across every virtual user running the operation, not merely within one. It compiles
     * to k6's own iteration counter rather than to a variable Vortex maintains, because a counter
     * each VU kept privately would repeat every value once per VU — exactly the bug this generator
     * is usually chosen to avoid.
     *
     * <p>The sequence is per operation. Two operations each using a sequence produce two independent
     * series, both starting from the same place.
     */
    SEQUENCE("sequence", "Sequence", "a number increasing by one per request, unique across virtual users");

    private final String key;
    private final String label;
    private final String meaning;

    Generator(String key, String label, String meaning) {
        this.key = key;
        this.label = label;
        this.meaning = meaning;
    }

    /** The token used in {@code vortex.yaml}. */
    public String key() {
        return key;
    }

    public static Generator fromKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "a generated value must say what to generate. Vortex generates: " + keys());
        }
        String normalised = value.trim().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        for (Generator generator : values()) {
            if (generator.key.equals(normalised)) {
                return generator;
            }
        }
        throw new IllegalArgumentException(
                "unknown generator '" + value + "'. Vortex generates: " + keys()
                        + ". The set is deliberately small — a value it cannot produce is usually one "
                        + "that belongs in a dataset, because it has to be a value your service will "
                        + "actually accept.");
    }

    /** Every generator's key, as a sentence, for an error message. */
    public static String keys() {
        return java.util.Arrays.stream(values()).map(Generator::key)
                .reduce((a, b) -> a + ", " + b).orElse("");
    }

    /** How this generator is named in the interface. */
    public String label() {
        return label;
    }

    /** What it produces, in a sentence, for a tooltip or a report. */
    public String meaning() {
        return meaning;
    }

    /** Whether this generator reads the configured integer range. */
    public boolean usesRange() {
        return this == RANDOM_INTEGER;
    }

    /** Whether this generator reads the configured length. */
    public boolean usesLength() {
        return this == RANDOM_STRING;
    }
}
