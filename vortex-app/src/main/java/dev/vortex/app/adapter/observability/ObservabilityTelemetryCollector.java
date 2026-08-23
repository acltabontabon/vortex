package dev.vortex.app.adapter.observability;

import dev.vortex.core.metrics.Aggregation;
import dev.vortex.core.metrics.MetricObservation;
import dev.vortex.core.metrics.ObservationTrace;
import dev.vortex.core.metrics.StageTelemetry;
import dev.vortex.core.metrics.TelemetryAvailability;
import dev.vortex.core.metrics.TelemetryGap;
import dev.vortex.core.metrics.TimeWindow;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.port.ObservabilityProvider;
import dev.vortex.core.port.TelemetryCollector;
import dev.vortex.core.resource.ResourceSample;
import dev.vortex.core.resource.ResourceSampleSink;
import dev.vortex.core.resource.ResourceSampleSinkFactory;
import dev.vortex.core.resource.ResourceSignal;
import dev.vortex.core.shared.ExecutionId;
import dev.vortex.core.workload.StageWindowBasis;
import dev.vortex.core.workload.StageWindows;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Samples the service under test throughout a run, keeping the peak of each measurement — overall,
 * and again for each level the workload held — while streaming every raw sample to that execution's
 * own artifact, so a report can show more than the peak without Vortex becoming an observability
 * platform.
 *
 * <h2>Why the peak</h2>
 * The question a bottleneck investigation asks is "what ran out?", and the answer lives at the
 * worst moment, not the average one. A pool that sat at 100% for two minutes of a ten-minute run
 * averages 20% — a number that would send an engineer looking somewhere else entirely.
 *
 * <p>Counters and timers, which only ever grow, are equally well served by their last value, so
 * taking the maximum is correct for both kinds without needing to distinguish them.
 *
 * <h2>Why also per stage</h2>
 * One peak for the whole run answers "what got hot" and cannot answer "when". "What changed as load
 * increased" is the question a breakpoint investigation actually asks, and answering it needs the
 * service's own view cut the same way the load generator's already is.
 *
 * <p>The per-stage windows here are computed from the plan, anchored at the moment sampling began.
 * They are labelled {@link StageWindowBasis#DERIVED_FROM_PLAN} accordingly — this collector watches
 * the service, not the load generator, so it has no way to observe when a stage really started. The
 * analyzer may later replace them with measured boundaries where k6's own output establishes them.
 *
 * <h2>Why it also streams raw samples</h2>
 * The peak/first/last summary above answers "how much"; it cannot answer "did CPU saturation precede
 * the breakpoint". Every sample this collector takes is also handed to a {@link ResourceSampleSink},
 * which persists it as part of the execution's own artifact — bounded, run-scoped, and read back only
 * when someone opens that run's evidence, never queried live the way a real observability platform's
 * telemetry would be. The peak/first/last aggregation below is unaffected either way: it is computed
 * online, in constant memory, independent of whether the raw stream can be persisted at all.
 *
 * <h2>Why it is cheap</h2>
 * One sample every few seconds, on a virtual thread that is otherwise blocked. A handful of small
 * HTTP requests per minute against a service already receiving thousands is not a meaningful
 * perturbation — but the interval is deliberately not shorter than it needs to be, because a
 * measurement instrument that changes the thing it measures is worse than no instrument.
 */
public final class ObservabilityTelemetryCollector implements TelemetryCollector {

    private static final Logger log =
            LoggerFactory.getLogger(ObservabilityTelemetryCollector.class);

    /** How often the service is sampled during a run. */
    static final Duration SAMPLE_INTERVAL = Duration.ofSeconds(5);

    /** The larger of this, or a fifth of the plan's own duration, is added to the plan's declared
     *  duration to get a session's safety ceiling — see {@link #watchdogFor}. */
    private static final Duration MIN_WATCHDOG_MARGIN = Duration.ofMinutes(30);

    /**
     * Distinct resource signals retained per run, globally rather than per provider or per kind.
     *
     * <p>CPU and memory are single-valued; the real risk is a provider that fans a signal out per
     * dimension — Dynatrace splitting dependency latency by downstream service name being the
     * concrete example in this codebase. Reused rather than invented: this is the same bound
     * {@code K6RawMetricsAggregator} already trusts for an analogous problem, distinct HTTP status
     * codes. A safety mechanism against a badly configured or high-cardinality provider, not a normal
     * operating constraint — a well-instrumented run sits far under it.
     */
    static final int MAX_RETAINED_SIGNALS = 64;

    private final List<ObservabilityProvider> providers;

    /**
     * The generator observer, sampled on every run regardless of what else is configured.
     *
     * <p>Held apart from the list above because it is not optional in the way those are. A team that
     * has wired up no observability at all still needs their run to say whether the load they asked
     * for was actually produced — and that question is answered by measuring Vortex's own machine,
     * which is always available to Vortex. Folding it into the configured list would have made the
     * one universally answerable question the first to go missing.
     */
    private final ObservabilityProvider generator;

    private final ResourceSampleSinkFactory sinkFactory;

    public ObservabilityTelemetryCollector(List<ObservabilityProvider> providers,
            ObservabilityProvider generator, ResourceSampleSinkFactory sinkFactory) {
        this.providers = List.copyOf(providers);
        this.generator = generator;
        this.sinkFactory = sinkFactory;
    }

    @Override
    public Session start(EffectiveTestPlan plan, ExecutionId executionId) {
        Instant now = Instant.now();
        String endpoint = plan.effectiveTarget().value();

        var correlation = ObservabilityProvider.RunCorrelation.of(null, plan.fingerprint());

        List<ObservabilityProvider> reachable = new ArrayList<>();
        List<TelemetryGap> gaps = new ArrayList<>();

        for (ObservabilityProvider provider : providers) {
            var probe = new ObservabilityProvider.ObservabilityQuery(endpoint,
                    new TimeWindow(now, now), provider.defaultMetrics(), correlation);
            try {
                if (provider.isAvailable(probe)) {
                    reachable.add(provider);
                } else {
                    // A provider that was asked and could not answer is recorded, not omitted.
                    // "Nobody looked" and "we looked and there was nothing" are different findings,
                    // and only one of them means the question is still open.
                    gaps.add(TelemetryGap.of(provider.id(), "",
                            TelemetryAvailability.UNREACHABLE));
                    log.debug("No {} telemetry available at {}; the run will report client-side "
                            + "measurements only from that provider", provider.id(), endpoint);
                }
            } catch (RuntimeException e) {
                gaps.add(new TelemetryGap(provider.id(), "", TelemetryAvailability.UNREACHABLE,
                        e.getMessage() == null ? "" : e.getMessage()));
            }
        }

        // The generator is added last and unconditionally. A run against a service with no telemetry
        // at all still measures the machine that produced its traffic, which is what makes "every run
        // carries generator signals, or a recorded reason it does not" true rather than aspirational.
        if (generator != null) {
            reachable.add(generator);
        }

        if (reachable.isEmpty()) {
            return Session.refused(gaps);
        }

        return new SamplingSession(reachable, endpoint,
                StageWindows.fromPlan(plan.stages(), now), correlation, gaps,
                openSink(executionId), watchdogFor(plan));
    }

    /**
     * The safety ceiling for a session that is never told to stop.
     *
     * <p>Not the primary lifecycle authority — {@code ExecutionService} owns that, stopping every
     * session exactly when the execution it belongs to ends, on every exit path, via a guaranteed
     * {@code finally}. This exists only for the one case that mechanism cannot reach: a hung
     * {@code engine.execute()} that never returns and never throws, so {@code ExecutionService}'s own
     * cleanup never runs either. A flat cap would either be too short for a long soak or too long a
     * backstop for a short smoke test, so it is derived from what this run actually asked for.
     */
    static Duration watchdogFor(EffectiveTestPlan plan) {
        Duration planned = plan.totalDuration();
        Duration margin = planned.dividedBy(5);
        if (margin.compareTo(MIN_WATCHDOG_MARGIN) < 0) {
            margin = MIN_WATCHDOG_MARGIN;
        }
        return planned.plus(margin);
    }

    private ResourceSampleSink openSink(ExecutionId executionId) {
        if (executionId == null) {
            return ResourceSampleSink.discard();
        }
        try {
            return sinkFactory.open(executionId);
        } catch (RuntimeException e) {
            // The peak/first/last summary this session still produces is unaffected either way —
            // only the raw series is lost, and it is lost honestly rather than silently.
            log.warn("Resource telemetry could not open an artifact for execution {}; this run's "
                    + "peak/first/last summary is unaffected, but no raw series will be retained: {}",
                    executionId, e.getMessage());
            return ResourceSampleSink.discard();
        }
    }

    /** Identifies one signal across the readings that describe it, qualified by which provider
     *  reported it. Two providers can report the same bare id for two different measurements —
     *  Prometheus and Dynatrace both use {@code system.cpu.utilization}, for instance — and without
     *  this, a run configured with both would silently fold their readings into one series. */
    private record SignalKey(String providerId, String signalId) {
    }

    /** Polls every reachable provider on a background thread until asked to stop. */
    private static final class SamplingSession implements Session {

        private final List<ObservabilityProvider> providers;
        private final String endpoint;
        private final List<StageWindows.StageWindow> stages;
        private final ObservabilityProvider.RunCorrelation correlation;
        private final ResourceSampleSink sink;
        private final Duration watchdog;
        private final Instant startedAt = Instant.now();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Map<SignalKey, Readings> runReadings = new LinkedHashMap<>();
        private final Map<Integer, Map<SignalKey, Readings>> stageReadings = new LinkedHashMap<>();

        /** What each signal is, as its provider classified it. Guarded by {@code runReadings}. Capped
         *  at {@link #MAX_RETAINED_SIGNALS}; a signal beyond the cap keeps its plain observation in
         *  {@code runReadings} but is never classified or streamed to {@code sink}. */
        private final Map<SignalKey, ResourceSignal> classifications = new LinkedHashMap<>();
        private final AtomicInteger retentionOverflow = new AtomicInteger();
        private final Set<TelemetryGap> gaps = new LinkedHashSet<>();
        private final Thread sampler;

        /** How long {@link #finish} waits for the sampler to notice it was interrupted. */
        private static final Duration FINISH_JOIN_TIMEOUT = Duration.ofMillis(500);

        private final AtomicBoolean finished = new AtomicBoolean(false);
        private volatile Telemetry cachedTelemetry;

        SamplingSession(List<ObservabilityProvider> providers, String endpoint,
                List<StageWindows.StageWindow> stages,
                ObservabilityProvider.RunCorrelation correlation, List<TelemetryGap> startupGaps,
                ResourceSampleSink sink, Duration watchdog) {
            this.providers = providers;
            this.endpoint = endpoint;
            this.stages = stages;
            this.correlation = correlation;
            this.gaps.addAll(startupGaps);
            this.sink = sink;
            this.watchdog = watchdog;
            this.sampler = Thread.ofVirtual().name("vortex-telemetry").start(this::sampleUntilStopped);
        }

        private void sampleUntilStopped() {
            Instant deadline = startedAt.plus(watchdog);
            while (running.get() && Instant.now().isBefore(deadline)) {
                sampleOnce();
                try {
                    Thread.sleep(SAMPLE_INTERVAL);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (running.get()) {
                // The loop can only fall through here while still "running" if the deadline, not
                // finish(), ended it — finish() always sets running=false first. This is the one case
                // ExecutionService's own guaranteed cleanup cannot reach: whatever is blocking
                // engine.execute() is still blocking it, so that method's own finally has not run
                // either. Closing the session here is what keeps it from sampling indefinitely, and
                // records an honest reason rather than leaving a reader to guess why the series ends
                // where it does.
                finishInternal(new TimeWindow(startedAt, Instant.now()),
                        "sampling exceeded its safety ceiling of " + watchdog);
            }
        }

        private void sampleOnce() {
            Instant now = Instant.now();
            var stage = StageWindows.at(stages, now).orElse(null);
            var scoped = stage == null
                    ? correlation
                    : correlation.forStage(stage.target().displayWithUnit());
            Integer stageIndex = stage == null ? null : stage.index();

            for (ObservabilityProvider provider : providers) {
                try {
                    var query = new ObservabilityProvider.ObservabilityQuery(endpoint,
                            new TimeWindow(now, now), provider.defaultMetrics(), scoped);

                    var collected = provider.collect(query);
                    synchronized (runReadings) {
                        for (MetricObservation observation : collected.observations()) {
                            SignalKey key = new SignalKey(provider.id(), observation.id());
                            runReadings
                                    .computeIfAbsent(key, _ -> new Readings(observation))
                                    .record(observation, now);
                            if (stage != null) {
                                stageReadings
                                        .computeIfAbsent(stage.index(), _ -> new LinkedHashMap<>())
                                        .computeIfAbsent(key, _ -> new Readings(observation))
                                        .record(observation, now);
                            }
                        }
                        // What the provider said each signal *is*, kept once per (provider, id). The
                        // values change every five seconds; the classification does not, so
                        // re-recording it each sample would be noise. The aggregated peak is
                        // re-wrapped with it in finish(), which is what carries the kind, scope and
                        // limit through to a conclusion — without it, every signal would arrive typed
                        // and leave untyped.
                        for (ResourceSignal classified : collected.resourceSignals()) {
                            SignalKey key = new SignalKey(provider.id(), classified.signalId());
                            if (!classifications.containsKey(key)) {
                                if (classifications.size() >= ObservabilityTelemetryCollector.MAX_RETAINED_SIGNALS) {
                                    retentionOverflow.incrementAndGet();
                                    continue;
                                }
                                classifications.put(key, classified);
                            }
                            sink.accept(new ResourceSample(now, provider.id(), classified.signalId(),
                                    classified.kind(), classified.scope(), classified.value(),
                                    classified.observation().unit(), stageIndex));
                        }
                        gaps.addAll(collected.gaps());
                    }
                } catch (RuntimeException e) {
                    // A service that becomes unreachable mid-run is itself interesting, but it is
                    // not Vortex's failure. Whatever was sampled before that point is kept.
                    log.debug("A telemetry sample from {} failed: {}", provider.id(), e.getMessage());
                    synchronized (runReadings) {
                        gaps.add(new TelemetryGap(provider.id(), "",
                                TelemetryAvailability.UNREACHABLE,
                                e.getMessage() == null ? "" : e.getMessage()));
                    }
                }
            }
        }

        /**
         * Waits briefly for the sampler to actually stop after being interrupted, so a sample already
         * in flight when {@link #finish} was called has a chance to land in {@code runReadings} before
         * it is read — without this, whichever of the two happened to reach the {@code runReadings}
         * monitor first decided, non-deterministically, whether that last sample was included.
         *
         * <p>Bounded, not awaited indefinitely: {@code finish()} must never hang because the sampler
         * is blocked somewhere that does not respond to interruption promptly (a provider's own HTTP
         * client, for instance).
         */
        private void joinBriefly() {
            try {
                sampler.join(FINISH_JOIN_TIMEOUT.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public Telemetry finish(TimeWindow window) {
            return finishInternal(window, null);
        }

        /**
         * Does the real work of ending a session, exactly once — called either by {@link #finish}, on
         * behalf of whoever owns this session's lifecycle, or by the watchdog inside
         * {@link #sampleUntilStopped} when nothing else ever does. A second call, from either source,
         * returns the same {@link Telemetry} already computed rather than reading {@code runReadings}
         * again or closing {@code sink} a second time.
         */
        private Telemetry finishInternal(TimeWindow window, String earlyStopReason) {
            if (!finished.compareAndSet(false, true)) {
                return cachedTelemetry;
            }
            running.set(false);
            sampler.interrupt();
            joinBriefly();

            List<MetricObservation> run = new ArrayList<>();
            List<ResourceSignal> runResources = new ArrayList<>();
            List<StageTelemetry> byStage = new ArrayList<>();

            synchronized (runReadings) {
                for (Map.Entry<SignalKey, Readings> entry : runReadings.entrySet()) {
                    MetricObservation observation = entry.getValue().toObservation(window);
                    run.add(observation);
                    typed(entry.getKey()).ifPresent(runResources::add);
                }
                for (StageWindows.StageWindow stage : stages) {
                    Map<SignalKey, Readings> readings = stageReadings.get(stage.index());
                    if (readings == null || readings.isEmpty()) {
                        continue;
                    }
                    List<MetricObservation> signals = new ArrayList<>();
                    List<ResourceSignal> resources = new ArrayList<>();
                    for (Map.Entry<SignalKey, Readings> entry : readings.entrySet()) {
                        MetricObservation observation = entry.getValue().toObservation(stage.window());
                        signals.add(observation);
                        typed(entry.getKey()).ifPresent(resources::add);
                    }
                    byStage.add(new StageTelemetry(stage.index(), stage.window(), stage.basis(),
                            signals, resources));
                }

                int overflow = retentionOverflow.get();
                if (overflow > 0) {
                    gaps.add(new TelemetryGap("vortex", "resource-signal-cardinality",
                            TelemetryAvailability.UNSUPPORTED,
                            overflow + " additional distinct resource signal"
                                    + (overflow == 1 ? "" : "s") + " were observed beyond the "
                                    + "retention limit of " + ObservabilityTelemetryCollector.MAX_RETAINED_SIGNALS
                                    + " and were not classified or recorded in detail."));
                }

                log.info("Collected {} measurements from the service under test via {}, across {} "
                                + "stages, with {} gaps",
                        run.size(), providers.stream().map(ObservabilityProvider::id).toList(),
                        byStage.size(), gaps.size());

                sink.close(earlyStopReason);

                cachedTelemetry = new Telemetry(run, byStage, List.copyOf(gaps), runResources);
                return cachedTelemetry;
            }
        }

        /**
         * The aggregated reading re-wrapped with what its provider said it was.
         *
         * <p>Empty when no provider classified this signal, which leaves it an ordinary observation
         * — still collected, aligned, cited, exported and rendered, and unable to become a claim
         * about a limit.
         */
        private Optional<ResourceSignal> typed(SignalKey key) {
            return Optional.ofNullable(classifications.get(key))
                    .map(classified -> new ResourceSignal(classified.observation(), classified.kind(),
                            classified.scope(), classified.limit()));
        }
    }

    /**
     * The first, highest and most recent readings of one metric across a window.
     *
     * <p>The peak alone cannot distinguish "the pool was already saturated before the test started"
     * from "the test drove the pool to saturation", and those two call for entirely different
     * conclusions. Keeping three points costs nothing and is the difference between a number and
     * evidence about a workload — and computing them online here, independent of whether the raw
     * series (streamed separately to a {@link ResourceSampleSink}) can be persisted at all, is what
     * keeps this summary's memory cost constant regardless of how long a run lasts.
     */
    private static final class Readings {

        private final MetricObservation first;
        private MetricObservation peak;
        private MetricObservation last;
        private Instant peakAt;

        Readings(MetricObservation observation) {
            this.first = observation;
            this.peak = observation;
            this.last = observation;
        }

        void record(MetricObservation observation, Instant at) {
            last = observation;
            if (observation.value() > peak.value()) {
                peak = observation;
                peakAt = at;
            }
        }

        /**
         * The peak, restamped with the covering window and marked as a maximum, carrying the trace.
         *
         * <p>Restamping matters: a reader must know the figure is the worst point during that
         * interval rather than a reading taken at some unspecified moment.
         */
        MetricObservation toObservation(TimeWindow window) {
            return peak.over(window, Aggregation.MAX)
                    .withTrace(new ObservationTrace(
                            first.value(), peak.value(), last.value(), peakAt));
        }
    }

    /** Sampling metadata, exposed for the settings page and documentation. */
    public Map<String, String> describe() {
        return Map.of(
                "interval", SAMPLE_INTERVAL.toSeconds() + "s",
                "aggregation", "maximum over the run, and again over each stage",
                "providers", String.valueOf(providers.size()));
    }
}
