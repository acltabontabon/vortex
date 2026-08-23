package dev.vortex.app.adapter.target.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vortex.core.application.PreflightCheck;
import dev.vortex.core.execution.FailureReason;
import dev.vortex.core.target.ContainerPort;
import dev.vortex.core.target.DockerComposeTarget;
import dev.vortex.core.target.PreparedTarget;
import dev.vortex.core.target.TargetOwnership;
import dev.vortex.core.target.TargetPreparationException;
import dev.vortex.core.target.TargetPreparationRequest;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.shared.ProjectId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link DockerComposeTargetExecutor} against a scripted {@link DockerProcess} — no real Docker
 * daemon is available in this environment, so every one of these proves behavior through the fake's
 * recorded invocations, not by watching a real Compose stack.
 *
 * <p>The class-level property under test throughout is that this executor is structurally attach-
 * only: {@link #everyScenarioNeverIssuesUpDownOrRm} asserts, across every scenario defined in this
 * file (success and every failure path), that the fake's complete recorded command history never
 * contains {@code up}, {@code down}, or {@code rm} as a Compose subcommand — not merely that the
 * outcome was a particular {@link FailureReason}.
 */
class DockerComposeTargetExecutorTest {

    private static final ExecutionId EXECUTION_ID = ExecutionId.of("exec-1");
    private static final ProjectId PROJECT_ID = ProjectId.of("project-1");
    private static final String SERVICE_NAME = "payment-service";
    private static final int CONTAINER_PORT = 8080;

    @TempDir
    Path workspace;

    private final ScriptedDockerProcess dockerProcess = new ScriptedDockerProcess();
    private final DockerComposeTargetExecutor executor =
            new DockerComposeTargetExecutor("docker", dockerProcess);

    private Path composeFile;

    @BeforeEach
    void writeComposeFile() throws IOException {
        composeFile = workspace.resolve("compose.yaml");
        Files.writeString(composeFile, "services:\n  " + SERVICE_NAME + ":\n    image: whatever\n");
    }

    private DockerComposeTarget target() {
        return new DockerComposeTarget("compose.yaml", SERVICE_NAME, new ContainerPort(CONTAINER_PORT));
    }

    private TargetPreparationRequest request() {
        return new TargetPreparationRequest(EXECUTION_ID, PROJECT_ID, target(), message -> { },
                workspace.toString());
    }

    // ---- compose file missing on disk ------------------------------------------------------

    @Test
    @DisplayName("a Compose file missing on disk fails with COMPOSE_FILE_NOT_FOUND before any docker command")
    void missingComposeFileFailsBeforeAnyCommand() throws IOException {
        Files.delete(composeFile);

        assertThatThrownBy(() -> executor.prepare(request()))
                .isInstanceOf(TargetPreparationException.class)
                .satisfies(e -> assertThat(((TargetPreparationException) e).reason())
                        .isEqualTo(FailureReason.COMPOSE_FILE_NOT_FOUND));

        assertThat(dockerProcess.invocations()).isEmpty();
    }

    // ---- docker compose config --services fails ---------------------------------------------

    @Test
    @DisplayName("'docker compose config --services' failing (malformed YAML, Docker unavailable, "
            + "etc.) is reported as COMPOSE_FILE_NOT_FOUND — reused rather than a fourth reason, "
            + "since either way the file gives this executor nothing to work with")
    void composeConfigFailureIsReportedAsFileNotFound() {
        dockerProcess.script("config", failure("yaml: line 4: mapping values are not allowed here"));

        assertThatThrownBy(() -> executor.prepare(request()))
                .isInstanceOf(TargetPreparationException.class)
                .satisfies(e -> assertThat(((TargetPreparationException) e).reason())
                        .isEqualTo(FailureReason.COMPOSE_FILE_NOT_FOUND));
    }

    // ---- service absent from config --services -----------------------------------------------

    @Test
    @DisplayName("a service the Compose file never declares fails with COMPOSE_SERVICE_NOT_FOUND")
    void undeclaredServiceFails() {
        dockerProcess.script("config", successWithStdout("some-other-service"));

        assertThatThrownBy(() -> executor.prepare(request()))
                .isInstanceOf(TargetPreparationException.class)
                .satisfies(e -> assertThat(((TargetPreparationException) e).reason())
                        .isEqualTo(FailureReason.COMPOSE_SERVICE_NOT_FOUND));

        assertThat(subcommandsInvoked()).containsExactly("config");
    }

    // ---- service declared but not running ------------------------------------------------------

    @Test
    @DisplayName("a service declared but not currently running fails with COMPOSE_SERVICE_NOT_RUNNING")
    void declaredButNotRunningFails() {
        dockerProcess.script("config", successWithStdout(SERVICE_NAME, "some-other-service"));
        dockerProcess.script("ps", successWithStdout("some-other-service"));

        assertThatThrownBy(() -> executor.prepare(request()))
                .isInstanceOf(TargetPreparationException.class)
                .satisfies(e -> assertThat(((TargetPreparationException) e).reason())
                        .isEqualTo(FailureReason.COMPOSE_SERVICE_NOT_RUNNING));

        assertThat(subcommandsInvoked()).containsExactly("config", "ps");
    }

    // ---- running service, port resolves --------------------------------------------------------

    @Test
    @DisplayName("a running service whose port resolves returns the correct TargetUrl, ownership EXTERNAL")
    void runningServiceResolvesCorrectTargetUrl() {
        dockerProcess.script("config", successWithStdout(SERVICE_NAME));
        dockerProcess.script("ps", successWithStdout(SERVICE_NAME));
        dockerProcess.script("port", successWithStdout("0.0.0.0:32768"));

        PreparedTarget prepared = executor.prepare(request());

        assertThat(prepared.resolvedTarget().endpoint().value()).isEqualTo("http://localhost:32768");
        assertThat(prepared.resolvedTarget().ownership()).isEqualTo(TargetOwnership.EXTERNAL);
        assertThat(subcommandsInvoked()).containsExactly("config", "ps", "port");

        List<String> portCommand = dockerProcess.invocationFor("port");
        assertThat(portCommand).contains(SERVICE_NAME, String.valueOf(CONTAINER_PORT));
    }

    // ---- port resolution fails ------------------------------------------------------------------

    @Test
    @DisplayName("'docker compose port' failing fails with PORT_RESOLUTION_FAILED")
    void portCommandFailureFailsWithPortResolutionFailed() {
        dockerProcess.script("config", successWithStdout(SERVICE_NAME));
        dockerProcess.script("ps", successWithStdout(SERVICE_NAME));
        dockerProcess.script("port", failure("no port mapping found"));

        assertThatThrownBy(() -> executor.prepare(request()))
                .isInstanceOf(TargetPreparationException.class)
                .satisfies(e -> assertThat(((TargetPreparationException) e).reason())
                        .isEqualTo(FailureReason.PORT_RESOLUTION_FAILED));
    }

    @Test
    @DisplayName("empty 'docker compose port' output also fails with PORT_RESOLUTION_FAILED")
    void emptyPortOutputFailsWithPortResolutionFailed() {
        dockerProcess.script("config", successWithStdout(SERVICE_NAME));
        dockerProcess.script("ps", successWithStdout(SERVICE_NAME));
        dockerProcess.script("port", success());

        assertThatThrownBy(() -> executor.prepare(request()))
                .isInstanceOf(TargetPreparationException.class)
                .satisfies(e -> assertThat(((TargetPreparationException) e).reason())
                        .isEqualTo(FailureReason.PORT_RESOLUTION_FAILED));
    }

    @Test
    @DisplayName("unparseable 'docker compose port' output also fails with PORT_RESOLUTION_FAILED")
    void unparseablePortOutputFailsWithPortResolutionFailed() {
        dockerProcess.script("config", successWithStdout(SERVICE_NAME));
        dockerProcess.script("ps", successWithStdout(SERVICE_NAME));
        dockerProcess.script("port", successWithStdout("not-a-port-mapping"));

        assertThatThrownBy(() -> executor.prepare(request()))
                .isInstanceOf(TargetPreparationException.class)
                .satisfies(e -> assertThat(((TargetPreparationException) e).reason())
                        .isEqualTo(FailureReason.PORT_RESOLUTION_FAILED));
    }

    // ---- checkAvailability --------------------------------------------------------------------

    @Test
    @DisplayName("checkAvailability: a missing Compose file fails the first check and skips the other two")
    void checkAvailabilityMissingComposeFile() throws IOException {
        Files.delete(composeFile);

        List<PreflightCheck> checks =
                executor.checkAvailability(target(), workspace.toString());

        assertThat(checks).hasSize(3);
        assertThat(checks.get(0).name()).isEqualTo("Compose file found");
        assertThat(checks.get(0).status()).isEqualTo(PreflightCheck.Status.FAIL);
        assertThat(checks.get(1).name()).isEqualTo(SERVICE_NAME + " found");
        assertThat(checks.get(1).status()).isEqualTo(PreflightCheck.Status.SKIPPED);
        assertThat(checks.get(2).name()).isEqualTo("Service running");
        assertThat(checks.get(2).status()).isEqualTo(PreflightCheck.Status.SKIPPED);
        assertThat(dockerProcess.invocations()).isEmpty();
    }

    @Test
    @DisplayName("checkAvailability: an undeclared service passes the file check, fails the service "
            + "check, and skips 'Service running'")
    void checkAvailabilityUndeclaredService() {
        dockerProcess.script("config", successWithStdout("some-other-service"));

        List<PreflightCheck> checks =
                executor.checkAvailability(target(), workspace.toString());

        assertThat(checks).hasSize(3);
        assertThat(checks.get(0).status()).isEqualTo(PreflightCheck.Status.PASS);
        assertThat(checks.get(1).name()).isEqualTo(SERVICE_NAME + " found");
        assertThat(checks.get(1).status()).isEqualTo(PreflightCheck.Status.FAIL);
        assertThat(checks.get(2).status()).isEqualTo(PreflightCheck.Status.SKIPPED);
        assertThat(subcommandsInvoked()).containsExactly("config");
    }

    @Test
    @DisplayName("checkAvailability: a declared but not-running service passes the first two checks "
            + "and fails 'Service running'")
    void checkAvailabilityServiceNotRunning() {
        dockerProcess.script("config", successWithStdout(SERVICE_NAME, "some-other-service"));
        dockerProcess.script("ps", successWithStdout("some-other-service"));

        List<PreflightCheck> checks =
                executor.checkAvailability(target(), workspace.toString());

        assertThat(checks).hasSize(3);
        assertThat(checks.get(0).status()).isEqualTo(PreflightCheck.Status.PASS);
        assertThat(checks.get(1).status()).isEqualTo(PreflightCheck.Status.PASS);
        assertThat(checks.get(2).name()).isEqualTo("Service running");
        assertThat(checks.get(2).status()).isEqualTo(PreflightCheck.Status.FAIL);
        assertThat(subcommandsInvoked()).containsExactly("config", "ps");
    }

    @Test
    @DisplayName("checkAvailability: a running service passes all three checks, and never resolves "
            + "a port — only 'config' and 'ps' are ever invoked")
    void checkAvailabilityRunningServicePassesAllThree() {
        dockerProcess.script("config", successWithStdout(SERVICE_NAME));
        dockerProcess.script("ps", successWithStdout(SERVICE_NAME));

        List<PreflightCheck> checks =
                executor.checkAvailability(target(), workspace.toString());

        assertThat(checks).hasSize(3);
        assertThat(checks).allSatisfy(check ->
                assertThat(check.status()).isEqualTo(PreflightCheck.Status.PASS));
        assertThat(checks.get(0).name()).isEqualTo("Compose file found");
        assertThat(checks.get(1).name()).isEqualTo(SERVICE_NAME + " found");
        assertThat(checks.get(2).name()).isEqualTo("Service running");
        assertThat(subcommandsInvoked()).containsExactly("config", "ps");
    }

    // ---- cleanup ----------------------------------------------------------------------------------

    @Test
    @DisplayName("cleanup() on a successfully-prepared target is unconditionally NOTHING_TO_DO and "
            + "issues no command at all")
    void cleanupIsAlwaysNothingToDoAndIssuesNoCommand() {
        dockerProcess.script("config", successWithStdout(SERVICE_NAME));
        dockerProcess.script("ps", successWithStdout(SERVICE_NAME));
        dockerProcess.script("port", successWithStdout("0.0.0.0:32768"));

        PreparedTarget prepared = executor.prepare(request());
        int invocationsBeforeCleanup = dockerProcess.invocations().size();

        var outcome = prepared.cleanup();

        assertThat(outcome).isEqualTo(dev.vortex.core.target.CleanupOutcome.NOTHING_TO_DO);
        assertThat(outcome.attempted()).isFalse();
        assertThat(outcome.succeeded()).isTrue();
        assertThat(dockerProcess.invocations()).hasSize(invocationsBeforeCleanup);
    }

    // ---- the critical safety property -------------------------------------------------------------

    @Test
    @DisplayName("across every scenario in this class — success and every failure — no command this "
            + "executor issues is ever 'up', 'down', or 'rm'")
    void everyScenarioNeverIssuesUpDownOrRm() throws IOException {
        // Each scenario configures its own fresh fake and (optionally) deletes the compose file, so
        // every failure path this class knows about — including the one that never issues a single
        // docker command — is exercised here, not only the outcome each has its own dedicated test
        // for above.
        List<java.util.function.Consumer<ScriptedDockerProcess>> scenarios = List.of(
                fake -> { /* compose file missing on disk: scripted below, before prepare() runs */ },
                fake -> fake.script("config", failure("malformed compose file")),
                fake -> fake.script("config", successWithStdout("some-other-service")),
                fake -> {
                    fake.script("config", successWithStdout(SERVICE_NAME, "some-other-service"));
                    fake.script("ps", successWithStdout("some-other-service"));
                },
                fake -> {
                    fake.script("config", successWithStdout(SERVICE_NAME));
                    fake.script("ps", successWithStdout(SERVICE_NAME));
                    fake.script("port", successWithStdout("0.0.0.0:32768"));
                },
                fake -> {
                    fake.script("config", successWithStdout(SERVICE_NAME));
                    fake.script("ps", successWithStdout(SERVICE_NAME));
                    fake.script("port", failure("no port mapping found"));
                });

        boolean deleteComposeFileFirst = true;
        for (var scenario : scenarios) {
            ScriptedDockerProcess fake = new ScriptedDockerProcess();
            scenario.accept(fake);
            DockerComposeTargetExecutor scenarioExecutor =
                    new DockerComposeTargetExecutor("docker", fake);

            if (deleteComposeFileFirst) {
                Files.deleteIfExists(composeFile);
            } else if (Files.notExists(composeFile)) {
                writeComposeFile();
            }
            deleteComposeFileFirst = false;

            try {
                scenarioExecutor.prepare(request());
            } catch (TargetPreparationException ignored) {
                // Every scenario but the successful one is expected to fail; the assertion below is
                // what matters here, not whether this particular scenario threw.
            }

            // checkAvailability must be exactly as safe as prepare() already is — run it against the
            // same scenario and fake, so its own recorded invocations join the same assertion below.
            scenarioExecutor.checkAvailability(target(), workspace.toString());

            for (List<String> invocation : fake.invocations()) {
                assertThat(invocation).doesNotContain("up", "down", "rm");
            }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    private List<String> subcommandsInvoked() {
        List<String> subcommands = new ArrayList<>();
        for (List<String> invocation : dockerProcess.invocations()) {
            subcommands.add(subcommandOf(invocation));
        }
        return subcommands;
    }

    /** The Compose subcommand of a {@code docker compose -f <path> <subcommand> ...} invocation —
     *  always at index 4, since the compose file path is always exactly one token. */
    private static String subcommandOf(List<String> invocation) {
        return invocation.size() > 4 ? invocation.get(4) : "";
    }

    private static DockerProcess.DockerCommandResult success() {
        return new DockerProcess.DockerCommandResult(0, List.of(), List.of());
    }

    private static DockerProcess.DockerCommandResult successWithStdout(String... stdoutLines) {
        return new DockerProcess.DockerCommandResult(0, List.of(stdoutLines), List.of());
    }

    private static DockerProcess.DockerCommandResult failure(String stderrLine) {
        return new DockerProcess.DockerCommandResult(1, List.of(), List.of(stderrLine));
    }

    /** Records every command it is asked to run, in order, and answers with whatever was scripted
     *  for that command's Compose subcommand (the fifth argument — {@code docker compose -f <path>
     *  <subcommand> ...}), defaulting to success when nothing was scripted for it. */
    private static final class ScriptedDockerProcess extends DockerProcess {

        private final Map<String, DockerCommandResult> scriptedBySubcommand = new HashMap<>();
        private final List<List<String>> invocations = new ArrayList<>();
        private final DockerCommandResult defaultResult = success();

        void script(String subcommand, DockerCommandResult result) {
            scriptedBySubcommand.put(subcommand, result);
        }

        List<List<String>> invocations() {
            return List.copyOf(invocations);
        }

        List<String> invocationFor(String subcommand) {
            for (List<String> invocation : invocations) {
                if (subcommandOf(invocation).equals(subcommand)) {
                    return invocation;
                }
            }
            throw new AssertionError("docker compose " + subcommand + " was never invoked");
        }

        @Override
        public DockerCommandResult run(List<String> command, Duration timeout) {
            invocations.add(List.copyOf(command));
            String subcommand = subcommandOf(command);
            return scriptedBySubcommand.getOrDefault(subcommand, defaultResult);
        }
    }
}
