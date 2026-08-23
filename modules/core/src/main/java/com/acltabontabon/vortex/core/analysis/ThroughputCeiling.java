package com.acltabontabon.vortex.core.analysis;

import com.acltabontabon.vortex.core.shared.LoadLevel;
import java.util.Objects;
import java.util.Optional;

/**
 * The level beyond which offering more load stops producing more throughput.
 *
 * <p>A different finding from the objective breakpoint, and they routinely disagree. A service can
 * breach a 200 ms p95 objective at 300 requests/sec while its throughput ceiling is 900 and its CPU
 * never passes 40% — that is a service which is <em>slow</em>, not a service which is <em>full</em>,
 * and one number cannot say both. Collapsing them throws away the distinction that tells an engineer
 * whether to tune the code, add instances, or renegotiate the objective.
 *
 * <h2>A ceiling is not a shortfall</h2>
 * A shortfall is a single stage's property; a ceiling is a derivative. A shortfall present at every
 * stage including the first is a generator symptom, and treating the two as the same thing is
 * precisely the error that made system saturation blind about whose symptoms it was corroborating.
 *
 * @param level         the last level that still responded to more offered load
 * @param firstFlatLevel the first level at which it stopped
 * @param explanation   what was observed, or why nothing could be concluded
 */
public record ThroughputCeiling(Status status, LoadLevel level, LoadLevel firstFlatLevel,
                                EvidenceStrength strength, int stagesObserved, String explanation) {

    public enum Status {

        /** Offered load rose, achieved throughput did not, and latency did — a queue forming. */
        OBSERVED("Observed"),

        /**
         * The same shape, on a run whose own generator was constrained.
         *
         * <p>Evidence about the machine Vortex runs on, and deliberately not reported as a property
         * of the service at all. Naming it a service ceiling would be the exact substitution ADR-038
         * exists to prevent, arriving through a different door.
         */
        GENERATOR_BOUND("Attributable to the load generator"),

        /** Throughput kept up with everything the run offered. A real and useful answer. */
        NOT_OBSERVED("Not observed"),

        /** Too few levels, or a workload that does not control an arrival rate. */
        NOT_EVALUATED("Not evaluated");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public ThroughputCeiling {
        Objects.requireNonNull(status, "status");
        strength = strength == null ? EvidenceStrength.INSUFFICIENT : strength;
        explanation = explanation == null ? "" : explanation;
    }

    public static ThroughputCeiling notEvaluated(String why) {
        return new ThroughputCeiling(Status.NOT_EVALUATED, null, null,
                EvidenceStrength.INSUFFICIENT, 0, why);
    }

    public static ThroughputCeiling notObserved(int stagesObserved) {
        return new ThroughputCeiling(Status.NOT_OBSERVED, null, null,
                EvidenceStrength.INSUFFICIENT, stagesObserved,
                "Achieved throughput kept pace with every level this run offered, so no ceiling was "
                        + "reached within the range tested.");
    }

    /** Whether this may be stated as a property of the service under test. */
    public boolean isQuotable() {
        return status == Status.OBSERVED;
    }

    public Optional<LoadLevel> levelIfPresent() {
        return status == Status.OBSERVED ? Optional.ofNullable(level) : Optional.empty();
    }

    public String describe() {
        return switch (status) {
            case OBSERVED -> "Throughput stopped responding to offered load above "
                    + level.displayWithUnit() + ". " + explanation;
            case GENERATOR_BOUND -> "Throughput flattened, and the load generator was itself "
                    + "constrained, so this says nothing about the service. " + explanation;
            case NOT_OBSERVED, NOT_EVALUATED -> explanation;
        };
    }
}
