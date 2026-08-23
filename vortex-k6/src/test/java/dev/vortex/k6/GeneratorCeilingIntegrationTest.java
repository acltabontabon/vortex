package dev.vortex.k6;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.port.PerformanceEngine;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.workload.OperationMix;
import dev.vortex.core.workload.RampingArrivalRateShape;
import dev.vortex.core.workload.Stage;
import dev.vortex.core.workload.TestType;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/**
 * Real k6, failing to generate the load it was asked for, on purpose.
 *
 * <p>The companion to {@code GeneratorCeilingWithheldTest} in {@code vortex-core}. That one proves
 * the withholding logic cannot regress; this one proves evidence of the right shape actually arrives
 * from the engine, which is a different and weaker-looking claim that the first is worthless without.
 *
 * <h2>Deterministic by plan, not by machine</h2>
 * "An offered rate the local generator cannot produce" is a property of the host, so a test written
 * that way passes on a laptop and silently stops exercising the path on a large build agent. This
 * makes it a property of the <em>experiment</em> instead: a constant-arrival-rate scenario demanding
 * a high rate with {@code maxVUs} clamped to one. One virtual user cannot start hundreds of
 * iterations per second on any hardware ever built, because the floor is one round trip per
 * iteration. k6 must drop iterations, and reports how many.
 *
 * <p>The clamp is honest rather than a mock: {@code k6Options} is a fingerprinted identity
 * dimension, so this is a real, reproducible experiment that happens to be configured to fail.
 *
 * <h2>Hermetic</h2>
 * The target is a {@link HttpServer} started here — JDK-native, no new dependency — rather than the
 * demo service. A gate test that needs another process running is a gate test that gets skipped.
 */
@EnabledIf("k6IsInstalled")
class GeneratorCeilingIntegrationTest {

    static boolean k6IsInstalled() {
        return new LocalBinaryK6Runner("k6").version().isPresent();
    }

    private HttpServer target;

    @BeforeEach
    void startTarget() throws IOException {
        target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        target.start();
    }

    @AfterEach
    void stopTarget() {
        if (target != null) {
            target.stop(0);
        }
    }

    private String targetUrl() {
        return "http://127.0.0.1:" + target.getAddress().getPort();
    }

    /**
     * A workload asking for far more than one virtual user can start.
     *
     * <p>Short, because the point is made in seconds and a gate test nobody waits for is a gate test
     * somebody disables.
     */
    private EffectiveTestPlan clampedTo(int maxVirtualUsers) {
        EffectiveTestPlan base = Fixtures.plan(TestType.AVERAGE_LOAD,
                new RampingArrivalRateShape(RequestsPerSecond.of(500), List.of(
                        new Stage(RequestsPerSecond.of(500), Duration.ofSeconds(5)))),
                OperationMix.single(Fixtures.GET_ORDER));

        return new EffectiveTestPlan(base.id(), base.projectId(), base.projectName(),
                base.serviceVersion(), base.intent(), base.workloadName(),
                base.workloadDescription(), base.testType(), base.workloadModel(),
                base.peakLevel(), base.stages(), base.operations(), base.datasets(),
                base.workloadSource(), base.thresholds(), base.environmentName(),
                base.environmentType(),
                new dev.vortex.core.target.ExternalEndpointTarget(
                        dev.vortex.core.environment.TargetUrl.of(targetUrl())),
                dev.vortex.core.environment.TargetUrl.of(targetUrl()),
                dev.vortex.core.environment.TargetUrl.of(targetUrl()),
                base.targetRewriteReason(), base.dependencyMode(), base.classification(),
                base.headers(),
                // The clamp. Merged last by the generator, so it wins over the pre-allocation
                // Vortex would otherwise choose.
                Map.of("preAllocatedVUs", String.valueOf(maxVirtualUsers),
                        "maxVUs", String.valueOf(maxVirtualUsers)),
                base.runner(), base.scriptSource(), base.safetyDecisions(), base.fingerprint(),
                base.validityPolicy(), base.workspacePath());
    }

    private PerformanceEngine.EngineOutcome run(EffectiveTestPlan plan, Path workspace) {
        var engine = new K6PerformanceEngine(new LocalBinaryK6Runner("k6"), new K6ScriptGenerator(),
                new K6SummaryParser(), new K6RawMetricsAggregator(), workspace, "test", false);

        return engine.execute(ExecutionId.of("ceiling"), plan, _ -> { },
                PerformanceEngine.Cancellation.never());
    }

    @Test
    @DisplayName("k6 reports the work it could not start, and Vortex reads it")
    void droppedWorkReachesTheDomain(@TempDir Path workspace) {
        var outcome = run(clampedTo(1), workspace);

        assertThat(outcome.producedResults()).isTrue();
        var generation = outcome.results().generation();

        // The property, never a number: whatever this machine is, one virtual user could not start
        // five hundred iterations a second, and k6 said so.
        assertThat(generation.wasReported()).isTrue();
        assertThat(generation.iterationsDroppedIfPresent())
                .hasValueSatisfying(dropped -> assertThat(dropped).isPositive());
        assertThat(generation.droppedWork()).isTrue();
    }

    @Test
    @DisplayName("the drops are attributed to buckets, so a level can be named")
    void dropsAreAttributedInTime(@TempDir Path workspace) {
        var outcome = run(clampedTo(1), workspace);

        // Per-bucket attribution is what lets a validity finding name the level at which the
        // generator fell behind, rather than only that it did somewhere in the run.
        assertThat(outcome.results().series().points())
                .anySatisfy(point -> assertThat(point.iterationsDroppedIfPresent()).isPresent());
    }

    @Test
    @DisplayName("and the run still reports everything it did measure")
    void nothingMeasuredIsLost(@TempDir Path workspace) {
        var outcome = run(clampedTo(1), workspace);

        // A run that failed to generate its load is not a run that measured nothing. Whatever
        // traffic was produced is real, and is reported as such.
        assertThat(outcome.results().requests()).isPositive();
        assertThat(outcome.results().reliability().wasReported()).isTrue();
    }
}
