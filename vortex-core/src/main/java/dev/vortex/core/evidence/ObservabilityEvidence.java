package dev.vortex.core.evidence;

import dev.vortex.core.analysis.MissingTelemetry;
import dev.vortex.core.metrics.MetricSource;
import java.util.List;
import java.util.Optional;

/**
 * What the service said about itself while the workload ran.
 *
 * <p>This is what lets a report move past "latency rose" towards "latency rose, and the connection
 * pool was saturated at the same time" — while stopping firmly short of claiming the second caused
 * the first. Vortex observes association; the step to cause needs context a test run does not have.
 *
 * <p>{@code gaps} matters as much as {@code signals}. A measurement that was never collected is
 * reported as missing rather than omitted, because the absence is itself an answer: it is why a
 * question about the database cannot be settled by this run.
 *
 * @param providersConsulted which providers were asked, whether or not they answered
 */
public record ObservabilityEvidence(
        List<ObservedSignal> signals,
        List<String> providersConsulted,
        List<MissingTelemetry> gaps) {

    public ObservabilityEvidence {
        signals = signals == null ? List.of() : List.copyOf(signals);
        providersConsulted = providersConsulted == null ? List.of() : List.copyOf(providersConsulted);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
    }

    public static ObservabilityEvidence empty() {
        return new ObservabilityEvidence(List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return signals.isEmpty();
    }

    public List<ObservedSignal> bySource(MetricSource source) {
        return signals.stream().filter(signal -> signal.source() == source).toList();
    }

    public Optional<ObservedSignal> signal(String id) {
        return signals.stream().filter(signal -> signal.id().equals(id)).findFirst();
    }

    /** Signals that climbed materially during the run — the ones worth a reader's attention. */
    public List<ObservedSignal> thatRose() {
        return signals.stream().filter(ObservedSignal::roseDuringRun).toList();
    }
}
