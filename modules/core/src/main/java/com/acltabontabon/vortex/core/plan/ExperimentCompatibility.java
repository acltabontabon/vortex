package com.acltabontabon.vortex.core.plan;

import java.util.List;

/**
 * Whether two executions tested the same experiment, and — when they did not — exactly what
 * differed.
 *
 * <p>Produced by {@link ExperimentIdentity#compare}, which derives both this and the fingerprint
 * from one list of dimensions. The two therefore cannot disagree: a pair of plans is compatible if
 * and only if it shares a fingerprint, and every incompatibility is accompanied by a sentence
 * naming the dimension that caused it.
 *
 * <p>The differences are written for a person, not for a log. "fingerprint mismatch" tells an
 * engineer nothing they can act on; "offered load changed from 100 requests/sec to 150
 * requests/sec" tells them why last week's number is not this week's baseline.
 *
 * @param compatible  whether a regression verdict may be computed from these two runs
 * @param differences what differs, in plain language, in dimension order
 */
public record ExperimentCompatibility(boolean compatible, List<String> differences) {

    public ExperimentCompatibility {
        differences = differences == null ? List.of() : List.copyOf(differences);
    }

    /** The two plans describe the same experiment. */
    public static final ExperimentCompatibility COMPATIBLE =
            new ExperimentCompatibility(true, List.of());

    /**
     * Compatible when nothing differs, incompatible otherwise.
     *
     * <p>One factory rather than two, so a caller cannot claim compatibility while holding a list
     * of reasons it is not.
     */
    public static ExperimentCompatibility of(List<String> differences) {
        return differences == null || differences.isEmpty()
                ? COMPATIBLE
                : new ExperimentCompatibility(false, differences);
    }

    /** This result with further differences added, which can only make it incompatible. */
    public ExperimentCompatibility and(List<String> more) {
        if (more == null || more.isEmpty()) {
            return this;
        }
        List<String> combined = new java.util.ArrayList<>(differences);
        combined.addAll(more);
        return of(combined);
    }
}
