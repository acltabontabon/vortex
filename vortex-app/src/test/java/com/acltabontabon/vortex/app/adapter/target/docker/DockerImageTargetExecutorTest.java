package com.acltabontabon.vortex.app.adapter.target.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.acltabontabon.vortex.core.application.PreflightCheck;
import com.acltabontabon.vortex.core.execution.FailureReason;
import com.acltabontabon.vortex.core.target.ContainerPort;
import com.acltabontabon.vortex.core.target.CpuAllocation;
import com.acltabontabon.vortex.core.target.DockerImageTarget;
import com.acltabontabon.vortex.core.target.ImageReference;
import com.acltabontabon.vortex.core.target.MemoryAllocation;
import com.acltabontabon.vortex.core.target.PreparedTarget;
import com.acltabontabon.vortex.core.target.ReadinessCheck;
import com.acltabontabon.vortex.core.target.ResourceEnvelopeRequest;
import com.acltabontabon.vortex.core.target.TargetOwnership;
import com.acltabontabon.vortex.core.target.TargetPreparationException;
import com.acltabontabon.vortex.core.target.TargetPreparationRequest;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.shared.ProjectId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DockerImageTargetExecutor} against a scripted {@link DockerProcess} and {@link
 * TargetReadinessProbe} — no real Docker daemon is available in this environment (see {@code
 * DockerLocalLabTest}), so every one of these proves behavior through the fakes' recorded
 * invocations, not by watching real containers.
 *
 * <p>The transactional-cleanup tests in particular assert on the exact command sequence the fake
 * recorded, not merely on the exception type thrown — that a failure was translated correctly and
 * that {@code docker rm -f} either was or was not issued are two different claims, and both matter.
 */
class DockerImageTargetExecutorTest {

    private static final ExecutionId EXECUTION_ID = ExecutionId.of("exec-1");
    private static final ProjectId PROJECT_ID = ProjectId.of("project-1");
    private static final String IMAGE = "payment-service:1.4.2";
    private static final String CONTAINER_ID = "c1a2b3c4d5e6";

    private final ScriptedDockerProcess dockerProcess = new ScriptedDockerProcess();

    private DockerImageTargetExecutor executorWith(DockerCapabilityProbe capabilityProbe,
            TargetReadinessProbe readinessProbe) {
        return new DockerImageTargetExecutor("docker", dockerProcess, capabilityProbe, readinessProbe);
    }

    private DockerImageTargetExecutor executorAssumingDockerAvailable() {
        return executorWith(new AlwaysAvailableCapabilityProbe(), new ScriptedReadinessProbe(true, true));
    }

    private TargetPreparationRequest requestFor(DockerImageTarget target) {
        return new TargetPreparationRequest(EXECUTION_ID, PROJECT_ID, target, message -> { }, "");
    }

    private DockerImageTarget targetWithoutReadinessCheck() {
        return new DockerImageTarget(new ImageReference(IMAGE), new ContainerPort(8080),
                ResourceEnvelopeRequest.none(), null);
    }

    // ---- Docker unavailable --------------------------------------------------------------

    @Test
    @DisplayName("Docker unavailable fails with DOCKER_UNAVAILABLE and never attempts docker create")
    void dockerUnavailableFailsBeforeAnythingIsAttempted() {
        // A real DockerCapabilityProbe pointed at a binary that does not exist, exactly like
        // DockerLocalLabTest's own "missing Docker" case — no fake needed for this one.
        DockerImageTargetExecutor executor = executorWith(
                new DockerCapabilityProbe("definitely-not-a-real-docker-binary"),
                new ScriptedReadinessProbe(true, true));

        assertThatThrownBy(() -> executor.prepare(requestFor(targetWithoutReadinessCheck())))
                .isInstanceOf(TargetPreparationException.class)
                .satisfies(e -> assertThat(((TargetPreparationException) e).reason())
                        .isEqualTo(FailureReason.DOCKER_UNAVAILABLE));

        assertThat(dockerProcess.invocations()).isEmpty();
    }

    // ---- checkAvailability -----------------------------------------------------------------

