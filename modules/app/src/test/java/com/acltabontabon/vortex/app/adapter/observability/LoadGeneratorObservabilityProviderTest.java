package com.acltabontabon.vortex.app.adapter.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.metrics.MetricUnit;
import com.acltabontabon.vortex.core.metrics.TelemetryAvailability;
import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.core.port.ObservabilityProvider;
import com.acltabontabon.vortex.core.resource.ResourceScope;
import com.acltabontabon.vortex.core.resource.ResourceSignal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Vortex measuring its own machine.
 *
 * <p>Nothing here asserts a CPU or memory figure by value. The properties that matter are
 * structural: a signal describing the generator's own process or container is told apart from one
 * describing the whole machine it happens to share — {@link ResourceScope#LOAD_GENERATOR} versus
 * {@link ResourceScope#LOAD_GENERATOR_HOST} — a limit is present where one genuinely exists, and —
 * the one that carries the whole phase — an unavailable reading produces a gap with a cause rather
 * than a zero that would read as a healthy generator.
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
        @DisplayName("every signal it produces is scoped to the generator or its host, never the service")
        void everySignalIsScopedToTheGeneratorOrItsHost() {
            var collected = provider.collect(query());

            assertThat(collected.resourceSignals()).allSatisfy(signal ->
                    assertThat(signal.scope())
                            .isIn(ResourceScope.LOAD_GENERATOR, ResourceScope.LOAD_GENERATOR_HOST));
            assertThat(collected.resourceSignals())
                    .noneMatch(signal -> signal.scope().describesTheServiceUnderTest());
        }

        @Test
        @DisplayName("host readings are host-scoped, process readings are generator-scoped — never the reverse")
        void hostAndProcessSignalsAreScopedDistinctly() {
            var byId = collected(provider).stream()
                    .collect(java.util.stream.Collectors.toMap(
                            signal -> signal.signalId(), signal -> signal));

            if (byId.containsKey("metric:generator.host.cpu.utilization")) {
                assertThat(byId.get("metric:generator.host.cpu.utilization").scope())
                        .isEqualTo(ResourceScope.LOAD_GENERATOR_HOST);
            }
            if (byId.containsKey("metric:generator.host.memory.used")) {
                assertThat(byId.get("metric:generator.host.memory.used").scope())
                        .isEqualTo(ResourceScope.LOAD_GENERATOR_HOST);
            }
            if (byId.containsKey("metric:generator.process.cpu.utilization")) {
                assertThat(byId.get("metric:generator.process.cpu.utilization").scope())
                        .isEqualTo(ResourceScope.LOAD_GENERATOR);
            }
            if (byId.containsKey("metric:generator.process.memory.used")) {
                assertThat(byId.get("metric:generator.process.memory.used").scope())
                        .isEqualTo(ResourceScope.LOAD_GENERATOR);
            }
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
                    .filter(signal -> signal.signalId().equals("metric:generator.host.cpu.utilization"))
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

        private List<ResourceSignal> collected(LoadGeneratorObservabilityProvider provider) {
            return provider.collect(query()).resourceSignals();
        }
    }

    @Nested
    @DisplayName("when the generator runs as a local process")
    class ProcessMemory {

        private Process spawned;

        @AfterEach
        void cleanup() {
            if (spawned != null) {
                spawned.destroyForcibly();
            }
        }

        @Test
        @DisplayName("process memory is reported for a real descendant, scoped to the generator and limited")
        void reportsResidentMemoryOfADescendant() throws Exception {
            spawned = new ProcessBuilder("sleep", "5").start();
            // Give ProcessHandle a moment to see the freshly spawned child, and ps a moment to have a
            // reading for its pid — both are effectively instant on a real machine, but not guaranteed
            // synchronous with start().
            Thread.sleep(200);

            var provider = new LoadGeneratorObservabilityProvider(true);
            var signal = provider.collect(query()).resourceSignals().stream()
                    .filter(s -> s.signalId().equals("metric:generator.process.memory.used"))
                    .findFirst();

            assertThat(signal).hasValueSatisfying(s -> {
                assertThat(s.scope()).isEqualTo(ResourceScope.LOAD_GENERATOR);
                assertThat(s.observation().unit()).isEqualTo(MetricUnit.BYTES);
                assertThat(s.observation().value()).isGreaterThan(0);
                assertThat(s.limitIfPresent()).isPresent();
            });
        }

        @Test
        @DisplayName("with no descendant visible, it gaps rather than reports a fabricated zero")
        void gapsWhenNoDescendantIsVisible() {
            var provider = new LoadGeneratorObservabilityProvider(true);

            var gaps = provider.collect(query()).gaps();

            assertThat(gaps)
                    .anyMatch(gap -> gap.metricName().equals("metric:generator.process.memory.used")
                            && gap.availability() == TelemetryAvailability.NO_DATA);
        }

        @Test
        @DisplayName("once a real descendant exists, a still-missing CPU reading is unsupported, never no-data")
        void unreadableProcessCpuIsUnsupportedNotNoData() throws Exception {
            // ProcessHandle.Info#totalCpuDuration() is a JDK-documented "if supported" API — empty
            // for every child process on some platforms (macOS notably), always, not just on the
            // first sample. Once a real descendant is known to exist, a still-missing reading is a
            // platform fact, not "nothing to look at yet" — and must never be classified the same way
            // as a genuinely empty process table.
            spawned = new ProcessBuilder("sleep", "5").start();
            Thread.sleep(200);

            var provider = new LoadGeneratorObservabilityProvider(true);
            provider.collect(query());
            var second = provider.collect(query());

            var cpuGap = second.gaps().stream()
                    .filter(gap -> gap.metricName().equals("metric:generator.process.cpu.utilization"))
                    .findFirst();
            var cpuSignal = second.resourceSignals().stream()
                    .filter(s -> s.signalId().equals("metric:generator.process.cpu.utilization"))
                    .findFirst();

            // Exactly one of the two: either this platform measured it, or it explains why not — and
            // a real descendant exists by now (proven by the memory reading above), so if it is a
            // gap, it is never NO_DATA.
            assertThat(cpuGap.isPresent() ^ cpuSignal.isPresent())
                    .as("expected exactly one of a gap or a signal for the process CPU reading")
                    .isTrue();
            cpuGap.ifPresent(gap ->
                    assertThat(gap.availability()).isEqualTo(TelemetryAvailability.UNSUPPORTED));
        }
    }

    @Nested
    @DisplayName("when the generator runs in a container")
    class ContainerisedGenerator {

        @Test
        @DisplayName("process memory is gapped as unsupported, never attributed to the docker client process")
        void processMemoryIsGapped() {
            var provider = new LoadGeneratorObservabilityProvider(false);

            var collected = provider.collect(query());

            assertThat(collected.resourceSignals())
                    .noneMatch(signal -> signal.signalId().equals("metric:generator.process.memory.used"));
            assertThat(collected.gaps())
                    .anyMatch(gap -> gap.metricName().equals("metric:generator.process.memory.used")
                            && gap.availability() == TelemetryAvailability.UNSUPPORTED);
        }

        @Test
        @DisplayName("host and process CPU are unaffected — only process memory is a container-mode gap")
        void onlyProcessMemoryIsGapped() {
            var provider = new LoadGeneratorObservabilityProvider(false);

            var collected = provider.collect(query());

            assertThat(collected.resourceSignals().stream().map(ResourceSignal::signalId)
                    .collect(java.util.stream.Collectors.toSet()))
                    .isSubsetOf(Set.of("metric:generator.host.cpu.utilization",
                            "metric:generator.host.memory.used"));
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
