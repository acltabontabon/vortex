package dev.vortex.core.evidence;

import dev.vortex.core.metrics.MetricObservation;
import dev.vortex.core.metrics.MetricSource;
import dev.vortex.core.metrics.ObservationProvenance;
import dev.vortex.core.metrics.ObservationTrace;
import dev.vortex.core.resource.ResourceSignal;
import java.util.Objects;
import java.util.Optional;

/**
 * One measurement the service made about itself, in the form a report needs it.
 *
 * <p>A thin wrapper over {@link MetricObservation} that exists so a renderer never has to ask where
 * a signal came from. Whether the number arrived from an Actuator endpoint, a PromQL query or a
 * Dynatrace metric selector, it presents identically here — which is what keeps the reporting layer
 * free of any particular observability vendor.
 *
 * <p>Everything optional is genuinely optional. The Actuator provider supplies no entity id and no
 * deep link, and a signal sampled once has no trace. Renderers must show what is there and say
 * nothing about what is not.
 *
 * @param observation the measurement itself, always present
 * @param resource    what sort of resource it is and whose, where a provider could classify it.
 *                    Absent for everything else — a custom metric a team asked for, a dependency
 *                    latency series, anything outside the closed set of resource kinds. Those are
 *                    still rendered, cited and exported; they simply cannot become a statement about
 *                    a limit, and the absence of this field is what makes that impossible rather
 *                    than merely discouraged
 */
public record ObservedSignal(MetricObservation observation, ResourceSignal resource) {

    public ObservedSignal {
        Objects.requireNonNull(observation, "observation");
    }

    /** A measurement no provider classified. */
    public static ObservedSignal of(MetricObservation observation) {
        return new ObservedSignal(observation, null);
    }

    /** A measurement a provider classified as a typed resource. */
    public static ObservedSignal of(ResourceSignal resource) {
        return new ObservedSignal(resource.observation(), resource);
    }

    public Optional<ResourceSignal> resourceIfPresent() {
        return Optional.ofNullable(resource);
    }

    /**
     * Whether this signal may be named as a constraint on the service under test.
     *
     * <p>False for an unclassified measurement, however suggestive its value: nothing is promoted by
     * looking like a resource.
     */
    public boolean canEstablishAServiceLimit() {
        return resource != null && resource.canEstablishAServiceLimit();
    }

    public String id() {
        return observation.id();
    }

    public String name() {
        return observation.name();
    }

    public MetricSource source() {
        return observation.source();
    }

    /** The peak value with its unit, e.g. {@code 94 %}. */
    public String display() {
        return observation.display();
    }

    public Optional<ObservationProvenance> provenance() {
        return observation.provenanceIfPresent();
    }

    public Optional<ObservationTrace> trace() {
        return observation.traceIfPresent();
    }

    /**
     * How the signal moved across the run, e.g. {@code 31 % → 94 % → 47 %}.
     *
     * <p>Absent when only one reading was taken, rather than filled in with the peak three times —
     * which would assert a flat signal nobody measured.
     */
    public Optional<String> movement() {
        return trace().map(trace -> observation.display(trace.startValue())
                + " → " + observation.display(trace.peakValue())
                + " → " + observation.display(trace.endValue()));
    }

    /** Whether this signal climbed materially during the run, rather than starting where it ended. */
    public boolean roseDuringRun() {
        return trace().map(ObservationTrace::roseDuringRun).orElse(false);
    }
}