    @Test
    @DisplayName("checkAvailability: Docker unavailable reports one FAIL check and issues no docker command at all")
    void checkAvailabilityDockerUnavailable() {
        DockerImageTargetExecutor executor = executorWith(
                new DockerCapabilityProbe("definitely-not-a-real-docker-binary"),
                new ScriptedReadinessProbe(true, true));

        List<PreflightCheck> checks =
                executor.checkAvailability(targetWithoutReadinessCheck(), "");

        assertThat(checks).hasSize(1);
        assertThat(checks.getFirst().name()).isEqualTo("Docker available");
        assertThat(checks.getFirst().status()).isEqualTo(PreflightCheck.Status.FAIL);
        assertThat(dockerProcess.invocations()).isEmpty();
    }

    @Test
    @DisplayName("checkAvailability: image not found reports one FAIL check and never attempts docker create")
    void checkAvailabilityImageNotFound() {
        dockerProcess.script("image", failure("no such image: " + IMAGE));
        DockerImageTargetExecutor executor = executorAssumingDockerAvailable();

        List<PreflightCheck> checks =
                executor.checkAvailability(targetWithoutReadinessCheck(), "");

        assertThat(checks).hasSize(1);
        assertThat(checks.getFirst().name()).isEqualTo("Image available");
        assertThat(checks.getFirst().status()).isEqualTo(PreflightCheck.Status.FAIL);
        assertThat(verbsInvoked()).doesNotContain("create");
    }

    @Test
    @DisplayName("checkAvailability: both Docker and the image available reports two PASS checks")
    void checkAvailabilityBothAvailable() {
        dockerProcess.script("image", success());
        DockerImageTargetExecutor executor = executorAssumingDockerAvailable();

        List<PreflightCheck> checks =
                executor.checkAvailability(targetWithoutReadinessCheck(), "");

        assertThat(checks).hasSize(2);
        assertThat(checks).allSatisfy(check ->
                assertThat(check.status()).isEqualTo(PreflightCheck.Status.PASS));
        assertThat(checks.get(0).name()).isEqualTo("Docker available");
        assertThat(checks.get(1).name()).isEqualTo("Image available");
        assertThat(verbsInvoked()).containsExactly("image");
    }

    // ---- Image not found -------------------------------------------------------------------

    @Test
    @DisplayName("image not found fails with IMAGE_NOT_FOUND and never attempts docker create")
    void imageNotFoundFailsBeforeCreate() {
        dockerProcess.script("image", failure("no such image: " + IMAGE));
        DockerImageTargetExecutor executor = executorAssumingDockerAvailable();

        assertThatThrownBy(() -> executor.prepare(requestFor(targetWithoutReadinessCheck())))
                .isInstanceOf(TargetPreparationException.class)
                .satisfies(e -> assertThat(((TargetPreparationException) e).reason())
                        .isEqualTo(FailureReason.IMAGE_NOT_FOUND));

        assertThat(verbsInvoked()).doesNotContain("create");
    }

    // ---- docker create fails ----------------------------------------------------------------

    @Test
    @DisplayName("docker create failing needs no cleanup, because nothing was created")
    void createFailingNeedsNoCleanup() {
        dockerProcess.script("image", success());
        dockerProcess.script("create", failure("daemon refused to create the container"));
        DockerImageTargetExecutor executor = executorAssumingDockerAvailable();

        assertThatThrownBy(() -> executor.prepare(requestFor(targetWithoutReadinessCheck())))
                .isInstanceOf(TargetPreparationException.class)
                .satisfies(e -> assertThat(((TargetPreparationException) e).reason())
                        .isEqualTo(FailureReason.CONTAINER_START_FAILED));

        assertThat(verbsInvoked()).containsExactly("image", "create");
    }

    // ---- docker start fails -------------------------------------------------------------------

