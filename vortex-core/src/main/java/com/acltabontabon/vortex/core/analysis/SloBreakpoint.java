package com.acltabontabon.vortex.core.analysis;

import com.acltabontabon.vortex.core.shared.LoadLevel;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The level at which the service first stopped meeting an agreed objective.
 *
 * <p>This is the deterministic half of "where is the limit?": the first workload stage whose
 * measurements violated a threshold. It is a statement about an <em>agreement</em>, not about the
 * machine — raise the p95 objective from 500 ms to 800 ms and the SLO breakpoint moves, while the
 * service behaves identically.
 *
 * <p>The level carries its own unit, because a breakpoint at 147 requests/sec and one at 147 virtual
 * users are different findings that would be indistinguishable as bare numbers.
 *
 * @param level                 the stage level at which the first violation was observed
 * @param highestCompliantLevel the highest stage level that still met every objective
 * @param violatedThresholdIds  which objectives were violated at that stage
 * @param strength              how well supported the finding is
 * @param stagesObserved        how many stages contributed evidence
 */
public record SloBreakpoint(
        LoadLevel level,
        LoadLevel highestCompliantLevel,
        List<String> violatedThresholdIds,
        EvidenceStrength strength,
        int stagesObserved) {

    public SloBreakpoint {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(strength, "strength");
        violatedThresholdIds = violatedThresholdIds == null ? List.of() : List.copyOf(violatedThresholdIds);
    }

    public Optional<LoadLevel> highestCompliantLevelIfPresent() {
        return Optional.ofNullable(highestCompliantLevel);
    }

    public String describe() {
        return "Objectives were first violated at " + level.displayWithUnit();
    }
}
