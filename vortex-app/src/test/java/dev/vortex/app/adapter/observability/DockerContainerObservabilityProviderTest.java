package dev.vortex.app.adapter.observability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.app.adapter.target.docker.DockerProcess;
import dev.vortex.core.metrics.MetricUnit;
import dev.vortex.core.metrics.TimeWindow;
import dev.vortex.core.port.ObservabilityProvider;
import dev.vortex.core.resource.LimitBasis;
import dev.vortex.core.resource.ResourceKind;
import dev.vortex.core.resource.ResourceScope;
import dev.vortex.core.resource.ResourceSignal;
import dev.vortex.core.target.CpuAllocation;
import dev.vortex.core.target.EffectiveResourceEnvelope;
import dev.vortex.core.target.MemoryAllocation;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Fakes-based — no real Docker daemon needed. Exercises {@link DockerContainerObservabilityProvider}
 * against canned {@code docker stats --format '{{json .}}'} output, including the ANSI cursor-control
 * escape sequences a real Docker CLI actually interleaves between lines (see that class's own
 * Javadoc for the investigation this fixture reflects), and against the resource-limit rules §5/§6
 * of the plan set: a limit only when Vortex itself configured and confirmed one, carrying the exact
 * same value, never a fabricated one for a target that requested none.
 */
class DockerContainerObservabilityProviderTest {

    private static final String CONTAINER_ID = "abc123";

    @Test
    void parsesARealWorldFixtureDespiteInterleavedAnsiEscapeCodes() {
        // Cursor-home, clear-screen and clear-line sequences before/after the JSON payload — exactly
        // what a real `docker stats --format '{{json .}}'` stream emits even with stdout redirected
        // to a plain file, confirmed during the plan §9 investigation.
        String fixture = "\u001B[H\u001B[2J{\"CPUPerc\":\"47.30%\",\"MemUsage\":\"128MiB / 1GiB\"}\u001B[K";
        var reading = DockerContainerObservabilityProvider.parse(fixture);

        assertThat(reading)
                .as("a real docker stats line must parse despite the escape codes wrapped around it")
                .isPresent();
    }

    @Test
    void aLineWithNeitherFieldDoesNotParse() {
        assertThat(DockerContainerObservabilityProvider.parse("{\"Name\":\"whatever\"}")).isEmpty();
        assertThat(DockerContainerObservabilityProvider.parse("not json at all")).isEmpty();
        assertThat(DockerContainerObservabilityProvider.parse(null)).isEmpty();
    }

    @Test
    void aConfiguredResourceEnvelopeProducesAVortexConfiguredLimitWithTheSameValue() {
        var envelope = new EffectiveResourceEnvelope(CpuAllocation.ofMillicores(500),
                MemoryAllocation.ofMebibytes(256));
        var provider = new DockerContainerObservabilityProvider(CONTAINER_ID, envelope,
                new ScriptedDockerProcess("{\"CPUPerc\":\"25.00%\",\"MemUsage\":\"64MiB / 256MiB\"}"),
                "docker");

        var collected = provider.collect(query());

        assertThat(collected.resourceSignals()).hasSize(2);

        ResourceSignal cpu = signalOf(collected, ResourceKind.CPU);
        assertThat(cpu.scope()).isEqualTo(ResourceScope.SYSTEM_UNDER_TEST);
        assertThat(cpu.observation().unit()).isEqualTo(MetricUnit.RATIO);
        assertThat(cpu.value()).isEqualTo(0.25);
        assertThat(cpu.limit()).isNotNull();
        assertThat(cpu.limit().basis()).isEqualTo(LimitBasis.VORTEX_CONFIGURED);
        // 500 millicores requested -> half of one core, the exact same value that was configured.
        assertThat(cpu.limit().value()).isEqualTo(0.5);
        assertThat(cpu.limit().unit()).isEqualTo(MetricUnit.RATIO);

        ResourceSignal memory = signalOf(collected, ResourceKind.MEMORY);
        assertThat(memory.scope()).isEqualTo(ResourceScope.SYSTEM_UNDER_TEST);
        assertThat(memory.observation().unit()).isEqualTo(MetricUnit.BYTES);
        assertThat(memory.value()).isEqualTo(64.0 * 1024 * 1024);
        assertThat(memory.limit()).isNotNull();
        assertThat(memory.limit().basis()).isEqualTo(LimitBasis.VORTEX_CONFIGURED);
        // 256 MiB requested -> the exact same byte count that was configured.
        assertThat(memory.limit().value()).isEqualTo(256.0 * 1024 * 1024);
        assertThat(memory.limit().unit()).isEqualTo(MetricUnit.BYTES);
    }

    @Test
    void noConfiguredEnvelopeProducesSignalsWithNoLimitRatherThanAFabricatedOne() {
        var provider = new DockerContainerObservabilityProvider(CONTAINER_ID, null,
                new ScriptedDockerProcess("{\"CPUPerc\":\"10.00%\",\"MemUsage\":\"32MiB / 256MiB\"}"),
                "docker");

        var collected = provider.collect(query());

        assertThat(collected.resourceSignals()).hasSize(2);
        assertThat(signalOf(collected, ResourceKind.CPU).limit())
                .as("a target that requested no CPU limit must not be given one")
                .isNull();
        assertThat(signalOf(collected, ResourceKind.MEMORY).limit())
                .as("a target that requested no memory limit must not be given one")
                .isNull();
    }