    @Test
    @DisplayName("docker start failing removes the container docker create just made")
    void startFailingRemovesTheContainer() {
        dockerProcess.script("image", success());
        dockerProcess.script("create", successWithStdout(CONTAINER_ID));
        dockerProcess.script("start", failure("container failed to start"));
        DockerImageTargetExecutor executor = executorAssumingDockerAvailable();

        assertThatThrownBy(() -> executor.prepare(requestFor(targetWithoutReadinessCheck())))
                .isInstanceOf(TargetPreparationException.class)
                .satisfies(e -> assertThat(((TargetPreparationException) e).reason())
                        .isEqualTo(FailureReason.CONTAINER_START_FAILED));

        assertThat(verbsInvoked()).containsExactly("image", "create", "start", "rm");
        assertThat(lastInvocationOf("rm")).contains(CONTAINER_ID);
    }

    // ---- port resolution fails ----------------------------------------------------------------

    @Test
    @DisplayName("unparseable docker inspect output fails with PORT_RESOLUTION_FAILED and removes the container")
    void portResolutionFailureRemovesTheContainer() {
        dockerProcess.script("image", success());
        dockerProcess.script("create", successWithStdout(CONTAINER_ID));
        dockerProcess.script("start", success());
        dockerProcess.script("inspect", successWithStdout("not json at all"));
        DockerImageTargetExecutor executor = executorAssumingDockerAvailable();

        assertThatThrownBy(() -> executor.prepare(requestFor(targetWithoutReadinessCheck())))
                .isInstanceOf(TargetPreparationException.class)
                .satisfies(e -> assertThat(((TargetPreparationException) e).reason())
                        .isEqualTo(FailureReason.PORT_RESOLUTION_FAILED));

        assertThat(verbsInvoked()).containsExactly("image", "create", "start", "inspect", "rm");
        assertThat(lastInvocationOf("rm")).contains(CONTAINER_ID);
    }

    @Test
    @DisplayName("an empty port mapping also fails with PORT_RESOLUTION_FAILED and removes the container")
    void missingPortMappingRemovesTheContainer() {
        dockerProcess.script("image", success());
        dockerProcess.script("create", successWithStdout(CONTAINER_ID));
        dockerProcess.script("start", success());
        // Valid JSON, but nothing mapped for 8080/tcp.
        dockerProcess.script("inspect", successWithStdout("{}"));
        DockerImageTargetExecutor executor = executorAssumingDockerAvailable();

        assertThatThrownBy(() -> executor.prepare(requestFor(targetWithoutReadinessCheck())))
                .isInstanceOf(TargetPreparationException.class)
                .satisfies(e -> assertThat(((TargetPreparationException) e).reason())
                        .isEqualTo(FailureReason.PORT_RESOLUTION_FAILED));

        assertThat(lastInvocationOf("rm")).contains(CONTAINER_ID);
    }

    // ---- readiness never succeeds --------------------------------------------------------------

    @Test
    @DisplayName("readiness never succeeding within its timeout removes the container")
    void readinessTimeoutRemovesTheContainer() {
        dockerProcess.script("image", success());
        dockerProcess.script("create", successWithStdout(CONTAINER_ID));
        dockerProcess.script("start", success());
        dockerProcess.script("inspect", successWithStdout(portMappingJson(8080, "49172")));

        // TCP layer opens immediately; the configured HTTP layer never returns the expected status,
        // and a short configured timeout keeps this test fast without touching the executor's
        // internal default.
        DockerImageTargetExecutor executor = executorWith(new AlwaysAvailableCapabilityProbe(),
                new ScriptedReadinessProbe(true, false));

        DockerImageTarget target = new DockerImageTarget(new ImageReference(IMAGE),
                new ContainerPort(8080), ResourceEnvelopeRequest.none(),
                new ReadinessCheck("/health", 200, Duration.ofMillis(300)));

        assertThatThrownBy(() -> executor.prepare(requestFor(target)))
                .isInstanceOf(TargetPreparationException.class)
                .satisfies(e -> assertThat(((TargetPreparationException) e).reason())
                        .isEqualTo(FailureReason.TARGET_READINESS_TIMEOUT));

        assertThat(verbsInvoked()).containsExactly("image", "create", "start", "inspect", "rm");
        assertThat(lastInvocationOf("rm")).contains(CONTAINER_ID);
    }

