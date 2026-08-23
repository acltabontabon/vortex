package dev.vortex.core.analysis;

import dev.vortex.core.resource.ResourceKind;
import dev.vortex.core.shared.LoadLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Four different limits, reported separately, because they routinely disagree.
 *
 * <pre>
 *   Objective breakpoint   where a declared objective was first violated
 *   Throughput ceiling     where more offered load stopped producing more throughput
 *   Resource limit         where a typed resource reached its declared limit
 *   System saturation      where the system stopped coping, from corroborating symptoms
 * </pre>
 *
 * <p>Collapsing them into "the breakpoint" would discard the distinction that decides what an
 * engineer does next: a service that is slow and a service that is full need opposite responses, and
 * a service that breaches an objective it agreed to last year needs neither.
 *
 * @param first which limit was reached first, or both where two tie. Empty when none was established
 */
public record LimitFindings(SloBreakpoint objectiveBreakpoint, ThroughputCeiling throughputCeiling,
                            ResourceLimitFinding resourceLimit, SystemSaturation systemSaturation,
                            List<FirstLimitingSignal> first) {

    /** Which limit was reached first, and where the answer is a resource, which one. */
    public record FirstLimitingSignal(LimitKind limit, LoadLevel level, String display,
                                      ResourceKind resourceKind) {

        public Optional<ResourceKind> resourceKindIfPresent() {
            return Optional.ofNullable(resourceKind);
        }
    }

    public enum LimitKind {

        OBJECTIVE_BREAKPOINT("Objective breakpoint"),
        THROUGHPUT_CEILING("Throughput ceiling"),
        RESOURCE_LIMIT("Resource limit"),
        SYSTEM_SATURATION("System saturation");

        private final String label;

        LimitKind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public LimitFindings {
        first = first == null ? List.of() : List.copyOf(first);
    }

    /** A run whose limits were never computed — an older summary, or one with no stages. */
    public static LimitFindings notEvaluated() {
        return new LimitFindings(null, ThroughputCeiling.notEvaluated(
                "This run's limits were not evaluated."), null, null, List.of());
    }

    public Optional<SloBreakpoint> objectiveBreakpointIfPresent() {
        return Optional.ofNullable(objectiveBreakpoint);
    }

    public Optional<ThroughputCeiling> throughputCeilingIfPresent() {
        return throughputCeiling == null || !throughputCeiling.isQuotable()
                ? Optional.empty() : Optional.of(throughputCeiling);
    }

    public Optional<ResourceLimitFinding> resourceLimitIfPresent() {
        return resourceLimit == null || !resourceLimit.wasReached()
                ? Optional.empty() : Optional.of(resourceLimit);
    }

    public Optional<SystemSaturation> systemSaturationIfPresent() {
        return systemSaturation == null || !systemSaturation.wasObserved()
                ? Optional.empty() : Optional.of(systemSaturation);
    }

    /**
     * Whether this run found none of the four.
     *
     * <p>A correct and useful answer, and one a report has to be able to state plainly rather than
     * leaving four empty sections for a reader to interpret.
     */
    public boolean noneEstablished() {
        return objectiveBreakpointIfPresent().isEmpty()
                && throughputCeilingIfPresent().isEmpty()
                && resourceLimitIfPresent().isEmpty()
                && systemSaturationIfPresent().isEmpty();
    }

    /**
     * What a report says about which limit came first.
     *
     * <p>Where two were reached at the same level, both are named — reporting one would assert an
     * ordering the run did not establish.
     */
    public String describeFirst() {
        if (first.isEmpty()) {
            return "No limit was established: this run did not reach the level at which the service "
                    + "stops meeting its objectives, stops absorbing load, or runs out of an "
                    + "observed resource.";
        }
        List<String> named = new ArrayList<>();
        first.forEach(signal -> named.add(signal.limit().label().toLowerCase(java.util.Locale.ROOT)
                + (signal.resourceKind() == null ? "" : " (" + signal.resourceKind().label() + ")")));
        String joined = String.join(" and ", named);
        return "The first limit reached was the " + joined + ", at "
                + first.getFirst().level().displayWithUnit() + ".";
    }
}