    @Test
    void aStreamThatNeverStartsReportsAGapRatherThanThrowing() {
        var provider = new DockerContainerObservabilityProvider(CONTAINER_ID, null,
                new FailingToStartDockerProcess(), "docker");

        var collected = provider.collect(query());

        assertThat(collected.observations()).isEmpty();
        assertThat(collected.resourceSignals()).isEmpty();
        assertThat(collected.gaps()).isNotEmpty();
    }

    @Test
    void noReadingYetReportsAGapRatherThanThrowing() {
        // A stream that starts but has not delivered a first parseable line yet — the ordinary state
        // for the very first collect() call, before the container has had a chance to report in.
        var provider = new DockerContainerObservabilityProvider(CONTAINER_ID, null,
                new ScriptedDockerProcess(""), "docker");

        var collected = provider.collect(query());

        assertThat(collected.observations()).isEmpty();
        assertThat(collected.resourceSignals()).isEmpty();
        assertThat(collected.gaps()).isNotEmpty();
    }

    @Test
    void aNonServiceScopeIsCarriedThroughToBothSignals() {
        // The load-generator case: the same class watches k6's own container, and must never label
        // what it reports as the service under test's.
        var provider = new DockerContainerObservabilityProvider(CONTAINER_ID, null,
                new ScriptedDockerProcess("{\"CPUPerc\":\"10.00%\",\"MemUsage\":\"32MiB / 256MiB\"}"),
                "docker", ResourceScope.LOAD_GENERATOR, false);

        var collected = provider.collect(query());

        assertThat(collected.resourceSignals())
                .allSatisfy(signal -> assertThat(signal.scope()).isEqualTo(ResourceScope.LOAD_GENERATOR));
    }

    @Test
    void withoutRetryAFailedStreamStartSticksPermanently() {
        // The system-under-test case: that container is already running by the time this provider is
        // built, so a failure to attach is a real, permanent problem — never silently retried away.
        var flaky = new FailsOnceThenStartsDockerProcess(
                "{\"CPUPerc\":\"10.00%\",\"MemUsage\":\"32MiB / 256MiB\"}");
        var provider = new DockerContainerObservabilityProvider(CONTAINER_ID, null, flaky, "docker",
                ResourceScope.SYSTEM_UNDER_TEST, false);

        assertThat(provider.collect(query()).resourceSignals()).isEmpty();
        assertThat(provider.collect(query()).resourceSignals())
                .as("without retryStreamStartup, a failed attempt is never retried, even though the "
                        + "underlying stream would now succeed")
                .isEmpty();
    }

    @Test
    void withRetryAFailedStreamStartRecoversOnceTheContainerExists() {
        // The load-generator case: the engine may start that container after this provider's session
        // has already begun sampling, so the first attempt failing is not proof it never will exist.
        var flaky = new FailsOnceThenStartsDockerProcess(
                "{\"CPUPerc\":\"10.00%\",\"MemUsage\":\"32MiB / 256MiB\"}");
        var provider = new DockerContainerObservabilityProvider(CONTAINER_ID, null, flaky, "docker",
                ResourceScope.LOAD_GENERATOR, true);

        assertThat(provider.collect(query()).resourceSignals()).isEmpty();
        assertThat(provider.collect(query()).resourceSignals())
                .as("with retryStreamStartup, the next collect() tries again rather than staying gapped")
                .isNotEmpty();
    }

    private static ResourceSignal signalOf(ObservabilityProvider.Collected collected, ResourceKind kind) {
        return collected.resourceSignals().stream()
                .filter(signal -> signal.kind() == kind)
                .findFirst()
                .orElseThrow();
    }

    private static ObservabilityProvider.ObservabilityQuery query() {
        Instant now = Instant.now();
        return new ObservabilityProvider.ObservabilityQuery("", new TimeWindow(now, now), List.of());
    }

    /** Fakes {@link DockerProcess#stream} by immediately feeding a canned line to the sink and
     *  returning a no-op handle — no real process is ever spawned. */
    private static final class ScriptedDockerProcess extends DockerProcess {

        private final String cannedLine;

        ScriptedDockerProcess(String cannedLine) {
            this.cannedLine = cannedLine;
        }

        @Override
        public StreamHandle stream(List<String> command, Consumer<String> stdoutSink) {
            stdoutSink.accept(cannedLine);
            return StreamHandle.noop();
        }
    }

    /** Simulates a stream that could not be started at all — e.g. the {@code docker} binary is not
     *  on this machine's PATH. */
    private static final class FailingToStartDockerProcess extends DockerProcess {

        @Override
        public StreamHandle stream(List<String> command, Consumer<String> stdoutSink) {
            throw new RuntimeException("simulated: docker binary not found");
        }
    }

    /** Simulates a container that does not exist yet on the first attempt — e.g. the engine has not
     *  started it — but does by the second, so a caller can tell retried recovery apart from a
     *  permanent failure. */
    private static final class FailsOnceThenStartsDockerProcess extends DockerProcess {

        private final String cannedLine;
        private int attempts;

        FailsOnceThenStartsDockerProcess(String cannedLine) {
            this.cannedLine = cannedLine;
        }

        @Override
        public StreamHandle stream(List<String> command, Consumer<String> stdoutSink) {
            attempts++;
            if (attempts == 1) {
                throw new RuntimeException("simulated: no such container");
            }
            stdoutSink.accept(cannedLine);
            return StreamHandle.noop();
        }
    }
}