    // ---- unexpected runtime exception mid-prepare -----------------------------------------------

    @Test
    @DisplayName("an unexpected RuntimeException mid-prepare still removes the container")
    void unexpectedRuntimeExceptionRemovesTheContainer() {
        dockerProcess.script("image", success());
        dockerProcess.script("create", successWithStdout(CONTAINER_ID));
        dockerProcess.script("start", success());
        dockerProcess.script("inspect", successWithStdout(portMappingJson(8080, "49172")));

        TargetReadinessProbe explodingProbe = new TargetReadinessProbe() {
            @Override
            public boolean tcpPortIsReachable(String host, int port, Duration connectTimeout) {
                throw new IllegalStateException("simulated probe failure");
            }

            @Override
            public boolean httpCheckSucceeds(String host, int port, String path, int expectedStatus,
                    Duration requestTimeout) {
                return true;
            }
        };
        DockerImageTargetExecutor executor =
                executorWith(new AlwaysAvailableCapabilityProbe(), explodingProbe);

        assertThatThrownBy(() -> executor.prepare(requestFor(targetWithoutReadinessCheck())))
                .isInstanceOf(TargetPreparationException.class)
                .satisfies(e -> assertThat(((TargetPreparationException) e).reason())
                        .isEqualTo(FailureReason.CONTAINER_START_FAILED));

        assertThat(lastInvocationOf("rm")).contains(CONTAINER_ID);
    }

    // ---- full success ---------------------------------------------------------------------------

    @Test
    @DisplayName("full success parses the dynamic host port, applies labels, and pins no host port")
    void fullSuccessResolvesDynamicPort() {
        dockerProcess.script("image", success());
        dockerProcess.script("create", successWithStdout(CONTAINER_ID));
        dockerProcess.script("start", success());
        dockerProcess.script("inspect", successWithStdout(portMappingJson(8080, "49172")));
        DockerImageTargetExecutor executor = executorAssumingDockerAvailable();

        PreparedTarget prepared = executor.prepare(requestFor(targetWithoutReadinessCheck()));

        assertThat(prepared.resolvedTarget().endpoint().value()).isEqualTo("http://localhost:49172");
        assertThat(prepared.resolvedTarget().ownership()).isEqualTo(TargetOwnership.VORTEX_MANAGED);
        assertThat(prepared.resolvedTarget().telemetryHandleIfPresent()).contains(CONTAINER_ID);
        assertThat(prepared.resolvedTarget().resourcesIfPresent()).isEmpty();

        List<String> createCommand = dockerProcess.invocationFor("create");
        assertThat(createCommand)
                .contains("--label", "vortex.managed=true")
                .contains("--label", "vortex.execution=" + EXECUTION_ID.value())
                .contains("--label", "vortex.service=" + PROJECT_ID.value())
                .contains("-p", "8080")
                .endsWith(IMAGE);
        // No host port pinned: "-p" is followed immediately by the bare container port, never a
        // "host:container" pair.
        assertThat(createCommand.get(createCommand.indexOf("-p") + 1)).isEqualTo("8080");

        assertThat(verbsInvoked()).containsExactly("image", "create", "start", "inspect");
    }

    // ---- resource envelope: docker create flags ----------------------------------------------

    @Test
    @DisplayName("a CPU-only resource request adds --cpus and omits --memory")
    void cpuOnlyResourceRequestAddsCpusFlagOnly() {
        dockerProcess.script("image", success());
        dockerProcess.script("create", successWithStdout(CONTAINER_ID));
        dockerProcess.script("start", success());
        dockerProcess.scriptFormat("HostConfig", successWithStdout(hostConfigJson(500_000_000L, 0L)));
        dockerProcess.scriptFormat("NetworkSettings.Ports",
                successWithStdout(portMappingJson(8080, "49172")));
        DockerImageTargetExecutor executor = executorAssumingDockerAvailable();

        DockerImageTarget target = new DockerImageTarget(new ImageReference(IMAGE),
                new ContainerPort(8080),
                new ResourceEnvelopeRequest(CpuAllocation.ofMillicores(500), null), null);

        PreparedTarget prepared = executor.prepare(requestFor(target));

        List<String> createCommand = dockerProcess.invocationFor("create");
        assertThat(createCommand).contains("--cpus", "0.500");
        assertThat(createCommand).doesNotContain("--memory");
        assertThat(prepared.resolvedTarget().resourcesIfPresent()).hasValueSatisfying(resources -> {
            assertThat(resources.cpuIfPresent()).contains(CpuAllocation.ofMillicores(500));
            assertThat(resources.memoryIfPresent()).isEmpty();
        });
    }

