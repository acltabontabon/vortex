package dev.vortex.core.resource;

import dev.vortex.core.metrics.MetricUnit;
import java.util.Objects;

/**
 * What a resource measurement is a fraction of.
 *
 * <p>"Memory 3.2 GB" is a fact about nothing until a limit is beside it. "3.2 GB of a 4 GB container
 * limit" is evidence. Where a provider publishes the limit it is carried here; where it does not,
 * the limit is <em>absent</em> and Vortex must not compute a percentage against a number it guessed.
 * That is the same rule that already governs headroom, applied one level down.
 *
 * @param value      the constraint the measurement is relative to
 * @param unit       the unit it is expressed in, which must be the measurement's own unit
 * @param basis      where the number came from, so a reader can weigh it
 * @param describedAs how to say it in a sentence, e.g. "the JVM's maximum heap"
 */
public record ResourceLimit(double value, MetricUnit unit, LimitBasis basis, String describedAs) {

    public ResourceLimit {
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(basis, "basis");
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalArgumentException(
                    "a resource limit must be a positive, finite quantity; a limit of " + value
                            + " would make every measurement against it meaningless");
        }
        describedAs = describedAs == null || describedAs.isBlank()
                ? basis.label()
                : describedAs;
    }

    /**
     * The limit a percentage carries by definition.
     *
     * <p>A utilisation already expressed as a percentage is a fraction of something the provider
     * divided by, so a hundred is a fact about the unit rather than a guess about the resource.
     */
    public static ResourceLimit inherentToPercentage() {
        return new ResourceLimit(100, MetricUnit.PERCENT, LimitBasis.INHERENT_TO_UNIT,
                "the definition of a percentage");
    }

    /** The limit a ratio carries by definition: one whole. */
    public static ResourceLimit inherentToRatio() {
        return new ResourceLimit(1, MetricUnit.RATIO, LimitBasis.INHERENT_TO_UNIT,
                "the definition of a ratio");
    }

    /**
     * The limit implied by a proportion's unit, where there is one.
     *
     * <p>Absent for every other unit, and deliberately so. Bytes, milliseconds and counts carry no
     * limit in their unit, and inventing one for them would be exactly the guess this type exists to
     * refuse. Providers that report a proportion do not all agree on which of the two forms to use,
     * and a limit expressed in the wrong one silently never matches — a resource that could never
     * reach its limit reads identically to one that never did.
     */
    public static ResourceLimit inherentTo(MetricUnit unit) {
        return switch (unit) {
            case PERCENT -> inherentToPercentage();
            case RATIO -> inherentToRatio();
            case MILLISECONDS, SECONDS, REQUESTS_PER_SECOND, VIRTUAL_USERS, COUNT, BYTES -> null;
        };
    }

    /** A limit the provider actually published, such as a container quota or a pool's maximum. */
    public static ResourceLimit published(double value, MetricUnit unit, String describedAs) {
        return new ResourceLimit(value, unit, LimitBasis.PUBLISHED_BY_PROVIDER, describedAs);
    }

    public String display() {
        return unit.symbol().isBlank()
                ? trim(value)
                : trim(value) + unit.symbol();
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
