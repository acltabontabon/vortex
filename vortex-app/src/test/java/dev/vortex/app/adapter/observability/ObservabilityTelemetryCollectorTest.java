package dev.vortex.app.adapter.observability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.metrics.Aggregation;
import dev.vortex.core.metrics.MetricObservation;
import dev.vortex.core.metrics.MetricSource;
import dev.vortex.core.metrics.MetricUnit;
import dev.vortex.core.metrics.TimeWindow;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.port.ObservabilityProvider;
import dev.vortex.core.port.TelemetryCollector;
import dev.vortex.core.resource.ResourceKind;
import dev.vortex.core.resource.ResourceSample;
import dev.vortex.core.resource.ResourceSampleSink;
import dev.vortex.core.resource.ResourceSampleSinkFactory;
import dev.vortex.core.resource.ResourceScope;
import dev.vortex.core.resource.ResourceSignal;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.workload.ConstantArrivalRateShape;
import dev.vortex.core.workload.TestType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Behaviour that is easy to get wrong quietly: a race between the sampler thread and
 * {@code finish()}, two providers colliding on one signal id, a session that must never buffer more
 * than a constant amount of memory, and a session that must stop itself if nothing else ever does.
 */
class ObservabilityTelemetryCollectorTest {

    private static final ExecutionId EXECUTION_ID = ExecutionId.generate();

    @Test
    void finishWaitsForAnAlreadyInFlightSampleRatherThanRacingIt() throws InterruptedException {
        var plan = Fixtures.plan();
        var slowProvider = new SlowOnFirstCallProvider();
        var collector = new ObservabilityTelemetryCollector(List.of(), slowProvider,
                ResourceSampleSinkFactory.none());

        TelemetryCollector.Session session = collector.start(plan, EXECUTION_ID);
        // Waits for the sampler to have actually entered collect() before calling finish() — the
        // race under test is "a sample is in flight", not "the sampler thread hasn't run yet", which
        // is a different, uninteresting race that a plain interrupt() handles fine on its own.
        assertThat(slowProvider.collectStarted.await(5, TimeUnit.SECONDS)).isTrue();

        TelemetryCollector.Telemetry telemetry = session.finish(
                new TimeWindow(Fixtures.NOW, Fixtures.NOW.plusSeconds(30)));

        assertThat(telemetry.run())
                .as("the sample already in flight when finish() was called should be included, not "
                        + "silently dropped by a race between the sampler thread and finish()")
                .extracting(MetricObservation::id)
                .contains("metric:generator.cpu");
    }

    @Test
    void twoProvidersReportingTheSameBareSignalIdAreKeptAsTwoSeparateSeries() {
        var plan = Fixtures.plan();
        var providerA = new FixedReadingProvider("prometheus", "metric:system.cpu.utilization", 40.0);
        var providerB = new FixedReadingProvider("dynatrace", "metric:system.cpu.utilization", 90.0);
        var collector = new ObservabilityTelemetryCollector(List.of(providerA, providerB), null,
                ResourceSampleSinkFactory.none());

        TelemetryCollector.Session session = collector.start(plan, EXECUTION_ID);
        sleep(20);
        TelemetryCollector.Telemetry telemetry =
                session.finish(new TimeWindow(Fixtures.NOW, Fixtures.NOW.plusSeconds(5)));

        assertThat(telemetry.run())
                .as("two providers reporting an identically-named signal must not be folded into one "
                        + "series — each provider's own reading must survive independently")
                .hasSize(2);
        assertThat(telemetry.resourceSignals())
                .as("both providers' classifications must survive as distinct resource signals")
                .hasSize(2)
                .extracting(ResourceSignal::value)
                .containsExactlyInAnyOrder(40.0, 90.0);
    }

    @Test
    void streamsEveryClassifiedSampleToTheSink() {
        var plan = Fixtures.plan();
        var provider = new FixedReadingProvider("prometheus", "metric:system.cpu.utilization", 77.0);
        var recording = new RecordingSink();
        var collector = new ObservabilityTelemetryCollector(List.of(provider), null,
                executionId -> recording);

        TelemetryCollector.Session session = collector.start(plan, EXECUTION_ID);
        sleep(20);
        session.finish(new TimeWindow(Fixtures.NOW, Fixtures.NOW.plusSeconds(5)));

        assertThat(recording.samples)
                .as("every classified reading taken should have reached the sink as a raw sample")
                .isNotEmpty()
                .allSatisfy(sample -> {
                    assertThat(sample.providerId()).isEqualTo("prometheus");
                    assertThat(sample.signalId()).isEqualTo("metric:system.cpu.utilization");
                    assertThat(sample.kind()).isEqualTo(ResourceKind.CPU);
                    assertThat(sample.scope()).isEqualTo(ResourceScope.SYSTEM_UNDER_TEST);
                    assertThat(sample.value()).isEqualTo(77.0);
                });
        assertThat(recording.closedReasons)
                .as("close() must be called exactly once when the session ends normally")
                .containsExactly((String) null);
    }