    @Test
    @DisplayName("a memory-only resource request adds --memory and omits --cpus")
    void memoryOnlyResourceRequestAddsMemoryFlagOnly() {
        dockerProcess.script("image", success());
        dockerProcess.script("create", successWithStdout(CONTAINER_ID));
        dockerProcess.script("start", success());
        dockerProcess.scriptFormat("HostConfig", successWithStdout(hostConfigJson(0L, 536_870_912L)));
        dockerProcess.scriptFormat("NetworkSettings.Ports",
                successWithStdout(portMappingJson(8080, "49172")));
        DockerImageTargetExecutor executor = executorAssumingDockerAvailable();

        DockerImageTarget target = new DockerImageTarget(new ImageReference(IMAGE),
                new ContainerPort(8080),
                new ResourceEnvelopeRequest(null, MemoryAllocation.ofMebibytes(512)), null);

        PreparedTarget prepared = executor.prepare(requestFor(target));

        List<String> createCommand = dockerProcess.invocationFor("create");
        assertThat(createCommand).contains("--memory", "536870912");
        assertThat(createCommand).doesNotContain("--cpus");
        assertThat(prepared.resolvedTarget().resourcesIfPresent()).hasValueSatisfying(resources -> {
            assertThat(resources.cpuIfPresent()).isEmpty();
            assertThat(resources.memoryIfPresent()).contains(MemoryAllocation.ofMebibytes(512));
        });
    }

    @Test
    @DisplayName("both CPU and memory present adds both flags and both are confirmed on the resolved target")
    void bothResourcesPresentAddsBothFlags() {
        dockerProcess.script("image", success());
        dockerProcess.script("create", successWithStdout(CONTAINER_ID));
        dockerProcess.script("start", success());
        dockerProcess.scriptFormat("HostConfig",
                successWithStdout(hostConfigJson(500_000_000L, 536_870_912L)));
        dockerProcess.scriptFormat("NetworkSettings.Ports",
                successWithStdout(portMappingJson(8080, "49172")));
        DockerImageTargetExecutor executor = executorAssumingDockerAvailable();

        DockerImageTarget target = new DockerImageTarget(new ImageReference(IMAGE),
                new ContainerPort(8080),
                new ResourceEnvelopeRequest(CpuAllocation.ofMillicores(500),
                        MemoryAllocation.ofMebibytes(512)),
                null);

        PreparedTarget prepared = executor.prepare(requestFor(target));

        List<String> createCommand = dockerProcess.invocationFor("create");
        assertThat(createCommand).contains("--cpus", "0.500", "--memory", "536870912");
        assertThat(prepared.resolvedTarget().resourcesIfPresent()).hasValueSatisfying(resources -> {
            assertThat(resources.cpuIfPresent()).contains(CpuAllocation.ofMillicores(500));
            assertThat(resources.memoryIfPresent()).contains(MemoryAllocation.ofMebibytes(512));
        });
    }

    @Test
    @DisplayName("no resources requested means confirmResourceEnvelope never inspects HostConfig")
    void noResourcesRequestedNeverInspectsHostConfig() {
        dockerProcess.script("image", success());
        dockerProcess.script("create", successWithStdout(CONTAINER_ID));
        dockerProcess.script("start", success());
        dockerProcess.script("inspect", successWithStdout(portMappingJson(8080, "49172")));
        DockerImageTargetExecutor executor = executorAssumingDockerAvailable();

        executor.prepare(requestFor(targetWithoutReadinessCheck()));

        for (List<String> invocation : dockerProcess.invocations()) {
            assertThat(invocation).noneMatch(argument -> argument.contains("HostConfig"));
        }
        assertThat(verbsInvoked().stream().filter("inspect"::equals).count()).isEqualTo(1);
    }

