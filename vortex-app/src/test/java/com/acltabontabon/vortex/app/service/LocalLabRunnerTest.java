package com.acltabontabon.vortex.app.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.acltabontabon.vortex.core.port.LocalLab;
import com.acltabontabon.vortex.core.shared.ProjectId;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LocalLabRunnerTest {

    private static final ProjectId CHECKOUT = ProjectId.of("checkout");
    private static final Path COMPOSE = Path.of("/tmp/checkout/compose.yaml");

    /**
     * A lab whose commands finish only when the test says so.
     *
     * <p>Hand-written rather than mocked: what these tests are about is what happens while a command
     * is still running, and that needs a command the test can hold open.
     */
    private static final class GatedLab implements LocalLab {
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicReference<String> lastPath = new AtomicReference<>();
        private final AtomicInteger statusCalls = new AtomicInteger();
        private LabResult result = new LabResult(true, "Dependencies started successfully.",
                List.of("checkout-db  Healthy"));
        private RuntimeException failure;

        @Override
        public LabStatus status() {
            statusCalls.incrementAndGet();
            return new LabStatus(true, true, true, "Docker version 28.0.4", "");
        }

        @Override
        public LabResult up(String composeFilePath) {
            lastPath.set(composeFilePath);
            started.countDown();
            awaitRelease();
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public LabResult down(String composeFilePath) {
            return up(composeFilePath);
        }

        private void awaitRelease() {
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void awaitFinished(LocalLabRunner runner) {
        awaitFinished(runner, CHECKOUT);
    }

    private static void awaitFinished(LocalLabRunner runner, ProjectId projectId) {
        awaitTrue(() -> !runner.isRunning(projectId));
    }

    /** Waits for a background command to land, rather than guessing at a sleep. */
    private static void awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.onSpinWait();
        }
        fail("the background command did not finish within 5 seconds");
    }

    @Test
    @DisplayName("reports a compose operation as running until it finishes")
    void reportsRunningUntilItFinishes() throws Exception {
        GatedLab lab = new GatedLab();
        LocalLabRunner runner = new LocalLabRunner(lab);

        assertThat(runner.start(CHECKOUT, LocalLabRunner.Operation.UP, COMPOSE)).isTrue();
        lab.started.await();

        assertThat(runner.isRunning(CHECKOUT)).isTrue();
        assertThat(runner.activity(CHECKOUT)).get()
                .extracting(LocalLabRunner.Activity::running).isEqualTo(true);

        lab.release.countDown();
        awaitFinished(runner);

        assertThat(runner.activity(CHECKOUT)).get()
                .extracting(LocalLabRunner.Activity::succeeded).isEqualTo(true);
    }

    @Test
    @DisplayName("refuses a second operation while one is in flight")
    void refusesConcurrentOperations() throws Exception {
        GatedLab lab = new GatedLab();
        LocalLabRunner runner = new LocalLabRunner(lab);

        assertThat(runner.start(CHECKOUT, LocalLabRunner.Operation.UP, COMPOSE)).isTrue();
        lab.started.await();

        // The claim covers both directions: a stop must not race a start into the same containers.
        assertThat(runner.start(CHECKOUT, LocalLabRunner.Operation.DOWN, COMPOSE)).isFalse();
        assertThat(runner.start(CHECKOUT, LocalLabRunner.Operation.UP, COMPOSE)).isFalse();

        lab.release.countDown();
        awaitFinished(runner);
    }

    @Test
    @DisplayName("records the compose file the operation actually used")
    void recordsTheComposeFileItRan() throws Exception {
        GatedLab lab = new GatedLab();
        LocalLabRunner runner = new LocalLabRunner(lab);
        Path other = Path.of("/tmp/checkout/infra/compose.yaml");

        runner.start(CHECKOUT, LocalLabRunner.Operation.UP, other);
        lab.started.await();
        lab.release.countDown();
        awaitFinished(runner);

        assertThat(runner.activity(CHECKOUT)).get()
                .extracting(LocalLabRunner.Activity::composeFile).isEqualTo(other);
        assertThat(lab.lastPath.get()).isEqualTo(other.toString());
    }

    @Test
    @DisplayName("keeps the last result so a returning visitor still sees the outcome")
    void keepsTheLastResult() throws Exception {
        GatedLab lab = new GatedLab();
        LocalLabRunner runner = new LocalLabRunner(lab);

        runner.start(CHECKOUT, LocalLabRunner.Operation.UP, COMPOSE);
        lab.started.await();
        lab.release.countDown();
        awaitFinished(runner);

        assertThat(runner.activity(CHECKOUT)).get()
                .extracting(a -> a.result().message())
                .isEqualTo("Dependencies started successfully.");
    }

    @Test
    @DisplayName("forgets an outcome when asked, so a stale one cannot describe a new compose file")
    void forgetsOnRequest() throws Exception {
        GatedLab lab = new GatedLab();
        LocalLabRunner runner = new LocalLabRunner(lab);

        runner.start(CHECKOUT, LocalLabRunner.Operation.UP, COMPOSE);
        lab.started.await();
        lab.release.countDown();
        awaitFinished(runner);

        runner.forget(CHECKOUT);

        assertThat(runner.activity(CHECKOUT)).isEmpty();
    }

    @Test
    @DisplayName("forgets the oldest finished operation rather than growing without bound")
    void evictsTheOldestFinishedOperation() {
        LocalLab immediate = new LocalLab() {
            @Override
            public LabStatus status() {
                return new LabStatus(true, true, true, "docker", "");
            }

            @Override
            public LabResult up(String composeFilePath) {
                return new LabResult(true, "done", List.of());
            }

            @Override
            public LabResult down(String composeFilePath) {
                return up(composeFilePath);
            }
        };
        LocalLabRunner runner = new LocalLabRunner(immediate);

        for (int i = 0; i < 40; i++) {
            ProjectId id = ProjectId.of("service-" + i);
            runner.start(id, LocalLabRunner.Operation.UP, COMPOSE);
            awaitFinished(runner, id);
        }

        assertThat(runner.activity(ProjectId.of("service-0"))).isEmpty();
        assertThat(runner.activity(ProjectId.of("service-39"))).isPresent();
    }

    @Test
    @DisplayName("contains a failing compose run instead of propagating it")
    void containsAFailure() throws Exception {
        GatedLab lab = new GatedLab();
        lab.failure = new IllegalStateException("docker went away");
        LocalLabRunner runner = new LocalLabRunner(lab);

        assertThat(runner.start(CHECKOUT, LocalLabRunner.Operation.UP, COMPOSE)).isTrue();
        lab.started.await();
        lab.release.countDown();
        awaitFinished(runner);

        assertThat(runner.activity(CHECKOUT)).get()
                .extracting(LocalLabRunner.Activity::failed).isEqualTo(true);
        assertThat(runner.activity(CHECKOUT).orElseThrow().result().output())
                .contains("docker went away");
    }

    @Test
    @DisplayName("reuses a recent capability answer rather than probing Docker on every page load")
    void memoisesCapability() {
        GatedLab lab = new GatedLab();
        LocalLabRunner runner = new LocalLabRunner(lab);

        runner.status();
        runner.status();
        runner.status();

        assertThat(lab.statusCalls.get()).isEqualTo(1);
    }
}
