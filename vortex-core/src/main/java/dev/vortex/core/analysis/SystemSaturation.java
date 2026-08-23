package dev.vortex.core.analysis;

import dev.vortex.core.shared.LoadLevel;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Evidence that the system stopped coping, as distinct from stopping meeting its objectives.
 *
 * <p>An SLO breakpoint and a system breakpoint are different things and Vortex refuses to conflate
 * them. The first is deterministic: a threshold was crossed. The second is contextual — it means
 * severe saturation, runaway errors, an inability to sustain the offered load, or a process that
 * fell over — and the signals that indicate it are noisy.
 *
 * <p>So this type is deliberately conservative. It reports a <em>range</em> supported by named
 * signals, or it reports that saturation was not established. A precise-looking
 * "system breakpoint: 147/sec" derived from a single heuristic would be the most confidently wrong
 * number in the product, and "not established by this test" is very often the correct answer.
 *
 * @param status      whether saturation was observed at all
 * @param lowerBound  the lowest level at which saturation signals appeared
 * @param upperBound  the highest level reached before the run ended
 * @param signals     the corroborating signals, named
 * @param strength    how well supported the finding is
 * @param explanation plain-language statement of what was and was not established
 */
public record SystemSaturation(
        Status status,
        LoadLevel lowerBound,
        LoadLevel upperBound,
        List<String> signals,
        EvidenceStrength strength,
        String explanation) {

    public enum Status {
        /** Multiple signals indicate the system stopped coping within a bounded range. */
        OBSERVED,
        /** The run produced no convincing saturation signals. This is a normal, useful outcome. */
        NOT_ESTABLISHED
    }

    /** How many independent signals must agree before saturation is reported at all. */
    public static final int MINIMUM_CORROBORATING_SIGNALS = 2;

    public SystemSaturation {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(strength, "strength");
        signals = signals == null ? List.of() : List.copyOf(signals);
        explanation = explanation == null ? "" : explanation;
    }

    public static SystemSaturation notEstablished(String explanation) {
        return new SystemSaturation(Status.NOT_ESTABLISHED, null, null, List.of(),
                EvidenceStrength.INSUFFICIENT, explanation);
    }

    public boolean wasObserved() {
        return status == Status.OBSERVED;
    }

    public Optional<LoadLevel> lowerBoundIfPresent() {
        return Optional.ofNullable(lowerBound);
    }

    public Optional<LoadLevel> upperBoundIfPresent() {
        return Optional.ofNullable(upperBound);
    }

    /**
     * Display form, deliberately a range rather than a point: {@code approximately 145-150
     * requests/sec}, or {@code Not established by this test}.
     */
    public String describe() {
        if (status == Status.NOT_ESTABLISHED) {
            return "Not established by this test";
        }
        if (lowerBound == null || upperBound == null) {
            return "Observed, but the level could not be bounded";
        }
        if (lowerBound.asDouble() == upperBound.asDouble()) {
            return "approximately " + lowerBound.displayWithUnit();
        }
        return "approximately " + lowerBound.display() + "–" + upperBound.displayWithUnit();
    }
}