    // ---- resource envelope: post-start confirmation failures -----------------------------------

    @Test
    @DisplayName("a CPU mismatch after start fails with RESOURCE_LIMIT_APPLICATION_FAILED and removes the container")
    void cpuMismatchRemovesTheContainer() {
        dockerProcess.script("image", success());
        dockerProcess.script("create", successWithStdout(CONTAINER_ID));
        dockerProcess.script("start", success());
        // Requested 500m but Docker reports only 250m actually applied.
        dockerProcess.scriptFormat("HostConfig", successWithStdout(hostConfigJson(250_000_000L, 0L)));
        DockerImageTargetExecutor executor = executorAssumingDockerAvailable();

        DockerImageTarget target = new DockerImageTarget(new ImageReference(IMAGE),
                new ContainerPort(8080),
                new ResourceEnvelopeRequest(CpuAllocation.ofMillicores(500), null), null);

        assertThatThrownBy(() -> executor.prepare(requestFor(target)))
                .isInstanceOf(TargetPreparationException.class)
                .satisfies(e -> assertThat(((TargetPreparationException) e).reason())
                        .isEqualTo(FailureReason.RESOURCE_LIMIT_APPLICATION_FAILED));

        assertThat(verbsInvoked()).containsExactly("image", "create", "start", "inspect", "rm");
        assertThat(lastInvocationOf("rm")).contains(CONTAINER_ID);
    }

    @Test
    @DisplayName("a memory mismatch after start fails with RESOURCE_LIMIT_APPLICATION_FAILED and removes the container")
    void memoryMismatchRemovesTheContainer() {
        dockerProcess.script("image", success());
        dockerProcess.script("create", successWithStdout(CONTAINER_ID));
        dockerProcess.script("start", success());
        // Requested 512 MiB but Docker reports a different byte count actually applied.
        dockerProcess.scriptFormat("HostConfig", successWithStdout(hostConfigJson(0L, 268_435_456L)));
        DockerImageTargetExecutor executor = executorAssumingDockerAvailable();

        DockerImageTarget target = new DockerImageTarget(new ImageReference(IMAGE),
                new ContainerPort(8080),
                new ResourceEnvelopeRequest(null, MemoryAllocation.ofMebibytes(512)), null);

        assertThatThrownBy(() -> executor.prepare(requestFor(target)))
                .isInstanceOf(TargetPreparationException.class)
                .satisfies(e -> assertThat(((TargetPreparationException) e).reason())
                        .isEqualTo(FailureReason.RESOURCE_LIMIT_APPLICATION_FAILED));

        assertThat(lastInvocationOf("rm")).contains(CONTAINER_ID);
    }

    @Test
    @DisplayName("docker inspect failing for HostConfig fails with RESOURCE_LIMIT_APPLICATION_FAILED and removes the container")
    void hostConfigInspectFailureRemovesTheContainer() {
        dockerProcess.script("image", success());
        dockerProcess.script("create", successWithStdout(CONTAINER_ID));
        dockerProcess.script("start", success());
        dockerProcess.scriptFormat("HostConfig", failure("daemon error reading container config"));
        DockerImageTargetExecutor executor = executorAssumingDockerAvailable();

        DockerImageTarget target = new DockerImageTarget(new ImageReference(IMAGE),
                new ContainerPort(8080),
                new ResourceEnvelopeRequest(CpuAllocation.ofMillicores(500), null), null);

        assertThatThrownBy(() -> executor.prepare(requestFor(target)))
                .isInstanceOf(TargetPreparationException.class)
                .satisfies(e -> assertThat(((TargetPreparationException) e).reason())
                        .isEqualTo(FailureReason.RESOURCE_LIMIT_APPLICATION_FAILED));

        assertThat(lastInvocationOf("rm")).contains(CONTAINER_ID);
    }

