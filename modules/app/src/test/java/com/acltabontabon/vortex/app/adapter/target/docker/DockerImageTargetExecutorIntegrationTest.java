package com.acltabontabon.vortex.app.adapter.target.docker;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.app.adapter.observability.DockerContainerObservabilityProvider;
import com.acltabontabon.vortex.core.metrics.MetricUnit;
import com.acltabontabon.vortex.core.port.ObservabilityProvider;
import com.acltabontabon.vortex.core.resource.LimitBasis;
import com.acltabontabon.vortex.core.resource.ResourceKind;
import com.acltabontabon.vortex.core.resource.ResourceScope;
import com.acltabontabon.vortex.core.resource.ResourceSignal;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.target.ContainerPort;
import com.acltabontabon.vortex.core.target.CpuAllocation;
import com.acltabontabon.vortex.core.target.DockerImageTarget;
import com.acltabontabon.vortex.core.target.EffectiveResourceEnvelope;
import com.acltabontabon.vortex.core.target.ImageReference;
import com.acltabontabon.vortex.core.target.MemoryAllocation;
import com.acltabontabon.vortex.core.target.PreparedTarget;
import com.acltabontabon.vortex.core.target.ResourceEnvelopeRequest;
import com.acltabontabon.vortex.core.target.TargetOwnership;
import com.acltabontabon.vortex.core.target.TargetPreparationRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Exercises {@link DockerImageTargetExecutor} against a real Docker daemon: create, start,
 * TCP-only readiness (no HTTP check configured), then remove.
 *
 * <p>Skipped automatically when Docker is not available — mirroring {@code
 * K6EngineIntegrationTest}'s {@code @EnabledIf} pattern exactly. Most environments running this
 * suite have no Docker daemon at all, in which case this test reports as skipped; it exists to run
 * for real wherever Docker is installed.
 *
 * <p>Also skipped when the chosen image is not already pulled locally — Vortex never pulls images
 * itself (an unpulled image fails preparation with {@code IMAGE_NOT_FOUND}, by design, proven
 * separately by the fake-based tests), so this real-daemon test only asserts the happy path when
 * the image is already there rather than depending on outbound network access from wherever it
 * runs.
 *
 * <p>Chose {@code hashicorp/http-echo}: a single static binary with no required arguments and no
 * required environment variables — it starts and listens on :5678 immediately — unlike, say,
 * {@code postgres}, which needs configuration to avoid crash-looping, or {@code grafana/k6} (this
 * codebase's other pinned Docker image, used for the k6-in-Docker runner), which is not an HTTP
 * server at all.
 */
@EnabledIf("dockerIsAvailableWithTheTestImage")
class DockerImageTargetExecutorIntegrationTest {

    private static final String IMAGE = "hashicorp/http-echo:latest";
    private static final int CONTAINER_PORT = 5678;

    private final DockerImageTargetExecutor executor = new DockerImageTargetExecutor("docker",
            new DockerProcess(), new DockerCapabilityProbe("docker"),
            new SocketTargetReadinessProbe());

    static boolean dockerIsAvailableWithTheTestImage() {
        if (!new DockerCapabilityProbe("docker").isAvailable()) {
            return false;
        }
        DockerProcess.DockerCommandResult result = new DockerProcess().run(
                List.of("docker", "image", "inspect", IMAGE), Duration.ofSeconds(10));
        return result.succeeded();
    }

    @Test
    @DisplayName("creates, starts, waits for TCP readiness, and removes a real container")
    void createsStartsAndRemovesARealContainer() {
        DockerImageTarget target = new DockerImageTarget(new ImageReference(IMAGE),
                new ContainerPort(CONTAINER_PORT), ResourceEnvelopeRequest.none(), null);
        TargetPreparationRequest request = new TargetPreparationRequest(ExecutionId.generate(),
                ProjectId.generate(), target, message -> { }, "");

        PreparedTarget prepared = executor.prepare(request);
        try {
            assertThat(prepared.resolvedTarget().ownership())
                    .isEqualTo(TargetOwnership.VORTEX_MANAGED);
            assertThat(prepared.resolvedTarget().endpoint().value())
                    .startsWith("http://localhost:");
            assertThat(prepared.resolvedTarget().telemetryHandleIfPresent()).isPresent();
        } finally {
            var outcome = prepared.cleanup();
            assertThat(outcome.attempted()).isTrue();
            assertThat(outcome.succeeded()).isTrue();
        }
    }

    @Test
    @DisplayName("a configured CPU/memory envelope is actually applied and confirmed on a real container")
    void resourceEnvelopeIsAppliedAndConfirmed() {
        DockerImageTarget target = new DockerImageTarget(new ImageReference(IMAGE),
                new ContainerPort(CONTAINER_PORT),
                new ResourceEnvelopeRequest(CpuAllocation.ofMillicores(250),
                        MemoryAllocation.ofMebibytes(256)),
                null);
        TargetPreparationRequest request = new TargetPreparationRequest(ExecutionId.generate(),
                ProjectId.generate(), target, message -> { }, "");

        PreparedTarget prepared = executor.prepare(request);
        try {
            assertThat(prepared.resolvedTarget().resourcesIfPresent()).hasValueSatisfying(resources -> {
                assertThat(resources.cpuIfPresent()).contains(CpuAllocation.ofMillicores(250));
                assertThat(resources.memoryIfPresent()).contains(MemoryAllocation.ofMebibytes(256));
            });
        } finally {
            var outcome = prepared.cleanup();
            assertThat(outcome.attempted()).isTrue();
            assertThat(outcome.succeeded()).isTrue();
        }
    }

    @Test
    @DisplayName("plan §9: container telemetry returns parseable, sane CPU/memory readings for a "
            + "real running container")
    void containerTelemetryReturnsParseableCpuAndMemoryReadings() {
        DockerImageTarget target = new DockerImageTarget(new ImageReference(IMAGE),
                new ContainerPort(CONTAINER_PORT),
                new ResourceEnvelopeRequest(CpuAllocation.ofMillicores(500),
                        MemoryAllocation.ofMebibytes(256)),
                null);
        TargetPreparationRequest request = new TargetPreparationRequest(ExecutionId.generate(),
                ProjectId.generate(), target, message -> { }, "");

        PreparedTarget prepared = executor.prepare(request);
        String containerId = prepared.resolvedTarget().telemetryHandleIfPresent().orElseThrow();
        EffectiveResourceEnvelope resources = prepared.resolvedTarget().resourcesIfPresent().orElseThrow();

        DockerContainerObservabilityProvider provider = new DockerContainerObservabilityProvider(
                containerId, resources, new DockerProcess(), "docker");
        try {
            // The long-lived docker stats stream (plan §9's chosen mechanism) needs a moment to
            // deliver its first reading; poll rather than sleeping a fixed guess.
            ObservabilityProvider.Collected collected = pollForAReading(provider);

            assertThat(collected.gaps())
                    .as("a real, running container must produce readings, not gaps")
                    .isEmpty();
            assertThat(collected.resourceSignals()).hasSize(2);

            ResourceSignal cpu = signalOf(collected, ResourceKind.CPU);
            assertThat(cpu.scope()).isEqualTo(ResourceScope.SYSTEM_UNDER_TEST);
            assertThat(cpu.observation().unit()).isEqualTo(MetricUnit.RATIO);
            assertThat(cpu.value()).isGreaterThanOrEqualTo(0.0);
            assertThat(cpu.limit()).isNotNull();
            assertThat(cpu.limit().basis()).isEqualTo(LimitBasis.VORTEX_CONFIGURED);
            assertThat(cpu.limit().value()).isEqualTo(0.5);

            ResourceSignal memory = signalOf(collected, ResourceKind.MEMORY);
            assertThat(memory.scope()).isEqualTo(ResourceScope.SYSTEM_UNDER_TEST);
            assertThat(memory.observation().unit()).isEqualTo(MetricUnit.BYTES);
            assertThat(memory.value()).isGreaterThan(0.0);
            assertThat(memory.limit()).isNotNull();
            assertThat(memory.limit().basis()).isEqualTo(LimitBasis.VORTEX_CONFIGURED);
            assertThat(memory.limit().value()).isEqualTo(256.0 * 1024 * 1024);
        } finally {
            provider.close();
            prepared.cleanup();
        }
    }

    private static ObservabilityProvider.Collected pollForAReading(
            ObservabilityProvider provider) {
        var query = new ObservabilityProvider.ObservabilityQuery("",
                new com.acltabontabon.vortex.core.metrics.TimeWindow(Instant.now(), Instant.now()), List.of());
        Instant deadline = Instant.now().plusSeconds(10);
        ObservabilityProvider.Collected last = provider.collect(query);
        while (last.resourceSignals().isEmpty() && Instant.now().isBefore(deadline)) {
            sleep(Duration.ofMillis(250));
            last = provider.collect(query);
        }
        return last;
    }

    private static ResourceSignal signalOf(ObservabilityProvider.Collected collected, ResourceKind kind) {
        return collected.resourceSignals().stream()
                .filter(signal -> signal.kind() == kind)
                .findFirst()
                .orElseThrow();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
