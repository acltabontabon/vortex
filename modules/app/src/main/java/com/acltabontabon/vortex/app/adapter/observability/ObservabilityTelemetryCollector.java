package com.acltabontabon.vortex.app.adapter.observability;

import com.acltabontabon.vortex.app.adapter.target.docker.DockerProcess;
import com.acltabontabon.vortex.core.metrics.Aggregation;
import com.acltabontabon.vortex.core.metrics.MetricObservation;
import com.acltabontabon.vortex.core.metrics.ObservationTrace;
import com.acltabontabon.vortex.core.metrics.StageTelemetry;
import com.acltabontabon.vortex.core.metrics.TelemetryAvailability;
import com.acltabontabon.vortex.core.metrics.TelemetryGap;
import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.port.ObservabilityProvider;
import com.acltabontabon.vortex.core.port.TelemetryCollector;
import com.acltabontabon.vortex.core.resource.ResolvedLoadGeneratorBudget;
import com.acltabontabon.vortex.core.resource.ResourceSample;
import com.acltabontabon.vortex.core.resource.ResourceSampleSink;
import com.acltabontabon.vortex.core.resource.ResourceSampleSinkFactory;
import com.acltabontabon.vortex.core.resource.ResourceSignal;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import com.acltabontabon.vortex.core.target.EffectiveResourceEnvelope;
import com.acltabontabon.vortex.core.target.ResolvedTarget;
import com.acltabontabon.vortex.core.target.TargetOwnership;
import com.acltabontabon.vortex.core.workload.StageWindowBasis;
import com.acltabontabon.vortex.core.workload.StageWindows;
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

    /** How long to wait, once, for docker stats' first real reading before sampling begins —
     *  generous against its measured 0.5–1s refresh interval, and paid only during setup. */
    private static final Duration DOCKER_STATS_WARM_UP = Duration.ofSeconds(2);

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

    /** Used only to build a {@link DockerContainerObservabilityProvider} on demand, one per run,
     *  when that run's target is Vortex-managed — see {@link #start}. Never touched for any other
     *  target type. */
    private final DockerProcess dockerProcess;
    private final String dockerExecutable;

    /** Whether the engine runs k6 in a Docker container ({@code DockerK6Runner}) rather than as a
     *  local binary — see {@link #start} for the container-scoped generator provider this enables. */
    private final boolean generatorRunsInDocker;

    public ObservabilityTelemetryCollector(List<ObservabilityProvider> providers,
            ObservabilityProvider generator, ResourceSampleSinkFactory sinkFactory,
            DockerProcess dockerProcess, String dockerExecutable) {
        this(providers, generator, sinkFactory, dockerProcess, dockerExecutable, false);
    }

    public ObservabilityTelemetryCollector(List<ObservabilityProvider> providers,
            ObservabilityProvider generator, ResourceSampleSinkFactory sinkFactory,
            DockerProcess dockerProcess, String dockerExecutable, boolean generatorRunsInDocker) {
        this.providers = List.copyOf(providers);
        this.generator = generator;
        this.sinkFactory = sinkFactory;
        this.dockerProcess = dockerProcess;
        this.dockerExecutable = dockerExecutable;
        this.generatorRunsInDocker = generatorRunsInDocker;
    }

    @Override
    public Session start(EffectiveTestPlan plan, ExecutionId executionId, ResolvedTarget resolvedTarget,
            ResolvedLoadGeneratorBudget resolvedLoadGeneratorBudget) {
        Instant now = Instant.now();
        // The plan's own pre-run target is genuinely blank for a Docker/Compose target — it has no
        // resolvable URL until Vortex starts the container itself. But by the time this method runs,
        // resolvedTarget carries the container's real, already-confirmed-reachable address (the same
        // one the readiness probe already succeeded against), so a Vortex-managed target uses that
        // instead of falling through to blank. Every other case keeps reading the plan's own target
        // exactly as before. The remaining Vortex-managed case this leaves unaddressed — a container
        // has no endpoint to key a provider by at all, even a resolved one — is handled below, once
        // the endpoint-keyed loop is done, by keying DockerContainerObservabilityProvider off the
        // container id directly instead.
        String endpoint = resolvedTarget != null
                && resolvedTarget.ownership() == TargetOwnership.VORTEX_MANAGED
                ? resolvedTarget.endpoint().value()
                : plan.effectiveTargetIfPresent().map(target -> target.value()).orElse("");

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

        // A Vortex-managed target's container is watched the same way: added directly, never through
        // the endpoint-keyed loop above, because a container id is not an HTTP endpoint and forcing
        // it into ObservabilityQuery.endpoint would be a category error rather than a simplification.
        // Built fresh here rather than injected as a long-lived bean, unlike every other provider in
        // this class: this one watches a specific container, and each run watches a different one.
        // The confirmed EffectiveResourceEnvelope travels with it unchanged from Step 8's own
        // docker-inspect confirmation, so a limit this provider reports is never re-derived or
        // re-guessed — it is the same number, carried forward.
        List<DockerContainerObservabilityProvider> dockerProviders = new ArrayList<>();
        if (resolvedTarget != null && resolvedTarget.ownership() == TargetOwnership.VORTEX_MANAGED) {
            var containerId = resolvedTarget.telemetryHandleIfPresent();
            if (containerId.isPresent()) {
                var dockerProvider = new DockerContainerObservabilityProvider(containerId.get(),
                        resolvedTarget.resourcesIfPresent().orElse(null), dockerProcess,
                        dockerExecutable);
                // This container is already running by construction (see this provider's own
                // Javadoc), so a brief, bounded wait here for docker stats' first real line is cheap
                // setup-time cost that never touches the run's own measured window — and it is what
                // keeps this run's very first sample from spuriously reporting NO_DATA merely because
                // the stream had not refreshed yet.
                dockerProvider.warmUp(DOCKER_STATS_WARM_UP);
                dockerProviders.add(dockerProvider);
                reachable.add(dockerProvider);
            }
        }

        // The load generator's own container, when the engine runs k6 in Docker rather than as a
        // local binary — same treatment, keyed by the deterministic name DockerK6Runner gives that
        // container from this same executionId, so nothing has to be threaded across the module
        // boundary to learn it. Retried on failure, unlike the system-under-test case above: that
        // container is already running by the time this method is called; this one is started later,
        // by the engine, and may not exist yet on the first sampling attempt.
        //
        // The envelope passed here is what this run *requested*, not (yet) a re-inspected
        // confirmation the way the system-under-test's is — the generator's container does not exist
        // yet when sampling starts. That is sound rather than a shortcut: DockerK6Runner applies and
        // confirms the same request before this run can ever reach a completed state, so a limit
        // reported here is never seen disagreeing with what actually took effect. See
        // ResolvedLoadGeneratorBudget's own Javadoc.
        if (generatorRunsInDocker && executionId != null) {
            EffectiveResourceEnvelope generatorResources = resolvedLoadGeneratorBudget == null
                    ? null
                    : new EffectiveResourceEnvelope(resolvedLoadGeneratorBudget.allocation().cpu(),
                            resolvedLoadGeneratorBudget.allocation().memory());
            var generatorContainerProvider = new DockerContainerObservabilityProvider(
                    "vortex-k6-" + executionId.value(), generatorResources, dockerProcess,
                    dockerExecutable, com.acltabontabon.vortex.core.resource.ResourceScope.LOAD_GENERATOR, true);
            dockerProviders.add(generatorContainerProvider);
            reachable.add(generatorContainerProvider);
        }

        if (reachable.isEmpty()) {
            return Session.refused(gaps);
        }

        return new SamplingSession(reachable, endpoint, plan.stages(), correlation, gaps,
                openSink(executionId), watchdogFor(plan), dockerProviders);
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

        /**
         * The planned stages, kept so the windows below can be re-anchored once traffic actually
         * starts — see {@link #trafficStarted()}.
         */
        private final List<com.acltabontabon.vortex.core.workload.Stage> plannedStages;

        /**
         * When each stage was in effect. Anchored at session start until traffic is confirmed, then
         * re-anchored to that instant: a session begins before the generator produces anything, so an
         * anchor taken here places every boundary earlier than it belongs by however long the engine
         * took to start — enough, on short stages, to attribute one stage's measurements to another.
         * Volatile because the sampler thread reads it while the run's own thread re-anchors it.
         */
        private volatile List<StageWindows.StageWindow> stages;
        private final ObservabilityProvider.RunCorrelation correlation;
        private final ResourceSampleSink sink;
        private final Duration watchdog;

        /** The Docker container providers this session owns — the system under test's, the load
         *  generator's, both, or neither, depending on this run's target and engine. Each is stopped
         *  exactly once, here, when the session ends, since {@link ObservabilityProvider} itself has
         *  no lifecycle hook and these are the only providers in this class that hold a real
         *  subprocess open across the whole run. */
        private final List<DockerContainerObservabilityProvider> dockerProviders;
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
        private final AtomicBoolean trafficConfirmed = new AtomicBoolean(false);
        private volatile Telemetry cachedTelemetry;

        SamplingSession(List<ObservabilityProvider> providers, String endpoint,
                List<com.acltabontabon.vortex.core.workload.Stage> plannedStages,
                ObservabilityProvider.RunCorrelation correlation, List<TelemetryGap> startupGaps,
                ResourceSampleSink sink, Duration watchdog,
                List<DockerContainerObservabilityProvider> dockerProviders) {
            this.providers = providers;
            this.endpoint = endpoint;
            this.plannedStages = plannedStages == null ? List.of() : List.copyOf(plannedStages);
            this.stages = StageWindows.fromPlan(this.plannedStages, Instant.now());
            this.correlation = correlation;
            this.gaps.addAll(startupGaps);
            this.sink = sink;
            this.watchdog = watchdog;
            this.dockerProviders = List.copyOf(dockerProviders);
            this.sampler = Thread.ofVirtual().name("vortex-telemetry").start(this::sampleUntilStopped);
        }

        /**
         * Re-bases this session on the moment traffic actually began.
         *
         * <p>Two things are corrected, both consequences of sampling deliberately starting earlier
         * than the workload. The stage windows are re-anchored, so a stage's measurements are the ones
         * taken while that stage was really running rather than while the engine was still starting.
         * And each signal's aggregate is restarted: the peak and the most recent reading now describe
         * the run, not the setup that preceded it.
         *
         * <p>Everything gathered before this point is discarded, including each signal's start
         * reading — see {@link Readings#restartOnNextSample()} for why that reading is not the
         * baseline it looks like.
         *
         * <p>Idempotent, because nothing guarantees the engine reports its first progress exactly
         * once, and re-basing twice would silently discard real measurements the second time.
         */
        @Override
        public void trafficStarted() {
            if (!trafficConfirmed.compareAndSet(false, true)) {
                return;
            }
            Instant at = Instant.now();
            stages = StageWindows.fromPlan(plannedStages, at);
            synchronized (runReadings) {
                runReadings.values().forEach(Readings::restartOnNextSample);
                stageReadings.clear();
            }
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
                        // re-recording it each sample would be noise. Only the kind, scope and limit
                        // are read back out of here — finish() re-wraps them around the aggregated
                        // peak, never around this sample's own stale value. Without the
                        // classification, every signal would arrive typed and leave untyped.
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

        /** Stops every Docker container stream this run had — best-effort, since a telemetry shutdown
         *  failure must never be the reason a run's own result is lost. */
        private void closeDockerProviderQuietly() {
            for (DockerContainerObservabilityProvider dockerProvider : dockerProviders) {
                try {
                    dockerProvider.close();
                } catch (RuntimeException e) {
                    log.debug("Could not stop container telemetry cleanly: {}", e.getMessage());
                }
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
            closeDockerProviderQuietly();

            List<MetricObservation> run = new ArrayList<>();
            List<ResourceSignal> runResources = new ArrayList<>();
            List<StageTelemetry> byStage = new ArrayList<>();

            synchronized (runReadings) {
                for (Map.Entry<SignalKey, Readings> entry : runReadings.entrySet()) {
                    MetricObservation observation = entry.getValue().toObservation(window);
                    run.add(observation);
                    typed(entry.getKey(), observation).ifPresent(runResources::add);
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
                        typed(entry.getKey(), observation).ifPresent(resources::add);
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
         * <p>The aggregate is passed in rather than taken from the stored classification: what was
         * stored is the sample that first identified this signal, and its <em>value</em> is whatever
         * the resource happened to be doing at that instant — typically before the run had generated
         * any load at all. Only the kind, scope and limit are stable enough to keep from that sample;
         * the number has to come from the window being summarised, or a resource tile reports the
         * service at rest while the timeline beside it shows the peak.
         *
         * <p>Empty when no provider classified this signal, which leaves it an ordinary observation
         * — still collected, aligned, cited, exported and rendered, and unable to become a claim
         * about a limit.
         */
        private Optional<ResourceSignal> typed(SignalKey key, MetricObservation aggregated) {
            return Optional.ofNullable(classifications.get(key))
                    .map(classified -> new ResourceSignal(aggregated, classified.kind(),
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

        private MetricObservation first;
        private MetricObservation peak;
        private MetricObservation last;
        private Instant peakAt;
        private boolean awaitingFirstOfRun;

        Readings(MetricObservation observation) {
            this.first = observation;
            this.peak = observation;
            this.last = observation;
        }

        void record(MetricObservation observation, Instant at) {
            last = observation;
            if (awaitingFirstOfRun) {
                awaitingFirstOfRun = false;
                first = observation;
                peak = observation;
                peakAt = at;
                return;
            }
            if (observation.value() > peak.value()) {
                peak = observation;
                peakAt = at;
            }
        }

        /**
         * Discards everything gathered before traffic existed, starting again at the next sample.
         *
         * <p>Deferred to the next sample rather than applied here, because "here" is the moment the
         * engine first reported progress and the newest reading at that moment is still a setup one —
         * seeding the peak with it would keep exactly the value this is meant to discard, and a run
         * whose later readings never exceed it would report its own start-up as its peak.
         *
         * <p>The start reading is discarded along with the peak. It is documented as the service at
         * rest, which is what makes it evidence — "the pool was already saturated before the test
         * started" is a real finding. A container that has just been declared ready is not at rest: it
         * is a JVM finishing its own start-up, reading 97% CPU on its own behalf. Keeping that as the
         * baseline would trade a wrong peak for an equally wrong claim that the service began the run
         * saturated.
         */
        void restartOnNextSample() {
            awaitingFirstOfRun = true;
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
