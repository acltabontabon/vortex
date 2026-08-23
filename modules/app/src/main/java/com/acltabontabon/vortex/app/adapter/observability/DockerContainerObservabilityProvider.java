package com.acltabontabon.vortex.app.adapter.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.acltabontabon.vortex.app.adapter.target.docker.DockerProcess;
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
import com.acltabontabon.vortex.core.target.EffectiveResourceEnvelope;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Container-scoped CPU/memory for a Docker container Vortex itself started — the system under test
 * when the target is Vortex-managed, or the load generator when k6 runs in a container. Keyed by
 * container id rather than by endpoint URL, which is why this provider is constructed fresh per run
 * and handed directly to {@link ObservabilityTelemetryCollector}'s reachable-provider list rather than
 * being probed through the endpoint-keyed {@link ObservabilityQuery} loop every other provider goes
 * through (see that class's treatment of {@code generator}, which this mirrors for the same reason: a
 * container id is not an HTTP endpoint, and forcing it into one would be a category error, not a
 * simplification).
 *
 * <h2>Investigation: how this reads {@code docker stats} (plan §9)</h2>
 * Measured in this sandbox against a real {@code hashicorp/http-echo} container under a background
 * load of tens of thousands of local HTTP requests, two mechanisms were actually run and timed, not
 * assumed. Repeated {@code docker stats --no-stream <id>} invocations (spawning a fresh process every
 * sample) each took a consistent ~1.6 seconds of wall-clock time — roughly a third of this
 * collector's own 5-second sample interval spent purely on process-spawn-and-query overhead, on
 * every single sample, for the life of the run. That is exactly the "repeated high-frequency process
 * spawn... material overhead" the plan's acceptance criterion asks to avoid, so it was rejected. A
 * single long-lived {@code docker stats <id>} stream (no {@code --no-stream}), by contrast, spawns
 * exactly one process for the whole run — confirmed via the process table across a 30-second window —
 * refreshes on the order of every half-second to one second (far more often than the 5-second
 * interval this collector actually samples at, so a collection simply reads whatever the freshest
 * parsed reading is rather than ever waiting on one), stopped cleanly with nothing left running once
 * destroyed, and held a flat, small resident-memory footprint (tens of megabytes, not growing) for
 * the whole window. The one real wrinkle: even requesting {@code --format '{{json .}}'} with stdout
 * redirected to a plain file — not a terminal — the Docker CLI still interleaves ANSI cursor-control
 * escape sequences between JSON lines (a real property of this Docker CLI version's redraw loop, not
 * an artifact of how it was invoked), so {@link #parse(String)} strips escape codes before attempting
 * a JSON decode, and simply skips a line that is not one. A second, related wrinkle surfaced only by
 * actually wiring this end to end against a real container (not by the bounded investigation alone):
 * each redraw cycle ends with a control-code-only line — a trailing clear-to-end-of-line/screen — so
 * naively tracking "the most recent raw line" leaves a sampler looking at that trailing junk for
 * nearly the whole interval between refreshes, catching a real reading only in the sub-millisecond
 * window before the next cycle's junk line overwrites it. {@link #latestReading} is updated only when
 * a line actually parses, which is what keeps a slow 5-second sampler reliably seeing the last real
 * reading rather than intermittently reporting {@code NO_DATA} despite the stream working correctly.
 * Because the long-lived stream clears every element of the bounded acceptance criterion — no
 * repeated spawn, no leak, clean shutdown, bounded memory, readings comfortably inside the sample
 * interval, and negligible overhead since {@code docker stats} reads the daemon's own cgroup
 * accounting rather than touching the target container's process at all — this class uses it, and the
 * Docker Engine API was never evaluated, per the plan's explicit stop rule once option (B) cleared the
 * bar.
 */
public final class DockerContainerObservabilityProvider implements ObservabilityProvider, AutoCloseable {

    private static final Logger log =
            LoggerFactory.getLogger(DockerContainerObservabilityProvider.class);

    public static final String ID = "docker";

    private static final String CPU = "metric:docker.cpu.utilization";
    private static final String MEMORY = "metric:docker.memory.used";

    private static final Duration AVAILABILITY_CHECK_TIMEOUT = Duration.ofSeconds(10);

    private static final Pattern ANSI_ESCAPE = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");
    private static final Pattern MEMORY_TOKEN = Pattern.compile("^([0-9]*\\.?[0-9]+)\\s*([A-Za-z]+)$");

    /** {@code docker stats}' own units for {@code MemUsage} — binary (IEC), not decimal; see the
     *  class-level investigation note. Anything else is treated as unparseable rather than guessed
     *  at, matching this codebase's rule against inventing a number it did not actually read. */
    private static final Map<String, Long> BINARY_UNIT_SCALE = Map.of(
            "B", 1L,
            "KiB", 1_024L,
            "MiB", 1_024L * 1_024,
            "GiB", 1_024L * 1_024 * 1_024,
            "TiB", 1_024L * 1_024 * 1_024 * 1_024,
            "PiB", 1_024L * 1_024 * 1_024 * 1_024 * 1_024);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String containerId;
    private final EffectiveResourceEnvelope resources;
    private final DockerProcess dockerProcess;
    private final String dockerExecutable;
    private final ResourceScope scope;

    /**
     * Whether a failed stream start should be retried on the next {@link #collect}, instead of
     * sticking permanently.
     *
     * <p>{@code false} for the system-under-test case this class originally handled, where the
     * container is already running by the time telemetry starts — a stream failure there is a real,
     * permanent problem. {@code true} for a load-generator container: it is started later, by the
     * engine, possibly after this provider's session has already begun sampling, so the first
     * attempt may simply be too early rather than wrong.
     */
    private final boolean retryStreamStartup;

    private final Object streamLock = new Object();

    /**
     * The most recent reading that actually parsed, not the most recent raw line — deliberately.
     * Every refresh cycle of {@code docker stats}' redraw protocol ends with a control-code-only
     * line (a trailing clear-to-end-of-line/screen), which becomes the "current" raw line for most
     * of the interval between refreshes; a sampler that trusted the latest raw line would see a real
     * reading only in the sub-millisecond window before that trailing junk line overwrites it, and
     * would report {@code NO_DATA} almost every time despite the stream working perfectly. Updated
     * only when a line actually parses, so a junk line simply leaves the last known-good reading in
     * place instead of clobbering it.
     */
    private final AtomicReference<Reading> latestReading = new AtomicReference<>();
    private volatile DockerProcess.StreamHandle handle;
    private volatile String streamStartupFailure;

    /** The system-under-test case: scoped to {@link ResourceScope#SYSTEM_UNDER_TEST}, and a failed
     *  stream start is permanent — the container this watches is already running by construction. */
    public DockerContainerObservabilityProvider(String containerId, EffectiveResourceEnvelope resources,
            DockerProcess dockerProcess, String dockerExecutable) {
        this(containerId, resources, dockerProcess, dockerExecutable, ResourceScope.SYSTEM_UNDER_TEST,
                false);
    }

    public DockerContainerObservabilityProvider(String containerId, EffectiveResourceEnvelope resources,
            DockerProcess dockerProcess, String dockerExecutable, ResourceScope scope,
            boolean retryStreamStartup) {
        this.containerId = Objects.requireNonNull(containerId, "containerId");
        this.resources = resources;
        this.dockerProcess = Objects.requireNonNull(dockerProcess, "dockerProcess");
        this.dockerExecutable = dockerExecutable == null || dockerExecutable.isBlank()
                ? "docker" : dockerExecutable.trim();
        this.scope = Objects.requireNonNull(scope, "scope");
        this.retryStreamStartup = retryStreamStartup;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<String> defaultMetrics() {
        return List.of("cpu", "memory");
    }

    /**
     * A real check, but one this provider's only caller never actually calls: {@link
     * ObservabilityTelemetryCollector} constructs this class only once a target executor has already
     * created and started the container it names, and adds it straight to the reachable set — the
     * same treatment the load-generator provider gets, and for the same reason (see the class
     * Javadoc). Implemented for real anyway, since it is part of the interface contract.
     */
    @Override
    public boolean isAvailable(ObservabilityQuery query) {
        try {
            DockerProcess.DockerCommandResult result = dockerProcess.run(
                    List.of(dockerExecutable, "inspect", "--format", "{{.State.Running}}", containerId),
                    AVAILABILITY_CHECK_TIMEOUT);
            return result.succeeded() && "true".equals(result.firstStdoutLine().trim());
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Collected collect(ObservabilityQuery query) {
        ensureStreamStarted();

        String failure = streamStartupFailure;
        if (failure != null) {
            return new Collected(List.of(), List.of(
                    new TelemetryGap(ID, CPU, TelemetryAvailability.UNREACHABLE, failure),
                    new TelemetryGap(ID, MEMORY, TelemetryAvailability.UNREACHABLE, failure)),
                    List.of());
        }

        Optional<Reading> reading = Optional.ofNullable(latestReading.get());
        if (reading.isEmpty()) {
            String why = "no docker stats reading has arrived yet for container " + containerId;
            return new Collected(List.of(), List.of(
                    new TelemetryGap(ID, CPU, TelemetryAvailability.NO_DATA, why),
                    new TelemetryGap(ID, MEMORY, TelemetryAvailability.NO_DATA, why)),
                    List.of());
        }

        List<MetricObservation> observations = new ArrayList<>();
        List<ResourceSignal> signals = new ArrayList<>();

        MetricObservation cpuObservation =
                observation(CPU, "Container CPU", MetricUnit.RATIO, reading.get().cpuRatio(), query);
        ResourceSignal cpuSignal = new ResourceSignal(cpuObservation, ResourceKind.CPU,
                scope, cpuLimitIfConfigured());
        observations.add(cpuObservation);
        signals.add(cpuSignal);

        MetricObservation memoryObservation = observation(MEMORY, "Container memory", MetricUnit.BYTES,
                reading.get().memoryUsedBytes(), query);
        ResourceSignal memorySignal = new ResourceSignal(memoryObservation, ResourceKind.MEMORY,
                scope, memoryLimitIfConfigured());
        observations.add(memoryObservation);
        signals.add(memorySignal);

        return new Collected(observations, List.of(), signals);
    }

    /** A limit only when Vortex actually requested and confirmed one (§5/§6 of the plan) — never a
     *  fabricated ceiling for a target that asked for none. */
    private ResourceLimit cpuLimitIfConfigured() {
        if (resources == null) {
            return null;
        }
        return resources.cpuIfPresent()
                .map(cpu -> ResourceLimit.vortexConfigured(cpu.millicores() / 1000.0, MetricUnit.RATIO,
                        "the CPU limit Vortex applied to this container"))
                .orElse(null);
    }

    private ResourceLimit memoryLimitIfConfigured() {
        if (resources == null) {
            return null;
        }
        return resources.memoryIfPresent()
                .map(memory -> ResourceLimit.vortexConfigured((double) memory.bytes(), MetricUnit.BYTES,
                        "the memory limit Vortex applied to this container"))
                .orElse(null);
    }

    private MetricObservation observation(String id, String name, MetricUnit unit, double value,
            ObservabilityQuery query) {
        return new MetricObservation(id, name, MetricSource.DERIVED, unit, Aggregation.MAX, value,
                query.window(), Map.of(), ObservationProvenance.of(ID, "docker stats " + containerId),
                null);
    }

    /**
     * Starts the long-lived {@code docker stats} stream on first use.
     *
     * <p>Normally exactly once — a container this provider watches never changes mid-run, so there is
     * nothing to restart. When {@link #retryStreamStartup} is set, a failed attempt is retried on the
     * next {@link #collect} instead of sticking permanently, since the container this provider names
     * may not exist yet the first time this runs — see that field's Javadoc.
     */
    private void ensureStreamStarted() {
        if (handle != null) {
            return;
        }
        if (streamStartupFailure != null && !retryStreamStartup) {
            return;
        }
        synchronized (streamLock) {
            if (handle != null) {
                return;
            }
            if (streamStartupFailure != null && !retryStreamStartup) {
                return;
            }
            try {
                handle = dockerProcess.stream(
                        List.of(dockerExecutable, "stats", "--format", "{{json .}}", containerId),
                        this::onLine);
                streamStartupFailure = null;
            } catch (RuntimeException e) {
                streamStartupFailure = "the docker stats stream for container " + containerId
                        + " could not be started: " + e.getMessage();
                log.warn("Could not start container telemetry for {}: {}", containerId, e.getMessage());
            }
        }
    }

    /** Called from the stream's pump thread for every raw line it reads. Only a line that actually
     *  parses updates {@link #latestReading} — see that field's Javadoc for why a junk line must
     *  leave the last known-good reading alone rather than blanking it out. */
    private void onLine(String rawLine) {
        parse(rawLine).ifPresent(latestReading::set);
    }

    /**
     * Stops the background stream, if one was ever started. Idempotent, and safe to call whether or
     * not {@link #collect} was ever called at all — {@link ObservabilityTelemetryCollector} calls
     * this once, when the sampling session it belongs to ends, so the stream does not outlive the run
     * it was watching.
     */
    @Override
    public void close() {
        DockerProcess.StreamHandle current = handle;
        if (current != null) {
            current.stop();
        }
    }

    /** One parsed {@code docker stats} reading: CPU as a fraction of one core (not clamped to 1.0 —
     *  a multi-core container legitimately exceeds it), and memory used in exact bytes. */
    private record Reading(double cpuRatio, long memoryUsedBytes) {
    }

    /**
     * Parses one line of {@code docker stats --format '{{json .}}'} output, tolerating the ANSI
     * cursor-control escape sequences the Docker CLI interleaves between JSON lines (see the class
     * Javadoc's investigation note) and any line that is not a complete JSON object at all — this is
     * called against whatever the stream most recently delivered, which can be a stray control-code
     * fragment as easily as a full reading.
     */
    static Optional<Reading> parse(String rawLine) {
        if (rawLine == null) {
            return Optional.empty();
        }
        String cleaned = ANSI_ESCAPE.matcher(rawLine).replaceAll("").trim();
        if (cleaned.isEmpty()) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(cleaned);
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        if (node == null || !node.has("CPUPerc") || !node.has("MemUsage")) {
            return Optional.empty();
        }
        Double cpuRatio = parseCpuPercent(node.path("CPUPerc").asText(""));
        Long memoryBytes = parseMemoryUsed(node.path("MemUsage").asText(""));
        if (cpuRatio == null || memoryBytes == null) {
            return Optional.empty();
        }
        return Optional.of(new Reading(cpuRatio, memoryBytes));
    }

    /** {@code docker stats}' {@code CPUPerc}, e.g. {@code "12.34%"}, turned into a fraction of one
     *  core by dividing by 100 — matching {@code CpuAllocation}'s own millicores/1000 convention,
     *  never clamped, since Docker reports over 100% for a container using more than one core. */
    private static Double parseCpuPercent(String text) {
        String trimmed = text.trim();
        if (!trimmed.endsWith("%")) {
            return null;
        }
        try {
            return Double.parseDouble(trimmed.substring(0, trimmed.length() - 1).trim()) / 100.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code docker stats}' {@code MemUsage}, e.g. {@code "9.32MiB / 7.654GiB"} — the used side,
     *  parsed into an exact byte count. Chosen over the sibling {@code MemPerc} field precisely
     *  because it is not a percentage of an unknown denominator: Vortex already knows the configured
     *  limit, if any, from {@code EffectiveResourceEnvelope}, and does not need to recover it by
     *  dividing back through a rounded percentage. */
    private static Long parseMemoryUsed(String text) {
        int slash = text.indexOf('/');
        String used = (slash >= 0 ? text.substring(0, slash) : text).trim();
        Matcher matcher = MEMORY_TOKEN.matcher(used);
        if (!matcher.matches()) {
            return null;
        }
        double amount;
        try {
            amount = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        Long scale = BINARY_UNIT_SCALE.get(matcher.group(2));
        return scale == null ? null : Math.round(amount * scale);
    }
}
