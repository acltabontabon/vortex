package dev.vortex.app.adapter.observability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.metrics.MetricUnit;
import dev.vortex.core.metrics.TelemetryAvailability;
import dev.vortex.core.metrics.TimeWindow;
import dev.vortex.core.port.ObservabilityProvider;
import dev.vortex.core.resource.ResourceScope;
import dev.vortex.core.resource.ResourceSignal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Vortex measuring its own machine.
 *
 * <p>Nothing here asserts a CPU figure. The properties that matter are structural: every signal is
 * scoped to the generator and never to the service, a limit is present where one genuinely exists,
 * and — the one that carries the whole phase — an unavailable reading produces a gap with a cause
 * rather than a zero that would read as a healthy generator.
 */
class LoadGeneratorObservabilityProviderTest {

    private static final TimeWindow WINDOW = new TimeWindow(
            Instant.parse("2026-08-21T10:00:00Z"), Instant.parse("2026-08-21T10:00:05Z"));

    private static ObservabilityProvider.ObservabilityQuery query() {
        return new ObservabilityProvider.ObservabilityQuery("http://localhost:8080", WINDOW,
                List.of(), ObservabilityProvider.RunCorrelation.none());
    }

    @Nested
    @DisplayName("on a machine Vortex can read")
    class WhenReadable {

        private final LoadGeneratorObservabilityProvider provider =
                new LoadGeneratorObservabilityProvider();

        @Test
        @DisplayName("it always answers, because the machine it measures is the one it runs on")
        void itIsAlwaysAvailable() {
            // Every other provider probes something external. Treating this one as unavailable
            // would drop generator observation from exactly the runs with the least other telemetry.
            assertThat(provider.isAvailable(query())).isTrue();
        }

        @Test
        @DisplayName("every signal it produces is scoped to the generator, and none to the service")
        void everySignalIsScopedToTheGenerator() {
            var collected = provider.collect(query());

            assertThat(collected.resourceSignals()).allSatisfy(signal ->
                    assertThat(signal.scope()).isEqualTo(ResourceScope.LOAD_GENERATOR));
            assertThat(collected.resourceSignals())
                    .noneMatch(signal -> signal.scope().describesTheServiceUnderTest());
        }

        @Test
        @DisplayName("no generator signal can ever establish a limit on the service under test")
        void itNeverConstrainsTheService() {
            var collected = provider.collect(query());

            // The structural guarantee slice 4.6 will rest on: whatever this machine is doing, it is
            // not evidence about the service's capacity.
            assertThat(collected.resourceSignals())
                    .noneMatch(ResourceSignal::canEstablishAServiceLimit);
        }

        @Test
        @DisplayName("what it does report is classified, limited and rendered")
        void reportedSignalsAreUsable() {
            var collected = provider.collect(query());

            // Classified signals belong in both lists: the observation is what a report renders, the
            // typed signal is what a validity rule may rest on.
            assertThat(collected.resourceSignals()).allSatisfy(signal -> {
                assertThat(signal.limitIfPresent()).isPresent();
                assertThat(signal.utilisation()).isPresent();
                assertThat(collected.observations()).contains(signal.observation());
            });
        }

        @Test
        @DisplayName("host CPU is a ratio measured against a ratio, so it can reach its limit")
        void hostCpuCanReachItsLimit() {
            var cpu = provider.collect(query()).resourceSignals().stream()
                    .filter(signal -> signal.signalId().equals("metric:generator.cpu.utilization"))
                    .findFirst();

            // The unit trap from ADR-037, on the signal GENERATOR_SATURATED will read. A limit in
            // percent against a ratio-valued reading never divides, and a resource that can never
            // reach its limit is indistinguishable from one that never did.
            assertThat(cpu).hasValueSatisfying(signal -> {
                assertThat(signal.observation().unit()).isEqualTo(MetricUnit.RATIO);
                assertThat(signal.limitIfPresent())
                        .hasValueSatisfying(limit -> assertThat(limit.unit())
                                .isEqualTo(MetricUnit.RATIO));
                assertThat(signal.utilisation()).isPresent();
            });
        }
    }

    @Nested
    @DisplayName("when it cannot read the machine")
    class WhenUnreadable {

        private final LoadGeneratorObservabilityProvider blind =
                new LoadGeneratorObservabilityProvider(null);

        @Test
        @DisplayName("it reports a gap with a cause, and no signal")
        void itReportsAGapNotAZero() {
            var collected = blind.collect(query());

            assertThat(collected.resourceSignals()).isEmpty();
            assertThat(collected.observations()).isEmpty();
            assertThat(collected.gaps()).isNotEmpty();
        }

        @Test
        @DisplayName("the gap names why, so its silence is never read as a healthy generator")
        void theGapExplainsItself() {
            var gap = blind.collect(query()).gaps().getFirst();

            // "Nobody looked" and "we looked and it was fine" are different afternoons, and only one
            // of them lets a capacity claim stand. GENERATOR_SATURATED will not fire from this, and
            // that must never be mistaken for evidence the generator kept up.
            assertThat(gap.availability()).isEqualTo(TelemetryAvailability.UNSUPPORTED);
            assertThat(gap.availability().isAvailable()).isFalse();
            assertThat(gap.detail()).isNotBlank();
        }
    }

    @Test
    @DisplayName("a cumulative CPU counter is not reported until there is something to compare it to")
    void aRateNeedsTwoReadings() {
        var provider = new LoadGeneratorObservabilityProvider();

        var first = provider.collect(query());
        var second = provider.collect(query());

        // totalCpuDuration only ever grows; publishing the raw total as a utilisation would be a
        // fabrication in the shape of a number. The first sample therefore reports a gap for it.
        assertThat(first.gaps())
                .anyMatch(gap -> gap.metricName().equals("metric:generator.process.cpu.utilization"));
        assertThat(second).isNotNull();
    }
}