    @Test
    void aSecondFinishCallReturnsTheSameTelemetryAndDoesNotCloseTheSinkAgain() {
        var plan = Fixtures.plan();
        var provider = new FixedReadingProvider("prometheus", "metric:system.cpu.utilization", 50.0);
        var recording = new RecordingSink();
        var collector = new ObservabilityTelemetryCollector(List.of(provider), null,
                executionId -> recording);

        TelemetryCollector.Session session = collector.start(plan, EXECUTION_ID);
        sleep(20);
        var window = new TimeWindow(Fixtures.NOW, Fixtures.NOW.plusSeconds(5));
        TelemetryCollector.Telemetry first = session.finish(window);
        TelemetryCollector.Telemetry second = session.finish(window);

        assertThat(second).isEqualTo(first);
        assertThat(recording.closedReasons)
                .as("finish() must be idempotent: a second call must not close the sink again")
                .hasSize(1);
    }

    @Test
    void distinctResourceSignalsAreCappedAndTheOverflowIsReportedNotDropped() {
        var plan = Fixtures.plan();
        List<ObservabilityProvider> manyProviders = new ArrayList<>();
        int total = ObservabilityTelemetryCollector.MAX_RETAINED_SIGNALS + 3;
        for (int i = 0; i < total; i++) {
            manyProviders.add(new FixedReadingProvider("provider", "metric:dependency." + i + ".latency",
                    10.0 + i));
        }
        var collector = new ObservabilityTelemetryCollector(manyProviders, null,
                ResourceSampleSinkFactory.none());

        TelemetryCollector.Session session = collector.start(plan, EXECUTION_ID);
        sleep(20);
        TelemetryCollector.Telemetry telemetry =
                session.finish(new TimeWindow(Fixtures.NOW, Fixtures.NOW.plusSeconds(5)));

        assertThat(telemetry.resourceSignals())
                .as("cardinality must be bounded rather than growing without limit")
                .hasSizeLessThanOrEqualTo(ObservabilityTelemetryCollector.MAX_RETAINED_SIGNALS);
        assertThat(telemetry.gaps())
                .as("the signals that did not fit must be reported, not silently dropped")
                .anySatisfy(gap -> assertThat(gap.detail()).contains("beyond the retention limit"));
    }

    @Test
    void watchdogIsDerivedFromThePlanRatherThanBeingAFixedCeiling() {
        var shortPlan = Fixtures.plan(TestType.AVERAGE_LOAD,
                ConstantArrivalRateShape.of(10, Duration.ofMinutes(2)));
        Duration shortWatchdog = ObservabilityTelemetryCollector.watchdogFor(shortPlan);
        // Two-minute plan: the 30-minute floor dominates the 20% margin, so the ceiling is the
        // planned duration plus 30 minutes.
        assertThat(shortWatchdog).isEqualTo(Duration.ofMinutes(2).plus(Duration.ofMinutes(30)));

        var longPlan = Fixtures.plan(TestType.AVERAGE_LOAD,
                ConstantArrivalRateShape.of(10, Duration.ofHours(5)));
        Duration longWatchdog = ObservabilityTelemetryCollector.watchdogFor(longPlan);
        // Five-hour plan: 20% of five hours (one hour) dominates the 30-minute floor.
        assertThat(longWatchdog).isEqualTo(Duration.ofHours(5).plus(Duration.ofHours(1)));
    }

    /** Signals when collect() begins, then sleeps, so a test can race finish() against it exactly. */
    private static final class SlowOnFirstCallProvider implements ObservabilityProvider {

        private final CountDownLatch collectStarted = new CountDownLatch(1);

        @Override
        public String id() {
            return "fake-generator";
        }

        @Override
        public List<String> defaultMetrics() {
            return List.of("cpu");
        }

        @Override
        public boolean isAvailable(ObservabilityQuery query) {
            return true;
        }

        @Override
        public Collected collect(ObservabilityQuery query) {
            collectStarted.countDown();
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            var observation = MetricObservation.of("metric:generator.cpu", "cpu", MetricSource.DERIVED,
                    MetricUnit.PERCENT, Aggregation.MAX, 42.0,
                    new TimeWindow(Instant.now(), Instant.now()));
            return Collected.of(List.of(observation));
        }
    }

    /** Always answers with the same reading, classified as CPU on the system under test. */
    private static final class FixedReadingProvider implements ObservabilityProvider {

        private final String providerId;
        private final String signalId;
        private final double value;

        FixedReadingProvider(String providerId, String signalId, double value) {
            this.providerId = providerId;
            this.signalId = signalId;
            this.value = value;
        }

        @Override
        public String id() {
            return providerId;
        }

        @Override
        public List<String> defaultMetrics() {
            return List.of(signalId);
        }

        @Override
        public boolean isAvailable(ObservabilityQuery query) {
            return true;
        }

        @Override
        public Collected collect(ObservabilityQuery query) {
            var observation = MetricObservation.of(signalId, signalId, MetricSource.PROMETHEUS,
                    MetricUnit.PERCENT, Aggregation.MAX, value,
                    new TimeWindow(Instant.now(), Instant.now()));
            var signal = ResourceSignal.unbounded(observation, ResourceKind.CPU,
                    ResourceScope.SYSTEM_UNDER_TEST);
            return new Collected(List.of(observation), List.of(), List.of(signal));
        }
    }

    /** Captures every sample and every close() call, for asserting the sink is fed and finalized
     *  the way the collector promises. */
    private static final class RecordingSink implements ResourceSampleSink {

        final List<ResourceSample> samples = new CopyOnWriteArrayList<>();
        final List<String> closedReasons = new CopyOnWriteArrayList<>();

        @Override
        public void accept(ResourceSample sample) {
            samples.add(sample);
        }

        @Override
        public void close(String reason) {
            closedReasons.add(reason);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
