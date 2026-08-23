package dev.vortex.core.validity;

import dev.vortex.core.shared.LoadLevel;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One thing that was wrong with the experiment, and the measurement that says so.
 *
 * <h2>The statement is not optional, and neither is its number</h2>
 * A finding never says only <em>run too short</em>. It names the figure an engineer would need in
 * order to argue with the qualification — which is the same figure they need in order to fix it:
 * "held for 2m; an average-load test requires 5m before a level is quotable as capacity". A
 * qualification somebody cannot argue with is one they cannot act on either.
 *
 * @param reason      which rule fired
 * @param effect      what it does to the conclusions
 * @param statement   what was measured and which threshold it crossed, in a sentence
 * @param evidenceIds the measurements behind it, minted through {@code EvidenceIds} so a reader can
 *                    resolve them. A finding with no citations is an opinion
 * @param fromLevel   the level at and above which this withholds claims, when it is level-specific.
 *                    Absent when the finding is about the run as a whole
 */
public record ValidityFinding(ValidityReason reason, ValidityEffect effect, String statement,
                              List<String> evidenceIds, LoadLevel fromLevel) {

    public ValidityFinding {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(effect, "effect");
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        if (statement == null || statement.isBlank()) {
            throw new IllegalArgumentException(
                    "a validity finding must state what it measured and the threshold it crossed; "
                            + reason + " on its own tells an engineer nothing they can act on");
        }
    }

    /** A finding about the run as a whole rather than about one level within it. */
    public ValidityFinding(ValidityReason reason, ValidityEffect effect, String statement,
            List<String> evidenceIds) {
        this(reason, effect, statement, evidenceIds, null);
    }

    public Optional<LoadLevel> fromLevelIfPresent() {
        return Optional.ofNullable(fromLevel);
    }

    /**
     * Whether this finding withholds a capacity claim at the given level.
     *
     * <p>A level-specific finding withholds at and above the level it names and leaves lower levels
     * alone: a generator that fell behind at 900 requests/sec says nothing about what happened at
     * 300, and refusing the lower figure too would discard evidence the run genuinely produced.
     */
    public boolean withholdsCapacityAt(LoadLevel level) {
        if (!effect.withholdsCapacity()) {
            return false;
        }
        if (fromLevel == null || level == null) {
            return true;
        }
        return level.asDouble() >= fromLevel.asDouble();
    }
}
