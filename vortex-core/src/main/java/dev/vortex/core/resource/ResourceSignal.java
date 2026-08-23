package dev.vortex.core.resource;

import dev.vortex.core.metrics.MetricObservation;
import java.util.Objects;
import java.util.Optional;

/**
 * A measurement that declared what kind of resource it is, and whose.
 *
 * <p>A {@link MetricObservation} is enough to <em>render</em> a measurement honestly and enough to
 * <em>cite</em> it. It is not enough to reason about one: nothing in it says that
 * {@code system.cpu.utilization}, {@code builtin:host.cpu.usage:max} and {@code process.cpu.usage}
 * are all CPU, or that two of them describe different machines.
 *
 * <h2>A wrapper, not a wider observation</h2>
 * Classification lives here rather than on {@code MetricObservation} so that "may this be cited as a
 * limiting resource?" is a <em>type</em> question. A {@code List<ResourceSignal>} contains only
 * things a provider classified; nothing is promoted by looking like a resource. Had classification
 * been nullable fields on the observation, every caller would have had to remember to check them,
 * and the first one that forgot would produce a confident statement about a resource nobody
 * identified.
 *
 * <p>Every signal is also present in its collection's sibling list of plain observations, so
 * everything that renders, cites, exports or sanitises measurements keeps working untouched. The
 * duplication is deliberate and cheap.
 *
 * @param observation the measurement, unchanged
 * @param kind        what sort of resource it measures
 * @param scope       whose resource it is
 * @param limit       what the value is relative to, or {@code null} when the provider published none
 */
public record ResourceSignal(MetricObservation observation, ResourceKind kind,
                             ResourceScope scope, ResourceLimit limit) {

    public ResourceSignal {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(scope, "scope");
        // limit stays nullable: absent is the honest common case, because most providers publish a
        // utilisation without publishing what it is a fraction of.
    }

    /** A classified signal whose limit the provider did not publish. */
    public static ResourceSignal unbounded(MetricObservation observation, ResourceKind kind,
            ResourceScope scope) {
        return new ResourceSignal(observation, kind, scope, null);
    }

    public String signalId() {
        return observation.id();
    }

    public String name() {
        return observation.name();
    }

    public double value() {
        return observation.value();
    }

    public Optional<ResourceLimit> limitIfPresent() {
        return Optional.ofNullable(limit);
    }

    /**
     * How much of the declared limit this measurement represents, as a fraction of one.
     *
     * <p>Absent when no limit was published. Vortex does not divide by a denominator it invented,
     * however obvious the denominator seems.
     */
    public Optional<Double> utilisation() {
        if (limit == null || limit.unit() != observation.unit()) {
            return Optional.empty();
        }
        return Optional.of(observation.value() / limit.value());
    }

    /**
     * Whether this resource reached the limit it was measured against.
     *
     * <p>False when no limit is known — not "unknown", because a caller asking this question is
     * about to make a claim, and the only safe answer to "did it reach a limit nobody told us
     * about?" is no.
     */
    public boolean isAtItsLimit() {
        return utilisation().map(used -> used >= 1.0).orElse(false);
    }

    /** Whether this resource is close enough to its declared limit to be worth a reader's attention. */
    public boolean isNearItsLimit(double fraction) {
        return utilisation().map(used -> used >= fraction).orElse(false);
    }

    /**
     * Whether this signal may support a statement about the service's own limits.
     *
     * <p>Both halves are required: scoped to the service, and measured against a limit somebody
     * actually declared.
     */
    public boolean canEstablishAServiceLimit() {
        return scope.describesTheServiceUnderTest() && limit != null;
    }

    /** How to say it: "heap at 3.9 GB of a 4 GB limit". */
    public String describe() {
        if (limit == null) {
            return name() + " at " + observation.display() + ", against no published limit";
        }
        return name() + " at " + observation.display() + " of " + limit.display()
                + " (" + limit.describedAs() + ")";
    }
}
