package com.acltabontabon.vortex.core.workload;

import java.util.Objects;
import java.util.Optional;

/**
 * Where a workload's numbers came from.
 *
 * <p>"50 requests/sec" means two very different things depending on whether it is the observed
 * production p95 over the last thirty days or a figure somebody typed while setting the project up.
 * Both are legitimate starting points; presenting them identically is not, because a capacity
 * conclusion inherits the confidence of its weakest input and nothing downstream can recover
 * provenance that was never recorded.
 *
 * <p>Vortex therefore makes the source visible next to the number rather than inferring confidence
 * it does not have. It never upgrades a manual figure by using it.
 *
 * @param kind        how the number was arrived at
 * @param detail      where it came from, e.g. a dashboard name — free text, shown verbatim
 * @param observation when the underlying observation was taken, and over what period
 * @param derivation  the arithmetic that produced this figure from an observation, stated so it can
 *                    be checked — for example "observed peak 120 × 1.5 = 180". Empty when the figure
 *                    was not calculated
 */
public record WorkloadSource(SourceKind kind, String detail, Observation observation,
        String derivation) {

    public enum SourceKind {

        /** Taken directly from observed production traffic. */
        PRODUCTION_OBSERVED("From observed production traffic"),

        /** Computed from an observation by a stated policy, such as 1.5x the observed peak. */
        DERIVED_FROM_OBSERVATION("Derived from observed production traffic"),

        /** Entered by a person. A reasonable starting point; not evidence about production. */
        MANUAL("Manually entered");

        private final String label;

        SourceKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** Whether figures from this source may support a claim about production capacity. */
        public boolean isProductionInformed() {
            return this != MANUAL;
        }
    }

    public WorkloadSource {
        Objects.requireNonNull(kind, "kind");
        detail = detail == null ? "" : detail.trim();
        derivation = derivation == null ? "" : derivation.trim();
        observation = observation == null ? Observation.unknown() : observation;
    }

    public static WorkloadSource manual() {
        return new WorkloadSource(SourceKind.MANUAL, "", Observation.unknown(), "");
    }

    public static WorkloadSource observed(String detail, Observation observation) {
        return new WorkloadSource(SourceKind.PRODUCTION_OBSERVED, detail, observation, "");
    }

    /**
     * A figure calculated from an observation.
     *
     * <p>The derivation is required rather than optional. A derived number whose arithmetic was not
     * recorded is indistinguishable from one somebody invented, and the whole point of the
     * distinction is that it can be checked.
     */
    public static WorkloadSource derived(String detail, Observation observation, String derivation) {
        return new WorkloadSource(SourceKind.DERIVED_FROM_OBSERVATION, detail, observation,
                derivation);
    }

    public Observation observation() {
        return observation;
    }

    /**
     * The same source, with the arithmetic that produced the figure recorded against it.
     *
     * <p>Applies to observed figures as well as derived ones: "your observed peak of 120, rounded to
     * 120" is still a step a reader may want to check, and rounding is where a number quietly stops
     * being the one that was measured.
     */
    public WorkloadSource withDerivation(String newDerivation) {
        return new WorkloadSource(kind, detail, observation, newDerivation);
    }

    /**
     * How this figure was calculated, when it was.
     *
     * <p>Kept as its own component rather than folded into the workload's description, which is free
     * text a user will reasonably rewrite. Provenance that a routine edit can destroy is not
     * provenance.
     */
    public Optional<String> derivationIfPresent() {
        return derivation.isBlank() ? Optional.empty() : Optional.of(derivation);
    }

    public boolean isProductionInformed() {
        return kind.isProductionInformed();
    }

    /** One line describing the provenance, for display beside the figure it qualifies. */
    public String describe() {
        return detail.isBlank() ? kind.label() : kind.label() + " · " + detail;
    }
}
