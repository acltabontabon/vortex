package dev.vortex.app.adapter.observability;

import dev.vortex.core.metrics.Aggregation;
import dev.vortex.core.metrics.MetricObservation;
import dev.vortex.core.metrics.MetricSource;
import dev.vortex.core.metrics.MetricUnit;
import dev.vortex.core.metrics.ObservationProvenance;
import dev.vortex.core.metrics.TelemetryAvailability;
import dev.vortex.core.metrics.TelemetryGap;
import dev.vortex.core.port.ObservabilityProvider;
import dev.vortex.core.resource.ResourceKind;
import dev.vortex.core.resource.ResourceLimit;
import dev.vortex.core.resource.ResourceScope;
import dev.vortex.core.resource.ResourceSignal;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Vortex observing itself.
 *
 * <p>Every other provider describes the service under test. This one describes the machine producing
 * the traffic, and it exists because without it Vortex cannot tell "the service could not go faster"
 * from "we could not ask faster" — the single most damaging failure mode in load testing, and the one
 * that turns a generator's ceiling into a quoted service capacity.
 *
 * <h2>Why this is an ordinary provider</h2>
 * It implements the same port as Prometheus and Dynatrace, and answers with
 * {@link ResourceScope#LOAD_GENERATOR}-scoped signals. {@code vortex-core} learns nothing new: it
 * already reasons over a kind and a scope, and a signal describing Vortex's host is told apart from
 * one describing the service by its scope alone. A separate port would have been a second way to say
 * the same thing.
 *
 * <h2>Reading the JDK does not make Vortex JVM-specific</h2>
 * The system measured here is the machine Vortex is running on, which is a JVM host by definition —
 * it is running Vortex. Resource telemetry for the <em>service</em> must stay language-neutral
 * (ADR-037); telemetry for the <em>generator</em> is Vortex looking in a mirror. So this takes no new
 * dependency: {@code com.sun.management.OperatingSystemMXBean} is container-aware on a modern JVM and
 * reports a cgroup quota rather than the host's, and {@link ProcessHandle} is the only JDK-native way
 * to see a child process that is not a JVM.
 *
 * <h2>Absence carries a cause, and silence is never health</h2>
 * Where the host bean is unavailable, or no child process is visible — a restricted PID namespace, a
 * containerised runner with no reachable metrics, a future distributed run under the k6 Operator —
 * this returns a {@link TelemetryGap} with a reason and no signal. {@code GENERATOR_SATURATED} then
 * simply does not fire, and its absence must never be read as proof the generator was healthy.
 */
public final class LoadGeneratorObservabilityProvider implements ObservabilityProvider {

    public static final String ID = "generator";

    private static final String CPU = "metric:generator.cpu.utilization";
    private static final String MEMORY = "metric:generator.memory.used";
    private static final String PROCESS_CPU = "metric:generator.process.cpu.utilization";

    private final com.sun.management.OperatingSystemMXBean os;

    /**
     * The previous CPU-time reading, so the next one can be turned into a rate.
     *
     * <p>{@code totalCpuDuration} is cumulative, and a cumulative counter is not a utilisation until
     * something says over how long. Guarded by {@code this}: two runs sharing one Vortex share one
     * host, and contending for it is a real property of that arrangement rather than an error.
     */
    private CpuTimeSample previous;

    public LoadGeneratorObservabilityProvider() {
        this(defaultBean());
    }

    LoadGeneratorObservabilityProvider(com.sun.management.OperatingSystemMXBean os) {
        this.os = os;
    }

    private static com.sun.management.OperatingSystemMXBean defaultBean() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean extended ? extended : null;
    }

    /** Two readings of a cumulative counter, and when each was taken. */
    private record CpuTimeSample(Duration totalCpuTime, Instant at) {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<String> defaultMetrics() {
        return List.of(CPU, MEMORY, PROCESS_CPU);
    }

    /**
     * Always true, and deliberately not a probe.
     *
     * <p>Every other provider asks whether some external system is reachable. This one measures the
     * process it is running inside, so the question has no meaningful negative answer — and treating
     * it as unavailable would silently drop generator observation from exactly the runs whose
     * telemetry is otherwise thinnest, which are the ones that need it most.
     */
    @Override
    public boolean isAvailable(ObservabilityQuery query) {
        return true;
    }

    @Override
    public Collected collect(ObservabilityQuery query) {
        List<MetricObservation> observations = new ArrayList<>();
        List<ResourceSignal> resources = new ArrayList<>();
        List<TelemetryGap> gaps = new ArrayList<>();

        if (os == null) {
            // A JVM whose management bean is not the extended one cannot report host load at all.
            // Rare, and reported rather than approximated from the load average, which measures
            // something else entirely.
            gaps.add(new TelemetryGap(ID, CPU, TelemetryAvailability.UNSUPPORTED,
                    "this JVM does not expose the extended operating-system metrics Vortex reads"));
            return new Collected(observations, gaps, resources);
        }

        hostCpu(query).ifPresentOrElse(
                signal -> add(observations, resources, signal),
                () -> gaps.add(new TelemetryGap(ID, CPU, TelemetryAvailability.NO_DATA,
                        "the operating system did not report a CPU load for this interval")));

        hostMemory(query).ifPresentOrElse(
                signal -> add(observations, resources, signal),
                () -> gaps.add(new TelemetryGap(ID, MEMORY, TelemetryAvailability.NO_DATA,
                        "the operating system did not report physical memory for this interval")));

        processCpu(query).ifPresentOrElse(
                signal -> add(observations, resources, signal),
                () -> gaps.add(new TelemetryGap(ID, PROCESS_CPU, TelemetryAvailability.NO_DATA,
                        "no load-generator process was visible to Vortex, or this was the first "
                                + "sample and there is nothing yet to measure a rate against")));

        return new Collected(observations, gaps, resources);
    }

    private static void add(List<MetricObservation> observations, List<ResourceSignal> resources,
            ResourceSignal signal) {
        // In both lists: the observation is what gets rendered and cited, the typed signal is what
        // a validity rule may rest on.
        observations.add(signal.observation());
        resources.add(signal);
    }

    /** Host CPU load as a ratio of one, which is what the bean reports and what its limit is. */
    private java.util.Optional<ResourceSignal> hostCpu(ObservabilityQuery query) {
        double load = os.getCpuLoad();
        if (!Double.isFinite(load) || load < 0) {
            // The bean returns a negative value when it has not yet accumulated enough samples.
            // That is "not measured", not "idle".
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ResourceSignal(
                observation(CPU, "Load generator CPU", MetricUnit.RATIO, load, query,
                        "com.sun.management.OperatingSystemMXBean#getCpuLoad"),
                ResourceKind.CPU, ResourceScope.LOAD_GENERATOR,
                ResourceLimit.inherentTo(MetricUnit.RATIO)));
    }

    /**
     * Memory in use on the generator, against the total it is allowed.
     *
     * <p>A published limit rather than an inherent one, and container-aware: a Vortex under a cgroup
     * quota reports the quota. That distinction matters here more than anywhere else, because the
     * machine generating load is very often the smallest one in the experiment.
     */
    private java.util.Optional<ResourceSignal> hostMemory(ObservabilityQuery query) {
        long total = os.getTotalMemorySize();
        long free = os.getFreeMemorySize();
        if (total <= 0 || free < 0 || free > total) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ResourceSignal(
                observation(MEMORY, "Load generator memory", MetricUnit.BYTES, total - free, query,
                        "com.sun.management.OperatingSystemMXBean#getFreeMemorySize"),
                ResourceKind.MEMORY, ResourceScope.LOAD_GENERATOR,
                ResourceLimit.published(total, MetricUnit.BYTES,
                        "the memory available to this machine")));
    }

    /**
     * What the spawned load generator is doing to this machine's CPU.
     *
     * <p>The engine runs as a child process rather than inside this JVM, so its cost is invisible to
     * every JVM-scoped measurement. Summing descendants rather than naming k6 keeps this true for any
     * engine Vortex ever spawns, and means nothing has to be plumbed through the runner.
     *
     * <p>Absent on the first sample by construction: a cumulative counter with nothing to difference
     * against is not yet a rate, and reporting the raw total as though it were a utilisation would be
     * a fabrication in the shape of a number.
     */
    private synchronized java.util.Optional<ResourceSignal> processCpu(ObservabilityQuery query) {
        Duration total = Duration.ZERO;
        boolean sawAny = false;

        for (ProcessHandle child : ProcessHandle.current().descendants().toList()) {
            var cpu = child.info().totalCpuDuration();
            if (cpu.isPresent()) {
                total = total.plus(cpu.get());
                sawAny = true;
            }
        }
        if (!sawAny) {
            previous = null;
            return java.util.Optional.empty();
        }

        Instant now = query.window().end();
        CpuTimeSample last = previous;
        previous = new CpuTimeSample(total, now);

        if (last == null || !now.isAfter(last.at())) {
            return java.util.Optional.empty();
        }

        double elapsedSeconds = Duration.between(last.at(), now).toNanos() / 1_000_000_000d;
        double cores = Runtime.getRuntime().availableProcessors();
        double used = total.minus(last.totalCpuTime()).toNanos() / 1_000_000_000d;
        double utilisation = used / (elapsedSeconds * cores);

        if (!Double.isFinite(utilisation) || utilisation < 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ResourceSignal(
                observation(PROCESS_CPU, "Load generator process CPU", MetricUnit.RATIO,
                        Math.min(utilisation, 1.0), query,
                        "ProcessHandle.Info#totalCpuDuration, differenced across samples"),
                ResourceKind.CPU, ResourceScope.LOAD_GENERATOR,
                ResourceLimit.inherentTo(MetricUnit.RATIO)));
    }

    private MetricObservation observation(String id, String name, MetricUnit unit, double value,
            ObservabilityQuery query, String how) {

        return new MetricObservation(id, name, MetricSource.DERIVED, unit, Aggregation.MAX, value,
                query.window(), Map.of(), ObservationProvenance.of(ID, how), null);
    }
}
