package dev.vortex.core.analysis;

/**
 * What kind of claim a finding is making, independent of how confidently it is made.
 *
 * <p>Confidence answers "how sure"; type answers "sure of what kind of thing". A load test
 * observes association, not cause, and this is the field that keeps a hypothesis from reading as
 * a measured fact once it reaches a report. It is unrelated to {@link
 * dev.vortex.core.evidence.FindingLevel}, which grades a <em>deterministic</em> finding's severity,
 * and to {@link EvidenceStrength}, which grades how well corroborated a deterministic breakpoint
 * is — both of those exist one tier below where an AI ever reasons.
 */
public enum FindingType {

    /** A measured fact, stated without inference — the evidence says this directly. */
    OBSERVATION("Observation", Confidence.HIGH),

    /** Two things moved together. Not a claim about which caused which. */
    CORRELATION("Correlation", Confidence.MEDIUM),

    /** A plausible explanation that would need another measurement or experiment to confirm. */
    HYPOTHESIS("Hypothesis", Confidence.LOW),

    /** Not a claim about the system at all — a statement about what could not be determined. */
    LIMITATION("Limitation", Confidence.LOW);

    private final String label;
    private final Confidence maxConfidence;

    FindingType(String label, Confidence maxConfidence) {
        this.label = label;
        this.maxConfidence = maxConfidence;
    }

    public String label() {
        return label;
    }

    /**
     * The most a finding of this type may claim to be sure of.
     *
     * <p>A hypothesis carrying {@code HIGH} confidence is the specific failure mode this exists to
     * stop: an unconfirmed explanation dressed up as a settled one. Enforced by {@link
     * dev.vortex.core.application.EpistemicIntegrityValidator}, not left to the prompt alone.
     */
    public Confidence maxConfidence() {
        return maxConfidence;
    }

    /**
     * Parses a type from model output, defaulting to the most conservative type rather than
     * rejecting the finding outright — the same stance {@link Confidence#parse(String)} takes.
     */
    public static FindingType parse(String raw) {
        if (raw == null) {
            return HYPOTHESIS;
        }
        return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "OBSERVATION" -> OBSERVATION;
            case "CORRELATION" -> CORRELATION;
            case "LIMITATION" -> LIMITATION;
            default -> HYPOTHESIS;
        };
    }
}
