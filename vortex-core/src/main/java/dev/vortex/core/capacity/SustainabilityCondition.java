package dev.vortex.core.capacity;

/**
 * The five things that must all hold before a level may be called a sustainable capacity.
 *
 * <p>Each is separately checkable and separately falsifiable, which is what makes the figure
 * defensible in a review. A capacity that frequently declines to exist is the cost of one that means
 * something when it does.
 */
public enum SustainabilityCondition {

    /** The offered load was actually generated — run quality permits the claim. */
    OFFERED_LOAD_WAS_GENERATED("The offered load was generated"),

    /** Every declared objective was met at this level. */
    OBJECTIVES_WERE_MET("Every objective was met"),

    /** It was held for the declared sustain duration, rather than passed through by a ramp. */
    HELD_FOR_THE_SUSTAIN_DURATION("It was held long enough"),

    /** Achieved throughput tracked offered load — at or below the throughput ceiling. */
    THROUGHPUT_TRACKED_OFFERED_LOAD("Throughput tracked the offered load"),

    /** No observed resource reached its declared limit, where such telemetry exists. */
    NO_RESOURCE_REACHED_ITS_LIMIT("No observed resource reached its limit");

    private final String label;

    SustainabilityCondition(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