    @Test
    @DisplayName("unparseable HostConfig output fails with RESOURCE_LIMIT_APPLICATION_FAILED and removes the container")
    void hostConfigUnparseableRemovesTheContainer() {
        dockerProcess.script("image", success());
        dockerProcess.script("create", successWithStdout(CONTAINER_ID));
        dockerProcess.script("start", success());
        dockerProcess.scriptFormat("HostConfig", successWithStdout("not json at all"));
        DockerImageTargetExecutor executor = executorAssumingDockerAvailable();

        DockerImageTarget target = new DockerImageTarget(new ImageReference(IMAGE),
                new ContainerPort(8080),
                new ResourceEnvelopeRequest(CpuAllocation.ofMillicores(500), null), null);

        assertThatThrownBy(() -> executor.prepare(requestFor(target)))
                .isInstanceOf(TargetPreparationException.class)
                .satisfies(e -> assertThat(((TargetPreparationException) e).reason())
                        .isEqualTo(FailureReason.RESOURCE_LIMIT_APPLICATION_FAILED));

        assertThat(lastInvocationOf("rm")).contains(CONTAINER_ID);
    }

    // ---- cleanup idempotency ----------------------------------------------------------------------

    @Test
    @DisplayName("cleanup() called twice only issues one docker rm -f")
    void cleanupIsIdempotent() {
        dockerProcess.script("image", success());
        dockerProcess.script("create", successWithStdout(CONTAINER_ID));
        dockerProcess.script("start", success());
        dockerProcess.script("inspect", successWithStdout(portMappingJson(8080, "49172")));
        dockerProcess.script("rm", success());
        DockerImageTargetExecutor executor = executorAssumingDockerAvailable();

        PreparedTarget prepared = executor.prepare(requestFor(targetWithoutReadinessCheck()));

        var first = prepared.cleanup();
        var second = prepared.cleanup();

        assertThat(first.attempted()).isTrue();
        assertThat(first.succeeded()).isTrue();
        assertThat(second.attempted()).isFalse();
        assertThat(second.succeeded()).isTrue();

        assertThat(verbsInvoked().stream().filter("rm"::equals).count()).isEqualTo(1);
    }

    // ---- cleanup only ever targets this executor's own container ------------------------------------

    @Test
    @DisplayName("cleanup only ever removes the specific container id this executor's own create returned")
    void cleanupOnlyEverTargetsItsOwnContainer() {
        dockerProcess.script("image", success());
        dockerProcess.script("create", successWithStdout(CONTAINER_ID));
        dockerProcess.script("start", success());
        dockerProcess.script("inspect", successWithStdout(portMappingJson(8080, "49172")));
        dockerProcess.script("rm", success());
        DockerImageTargetExecutor executor = executorAssumingDockerAvailable();

        PreparedTarget prepared = executor.prepare(requestFor(targetWithoutReadinessCheck()));
        prepared.cleanup();

        // This class never issues a label-based or name-based lookup (no "docker ps", no "docker
        // container ls --filter") — every command that names a container id names the one id
        // captured from this executor's own "docker create" call, never anything else.
        for (List<String> invocation : dockerProcess.invocations()) {
            assertThat(invocation).doesNotContain("ps");
            for (String argument : invocation) {
                if (looksLikeAContainerId(argument)) {
                    assertThat(argument).isEqualTo(CONTAINER_ID);
                }
            }
        }
        assertThat(lastInvocationOf("rm")).contains(CONTAINER_ID);
    }

