package com.acltabontabon.vortex.app.adapter.observability;

import com.acltabontabon.vortex.core.metrics.Aggregation;
import com.acltabontabon.vortex.core.metrics.MetricObservation;
import com.acltabontabon.vortex.core.metrics.MetricSource;
import com.acltabontabon.vortex.core.metrics.MetricUnit;
import com.acltabontabon.vortex.core.metrics.ObservationProvenance;
import com.acltabontabon.vortex.core.metrics.TelemetryAvailability;
import com.acltabontabon.vortex.core.metrics.TelemetryGap;
import com.acltabontabon.vortex.core.port.ObservabilityProvider;
import com.acltabontabon.vortex.core.resource.ResourceKind;
import com.acltabontabon.vortex.core.resource.ResourceLimit;
import com.acltabontabon.vortex.core.resource.ResourceScope;
import com.acltabontabon.vortex.core.resource.ResourceSignal;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Vortex observing itself.
 *
 * <p>Every other provider describes the service under test. This one describes the machine producing
 * the traffic, and it exists because without it Vortex cannot tell "the service could not go faster"
 * from "we could not ask faster" — the single most damaging failure mode in load testing, and the one
 * that turns a generator's ceiling into a quoted service capacity.
 *
 * <h2>Two scopes, not one</h2>
 * A signal here is never simply "the generator" — it is either {@link ResourceScope#LOAD_GENERATOR},
 * the generator's own process or container (the narrowest measurement Vortex could isolate, and the
 * only one {@code GENERATOR_SATURATED} may rest on), or {@link ResourceScope#LOAD_GENERATOR_HOST},
 * the whole machine running it — broader, always available, and never proof by itself that the
 * generator was constrained, since anything else sharing that machine could be the actual cause.
 * Folding the two together previously meant ordinary host memory or CPU pressure, caused by anything
 * at all sharing the machine, looked identical to the generator hitting its own limit.
 *
 * <h2>Why this is an ordinary provider</h2>
 * It implements the same port as Prometheus and Dynatrace, and answers with scoped signals.
 * {@code vortex-core} learns nothing new: it already reasons over a kind and a scope, and a signal
 * describing Vortex's host is told apart from one describing the service by its scope alone. A
 * separate port would have been a second way to say the same thing.
 *
 * <h2>Reading the JDK does not make Vortex JVM-specific</h2>
 * The host-scoped signals come from {@code com.sun.management.OperatingSystemMXBean}, which is
 * container-aware on a modern JVM and reports a cgroup quota rather than the host's. Process-scoped
 * CPU sums {@link ProcessHandle} descendants; process-scoped memory does the same, differenced
 * against {@code ps -o rss=} rather than a JDK API, because {@link ProcessHandle.Info} exposes CPU
 * time but no memory accessor at all.
 *
 * <h2>The generator may run in a container, and descendants then lie</h2>
 * When k6 runs inside a Docker container ({@code DockerK6Runner}), {@link
 * ProcessHandle#descendants()} only reaches the {@code docker} CLI client process on the host — not
 * k6 itself, running in a different PID namespace. Summing that descendant's memory would be a
 * smaller, still-wrong version of the same mislabeling this class exists to prevent, so
 * process-scoped memory is gapped rather than reported in that mode; the generator's actual container
 * memory is instead measured by a separate, per-run {@code DockerContainerObservabilityProvider}
 * scoped to {@link ResourceScope#LOAD_GENERATOR} (see {@code ObservabilityTelemetryCollector}).
 *
 * <h2>Absence carries a cause, and silence is never health</h2>
 * Where the host bean is unavailable, or no child process is visible — a restricted PID namespace, a
 * containerised runner with no reachable metrics, a future distributed run under the k6 Operator —
 * this returns a {@link TelemetryGap} with a reason and no signal. {@code GENERATOR_SATURATED} then
 * simply does not fire, and its absence must never be read as proof the generator was healthy.
 */
public final class LoadGeneratorObservabilityProvider implements ObservabilityProvider {

    public static final String ID = "generator";

    private static final String HOST_CPU = "metric:generator.host.cpu.utilization";
    private static final String HOST_MEMORY = "metric:generator.host.memory.used";
    private static final String PROCESS_CPU = "metric:generator.process.cpu.utilization";
    private static final String PROCESS_MEMORY = "metric:generator.process.memory.used";

    private final com.sun.management.OperatingSystemMXBean os;

    /**
     * Whether the load generator runs directly as a child process of this JVM (the local-binary
     * engine) rather than inside a Docker container.
     *
     * <p>{@code false} when {@code DockerK6Runner} is configured — see the class Javadoc on why
     * process-scoped memory cannot be trusted through {@link ProcessHandle} in that mode.
     */
    private final boolean generatorRunsAsLocalProcess;

    private final ProcessMemoryReader processMemoryReader;
    private final AvailableMemoryReader availableMemoryReader;

    /**
     * The previous CPU-time reading, so the next one can be turned into a rate.
     *
     * <p>{@code totalCpuDuration} is cumulative, and a cumulative counter is not a utilisation until
     * something says over how long. Guarded by {@code this}: two runs sharing one Vortex share one
     * host, and contending for it is a real property of that arrangement rather than an error.
     */
    private CpuTimeSample previous;

    public LoadGeneratorObservabilityProvider() {
        this(defaultBean(), true);
    }

    public LoadGeneratorObservabilityProvider(boolean generatorRunsAsLocalProcess) {
        this(defaultBean(), generatorRunsAsLocalProcess);
    }

    LoadGeneratorObservabilityProvider(com.sun.management.OperatingSystemMXBean os) {
        this(os, true);
    }

    LoadGeneratorObservabilityProvider(com.sun.management.OperatingSystemMXBean os,
            boolean generatorRunsAsLocalProcess) {
        this(os, generatorRunsAsLocalProcess, defaultProcessMemoryReader(), defaultAvailableMemoryReader());
    }

    LoadGeneratorObservabilityProvider(com.sun.management.OperatingSystemMXBean os,
            boolean generatorRunsAsLocalProcess, ProcessMemoryReader processMemoryReader) {
        this(os, generatorRunsAsLocalProcess, processMemoryReader, defaultAvailableMemoryReader());
    }

    LoadGeneratorObservabilityProvider(com.sun.management.OperatingSystemMXBean os,
            boolean generatorRunsAsLocalProcess, ProcessMemoryReader processMemoryReader,
            AvailableMemoryReader availableMemoryReader) {
        this.os = os;
        this.generatorRunsAsLocalProcess = generatorRunsAsLocalProcess;
        this.processMemoryReader = processMemoryReader;
        this.availableMemoryReader = availableMemoryReader;
    }

    private static com.sun.management.OperatingSystemMXBean defaultBean() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean extended ? extended : null;
    }

    /** Reads resident memory for one process, so real sampling can shell out to {@code ps} while a
     *  test supplies canned values without spawning anything. */
    @FunctionalInterface
    interface ProcessMemoryReader {
        /** Resident memory in bytes for {@code pid}, or empty if it could not be read — the process
         *  already exited, {@code ps} is unavailable, or its output did not parse. */
        java.util.Optional<Long> residentBytes(long pid);
    }

    private static ProcessMemoryReader defaultProcessMemoryReader() {
        return pid -> {
            try {
                Process ps = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid))
                        .redirectErrorStream(true)
                        .start();
                String line;
                try (var reader = ps.inputReader()) {
                    line = reader.readLine();
                }
                boolean finished = ps.waitFor(2, TimeUnit.SECONDS);
                if (!finished) {
                    ps.destroyForcibly();
                    return java.util.Optional.empty();
                }
                if (line == null || line.isBlank()) {
                    return java.util.Optional.empty();
                }
                long kilobytes = Long.parseLong(line.trim());
                return java.util.Optional.of(kilobytes * 1024);
            } catch (IOException e) {
                return java.util.Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return java.util.Optional.empty();
            } catch (NumberFormatException e) {
                return java.util.Optional.empty();
            }
        };
    }

    /**
     * Reads how much host memory an OS considers reclaimable — page cache, purgeable and inactive
     * pages — none of which {@link com.sun.management.OperatingSystemMXBean#getFreeMemorySize()}
     * counts as free, because it reports only unused pages, not pages an OS would hand back under
     * pressure without swapping. On a machine that dedicates spare RAM to disk cache, as both macOS
     * and Linux do by design, raw "free" sits near zero under completely ordinary conditions, and
     * {@code GENERATOR_HOST_UNDER_PRESSURE} would fire on nearly every run rather than only the ones
     * where the host genuinely could not spare more memory.
     */
    @FunctionalInterface
    interface AvailableMemoryReader {
        /** Reclaimable host memory in bytes, or empty if this OS is not one Vortex knows how to read
         *  this way — {@link #hostMemory} then falls back to the MXBean's raw free figure. */
        java.util.Optional<Long> availableBytes();
    }

    private static AvailableMemoryReader defaultAvailableMemoryReader() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("mac")) {
            return LoadGeneratorObservabilityProvider::readMacAvailableBytes;
        }
        if (osName.contains("linux")) {
            return LoadGeneratorObservabilityProvider::readLinuxAvailableBytes;
        }
        return java.util.Optional::empty;
    }

    /** {@code vm_stat}'s free, inactive, speculative and purgeable pages, converted with its own
     *  reported page size — the same categories Activity Monitor counts as not actually in use. */
    private static java.util.Optional<Long> readMacAvailableBytes() {
        try {
            Process vmStat = new ProcessBuilder("vm_stat").redirectErrorStream(true).start();
            List<String> lines;
            try (var reader = vmStat.inputReader()) {
                lines = reader.lines().toList();
            }
            boolean finished = vmStat.waitFor(2, TimeUnit.SECONDS);
            if (!finished) {
                vmStat.destroyForcibly();
                return java.util.Optional.empty();
            }
            if (lines.isEmpty()) {
                return java.util.Optional.empty();
            }

            long pageSize = vmStatPageSize(lines.getFirst());
            long free = vmStatPageCount(lines, "Pages free:");
            long inactive = vmStatPageCount(lines, "Pages inactive:");
            if (pageSize <= 0 || free < 0 || inactive < 0) {
                return java.util.Optional.empty();
            }
            long speculative = Math.max(vmStatPageCount(lines, "Pages speculative:"), 0);
            long purgeable = Math.max(vmStatPageCount(lines, "Pages purgeable:"), 0);

            return java.util.Optional.of((free + inactive + speculative + purgeable) * pageSize);
        } catch (IOException e) {
            return java.util.Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return java.util.Optional.empty();
        }
    }

    private static long vmStatPageSize(String headerLine) {
        // "Mach Virtual Memory Statistics: (page size of 16384 bytes)"
        var matcher = java.util.regex.Pattern.compile("page size of (\\d+) bytes").matcher(headerLine);
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long vmStatPageCount(List<String> lines, String label) {
        for (String line : lines) {
            if (!line.startsWith(label)) {
                continue;
            }
            String digits = line.substring(label.length()).trim();
            if (digits.endsWith(".")) {
                digits = digits.substring(0, digits.length() - 1);
            }
            try {
                return Long.parseLong(digits);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /** {@code /proc/meminfo}'s {@code MemAvailable} — the kernel's own estimate of reclaimable
     *  memory, already accounting for page cache and slab it could evict without swapping. */
    private static java.util.Optional<Long> readLinuxAvailableBytes() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (!line.startsWith("MemAvailable:")) {
                    continue;
                }
                String[] parts = line.substring("MemAvailable:".length()).trim().split("\\s+");
                long kilobytes = Long.parseLong(parts[0]);
                return java.util.Optional.of(kilobytes * 1024);
            }
            return java.util.Optional.empty();
        } catch (IOException | NumberFormatException e) {
            return java.util.Optional.empty();
        }
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
        return List.of(HOST_CPU, HOST_MEMORY, PROCESS_CPU, PROCESS_MEMORY);
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
            gaps.add(new TelemetryGap(ID, HOST_CPU, TelemetryAvailability.UNSUPPORTED,
                    "this JVM does not expose the extended operating-system metrics Vortex reads"));
            return new Collected(observations, gaps, resources);
        }

        hostCpu(query).ifPresentOrElse(
                signal -> add(observations, resources, signal),
                () -> gaps.add(new TelemetryGap(ID, HOST_CPU, TelemetryAvailability.NO_DATA,
                        "the operating system did not report a CPU load for this interval")));

        hostMemory(query).ifPresentOrElse(
                signal -> add(observations, resources, signal),
                () -> gaps.add(new TelemetryGap(ID, HOST_MEMORY, TelemetryAvailability.NO_DATA,
                        "the operating system did not report physical memory for this interval")));

        processCpu(query).ifPresentOrElse(
                signal -> add(observations, resources, signal),
                () -> gaps.add(processCpuGap()));

        if (generatorRunsAsLocalProcess) {
            processMemory(query).ifPresentOrElse(
                    signal -> add(observations, resources, signal),
                    () -> gaps.add(new TelemetryGap(ID, PROCESS_MEMORY, TelemetryAvailability.NO_DATA,
                            "no load-generator process was visible to Vortex")));
        } else {
            gaps.add(new TelemetryGap(ID, PROCESS_MEMORY, TelemetryAvailability.UNSUPPORTED,
                    "the load generator runs in a container; its process memory is not visible "
                            + "through this machine's process table, and is reported instead by the "
                            + "container-scoped provider for that container"));
        }

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
                observation(HOST_CPU, "Load generator host CPU", MetricUnit.RATIO, load, query,
                        "com.sun.management.OperatingSystemMXBean#getCpuLoad"),
                ResourceKind.CPU, ResourceScope.LOAD_GENERATOR_HOST,
                ResourceLimit.inherentTo(MetricUnit.RATIO)));
    }

    /**
     * Memory in use on the machine running the generator, against the total it is allowed.
     *
     * <p>A published limit rather than an inherent one, and container-aware: a Vortex under a cgroup
     * quota reports the quota. Scoped to the host, not the generator's own process or container —
     * anything else sharing that machine contributes to this number too, which is exactly why it
     * cannot alone establish that the generator was constrained; see {@link
     * ResourceScope#LOAD_GENERATOR_HOST}.
     *
     * <p>"Used" is computed against {@link AvailableMemoryReader}, not the MXBean's raw free figure,
     * where this OS is one Vortex can read that way — see the interface Javadoc for why raw free
     * alone would call an ordinarily fully-cached machine "under pressure" on nearly every run.
     */
    private java.util.Optional<ResourceSignal> hostMemory(ObservabilityQuery query) {
        long total = os.getTotalMemorySize();
        long free = os.getFreeMemorySize();
        if (total <= 0 || free < 0 || free > total) {
            return java.util.Optional.empty();
        }

        java.util.Optional<Long> reclaimable = availableMemoryReader.availableBytes()
                .filter(bytes -> bytes >= 0 && bytes <= total);
        long available = reclaimable.orElse(free);
        long used = total - available;

        String how = reclaimable.isPresent()
                ? "OperatingSystemMXBean#getTotalMemorySize, minus this OS's own reclaimable-memory "
                        + "estimate (page cache and purgeable/inactive pages counted as available, not "
                        + "used)"
                : "OperatingSystemMXBean#getTotalMemorySize/getFreeMemorySize (raw free — this OS "
                        + "exposes no reclaimable-memory estimate Vortex knows how to read)";

        return java.util.Optional.of(new ResourceSignal(
                observation(HOST_MEMORY, "Load generator host memory", MetricUnit.BYTES, used, query, how),
                ResourceKind.MEMORY, ResourceScope.LOAD_GENERATOR_HOST,
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

    /**
     * Why {@link #processCpu} came back empty — distinguishing a temporary "nothing to look at yet"
     * from a permanent "this JDK cannot answer that question here".
     *
     * <p>{@code ProcessHandle.Info#totalCpuDuration()} is a JDK-documented "if supported" API — on
     * some platforms (macOS notably) it returns empty for every child process, always, no matter how
     * many samples are taken. That is indistinguishable from "no process was there yet" by
     * {@link #processCpu}'s own return value alone, so this re-walks the descendants once more,
     * specifically to tell the two apart: if a descendant process exists at all but none of them
     * exposed a CPU time, the platform does not support this measurement here — a fact about this
     * machine, not about this run — and it is classified {@code UNSUPPORTED} rather than the
     * {@code NO_DATA} used for "nothing found yet", which quietly degraded every run on such a
     * platform forever, the same way an unfilterable {@code UNSUPPORTED} gap did before that was
     * recognised as its own case.
     */
    private TelemetryGap processCpuGap() {
        List<ProcessHandle> descendants = ProcessHandle.current().descendants().toList();
        if (!descendants.isEmpty()
                && descendants.stream().noneMatch(child -> child.info().totalCpuDuration().isPresent())) {
            return new TelemetryGap(ID, PROCESS_CPU, TelemetryAvailability.UNSUPPORTED,
                    "this machine's JDK does not expose per-process CPU time for the load "
                            + "generator's process (a known platform limitation, e.g. on macOS) — "
                            + "the generator's host-wide CPU is reported separately instead");
        }
        return new TelemetryGap(ID, PROCESS_CPU, TelemetryAvailability.NO_DATA,
                "no load-generator process was visible to Vortex, or this was the first sample and "
                        + "there is nothing yet to measure a rate against");
    }

    /**
     * Resident memory of the spawned load generator, summed across every descendant process of this
     * JVM.
     *
     * <p>Only called when {@link #generatorRunsAsLocalProcess} — see the class Javadoc for why a
     * containerised generator's descendants are the {@code docker} CLI client, not k6, and would make
     * this actively misleading rather than merely absent.
     *
     * <p>The limit is the same host/cgroup total {@link #hostMemory} reads, but described as a shared
     * ceiling rather than a dedicated one: the generator process does not have its own memory budget
     * on a local run, it shares whatever the machine has.
     */
    private java.util.Optional<ResourceSignal> processMemory(ObservabilityQuery query) {
        long total = 0;
        boolean sawAny = false;

        for (ProcessHandle child : ProcessHandle.current().descendants().toList()) {
            var rss = processMemoryReader.residentBytes(child.pid());
            if (rss.isPresent()) {
                total += rss.get();
                sawAny = true;
            }
        }
        if (!sawAny) {
            return java.util.Optional.empty();
        }

        long hostTotal = os.getTotalMemorySize();
        ResourceLimit limit = hostTotal > 0
                ? ResourceLimit.published(hostTotal, MetricUnit.BYTES,
                        "the memory available on the machine the generator process shares")
                : null;

        return java.util.Optional.of(new ResourceSignal(
                observation(PROCESS_MEMORY, "Load generator process memory", MetricUnit.BYTES, total,
                        query, "ps -o rss=, summed across the load generator's descendant processes"),
                ResourceKind.MEMORY, ResourceScope.LOAD_GENERATOR, limit));
    }

    private MetricObservation observation(String id, String name, MetricUnit unit, double value,
            ObservabilityQuery query, String how) {

        return new MetricObservation(id, name, MetricSource.DERIVED, unit, Aggregation.MAX, value,
                query.window(), Map.of(), ObservationProvenance.of(ID, how), null);
    }
}