    private boolean looksLikeAContainerId(String argument) {
        return argument.equals(CONTAINER_ID) || argument.matches("[0-9a-f]{6,64}");
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private List<String> verbsInvoked() {
        List<String> verbs = new ArrayList<>();
        for (List<String> invocation : dockerProcess.invocations()) {
            verbs.add(invocation.size() > 1 ? invocation.get(1) : "");
        }
        return verbs;
    }

    private List<String> lastInvocationOf(String verb) {
        List<String> last = null;
        for (List<String> invocation : dockerProcess.invocations()) {
            if (invocation.size() > 1 && invocation.get(1).equals(verb)) {
                last = invocation;
            }
        }
        assertThat(last).as("an invocation of docker %s", verb).isNotNull();
        return last;
    }

    private static String portMappingJson(int containerPort, String hostPort) {
        return "{\"" + containerPort + "/tcp\":[{\"HostIp\":\"0.0.0.0\",\"HostPort\":\"" + hostPort
                + "\"}]}";
    }

    /** A {@code docker inspect --format '{{json .HostConfig}}'} fixture reporting the given
     *  applied {@code NanoCpus}/{@code Memory} — the exact fields {@code confirmResourceEnvelope}
     *  reads. A field's value is irrelevant whenever the corresponding resource wasn't requested,
     *  since it is then never checked. */
    private static String hostConfigJson(long nanoCpus, long memoryBytes) {
        return "{\"NanoCpus\":" + nanoCpus + ",\"Memory\":" + memoryBytes + "}";
    }

    private static DockerProcess.DockerCommandResult success() {
        return new DockerProcess.DockerCommandResult(0, List.of(), List.of());
    }

    private static DockerProcess.DockerCommandResult successWithStdout(String stdoutLine) {
        return new DockerProcess.DockerCommandResult(0, List.of(stdoutLine), List.of());
    }

    private static DockerProcess.DockerCommandResult failure(String stderrLine) {
        return new DockerProcess.DockerCommandResult(1, List.of(), List.of(stderrLine));
    }

    /** Records every command it is asked to run, in order, and answers with whatever was scripted
     *  for that command's verb (the second argument — {@code docker <verb> ...}), or — when a
     *  format fragment was scripted and matches — with that instead. A format fragment is needed
     *  because both port resolution and resource confirmation issue a plain {@code docker
     *  inspect}, distinguished only by their {@code --format} argument. */
    private static final class ScriptedDockerProcess extends DockerProcess {

        private final Map<String, DockerCommandResult> scriptedByVerb = new HashMap<>();
        private final Map<String, DockerCommandResult> scriptedByFormatFragment =
                new LinkedHashMap<>();
        private final List<List<String>> invocations = new ArrayList<>();
        private final DockerCommandResult defaultResult = success();

        void script(String verb, DockerCommandResult result) {
            scriptedByVerb.put(verb, result);
        }

        void scriptFormat(String formatFragment, DockerCommandResult result) {
            scriptedByFormatFragment.put(formatFragment, result);
        }

        List<List<String>> invocations() {
            return List.copyOf(invocations);
        }

        List<String> invocationFor(String verb) {
            for (List<String> invocation : invocations) {
                if (invocation.size() > 1 && invocation.get(1).equals(verb)) {
                    return invocation;
                }
            }
            throw new AssertionError("docker " + verb + " was never invoked");
        }

        @Override
        public DockerCommandResult run(List<String> command, Duration timeout) {
            invocations.add(List.copyOf(command));
            for (Map.Entry<String, DockerCommandResult> scripted
                    : scriptedByFormatFragment.entrySet()) {
                if (command.stream().anyMatch(argument -> argument.contains(scripted.getKey()))) {
                    return scripted.getValue();
                }
            }
            String verb = command.size() > 1 ? command.get(1) : "";
            return scriptedByVerb.getOrDefault(verb, defaultResult);
        }
    }

    private static final class AlwaysAvailableCapabilityProbe extends DockerCapabilityProbe {

        AlwaysAvailableCapabilityProbe() {
            super("docker");
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    private static final class ScriptedReadinessProbe implements TargetReadinessProbe {

        private final boolean tcpReady;
        private final boolean httpReady;

        ScriptedReadinessProbe(boolean tcpReady, boolean httpReady) {
            this.tcpReady = tcpReady;
            this.httpReady = httpReady;
        }

        @Override
        public boolean tcpPortIsReachable(String host, int port, Duration connectTimeout) {
            return tcpReady;
        }

        @Override
        public boolean httpCheckSucceeds(String host, int port, String path, int expectedStatus,
                Duration requestTimeout) {
            return httpReady;
        }
    }
}
