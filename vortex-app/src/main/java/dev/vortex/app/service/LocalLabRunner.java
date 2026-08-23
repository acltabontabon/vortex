package dev.vortex.app.service;

import dev.vortex.core.port.LocalLab;
import dev.vortex.core.shared.ProjectId;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs Compose commands in the background, so a slow one never holds a request open.
 *
 * <p>A first {@code compose up} pulls images and then waits for healthchecks; minutes is normal.
 * Blocking a page load on that would make the local lab feel like part of the critical path, and it
 * is not part of any path — starting dependencies is a convenience a developer asks for explicitly.
 *
 * <p>What is remembered is what Vortex actually ran and what came back. This class does not watch
 * containers, and nothing that reads it should present it as though it does. See
 * {@code docs/02-architecture/architecture.adoc} (Local lab).
 */
@Service
public class LocalLabRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalLabRunner.class);

    /**
     * How many services' outcomes to keep.
     *
     * <p>Small on purpose. This is a convenience for whoever is looking at a settings page right
     * now, not a history worth persisting, and an unbounded map of results would grow for as long as
     * the process lives.
     */
    private static final int MAX_REMEMBERED = 32;

    /** How long a capability answer stays good enough to reuse. */
    private static final Duration CAPABILITY_TTL = Duration.ofSeconds(30);

    /** Which Compose command was asked for. */
    public enum Operation {
        UP("start", "up"),
        DOWN("stop", "down");

        private final String label;
        private final String command;

        Operation(String label, String command) {
            this.label = label;
            this.command = command;
        }

        /** How to name the operation in a sentence. */
        public String label() {
            return label;
        }

        /** The Compose subcommand, for saying exactly what was run. */
        public String command() {
            return command;
        }
    }

    /**
     * One Compose command Vortex ran, or is running.
     *
     * @param operation   which command
     * @param composeFile the file it was run against. Recorded rather than looked up later, because
     *                    it is evidence of what happened: if somebody points the service at a
     *                    different Compose file afterwards, an earlier success must not appear to
     *                    describe the new one
     * @param running     whether it is still going
     * @param result      what Compose reported, once it finished. Null while running
     * @param startedAt   when it started
     * @param finishedAt  when it finished, or null while running
     */
    public record Activity(
            Operation operation,
            Path composeFile,
            boolean running,
            LocalLab.LabResult result,
            Instant startedAt,
            Instant finishedAt) {

        public Optional<LocalLab.LabResult> resultIfPresent() {
            return Optional.ofNullable(result);
        }

        public boolean succeeded() {
            return result != null && result.success();
        }

        public boolean failed() {
            return result != null && !result.success();
        }
    }

    private final LocalLab localLab;
    private final Map<String, Activity> activity = new ConcurrentHashMap<>();
    private final AtomicReference<CachedCapability> capability = new AtomicReference<>();

    public LocalLabRunner(LocalLab localLab) {
        this.localLab = localLab;
    }

    /**
     * Starts a Compose command unless one is already running for this service.
     *
     * <p>One claim covers both directions, so {@code up} and {@code down} cannot race each other
     * into the same project's containers.
     *
     * @return whether a command was started
     */
    public boolean start(ProjectId projectId, Operation operation, Path composeFile) {
        String key = projectId.value();
        Instant startedAt = Instant.now();

        AtomicBoolean claimed = new AtomicBoolean(false);
        activity.compute(key, (id, current) -> {
            if (current != null && current.running()) {
                return current;
            }
            claimed.set(true);
            return new Activity(operation, composeFile, true, null, startedAt, null);
        });
        if (!claimed.get()) {
            return false;
        }
        evictOldestFinished();

        Thread.ofVirtual()
                .name("vortex-lab-" + operation.command() + "-" + key)
                .start(() -> {
                    LocalLab.LabResult result;
                    try {
                        result = operation == Operation.UP
                                ? localLab.up(composeFile.toString())
                                : localLab.down(composeFile.toString());
                        log.info("Compose {} for {} finished: {}", operation.label(), key,
                                result.success() ? "succeeded" : "failed");
                    } catch (RuntimeException e) {
                        // Contained on purpose. A Compose command that blew up is a failed command,
                        // and the service's configuration and evidence are untouched by it.
                        log.warn("Compose {} for {} failed: {}", operation.label(), key,
                                e.getMessage());
                        result = new LocalLab.LabResult(false,
                                "The " + operation.label() + " command could not be run.",
                                List.of(String.valueOf(e.getMessage())));
                    }
                    activity.put(key, new Activity(operation, composeFile, false, result, startedAt,
                            Instant.now()));
                });
        return true;
    }

    public boolean isRunning(ProjectId projectId) {
        Activity current = activity.get(projectId.value());
        return current != null && current.running();
    }

    /** The last command Vortex ran for this service, running or finished. */
    public Optional<Activity> activity(ProjectId projectId) {
        return Optional.ofNullable(activity.get(projectId.value()));
    }

    /**
     * Discards what is remembered for a service.
     *
     * <p>Called when the Compose file changes: a result describing a file that is no longer
     * configured would be read as describing the one that is.
     */
    public void forget(ProjectId projectId) {
        activity.remove(projectId.value());
    }

    /**
     * What container tooling this machine has.
     *
     * <p>Memoised briefly, because three subprocesses per page load is a real cost on a page nobody
     * expects to be slow, and the answer changes about as often as somebody installs Docker.
     *
     * <p>This is <em>machine capability</em>: is Docker installed, is the daemon reachable, is
     * Compose usable. It says nothing about whether any service's dependencies are up — Vortex does
     * not track that — so finishing an {@code up} or {@code down} is not a reason to invalidate it.
     */
    public LocalLab.LabStatus status() {
        CachedCapability cached = capability.get();
        Instant now = Instant.now();
        if (cached != null && cached.checkedAt().plus(CAPABILITY_TTL).isAfter(now)) {
            return cached.status();
        }
        LocalLab.LabStatus fresh = localLab.status();
        capability.set(new CachedCapability(fresh, now));
        return fresh;
    }

    /** Drops the oldest finished outcome once too many services have one. */
    private void evictOldestFinished() {
        if (activity.size() <= MAX_REMEMBERED) {
            return;
        }
        activity.entrySet().stream()
                .filter(entry -> !entry.getValue().running())
                .min(Comparator.comparing(entry -> entry.getValue().startedAt()))
                .map(Map.Entry::getKey)
                .ifPresent(activity::remove);
    }

    private record CachedCapability(LocalLab.LabStatus status, Instant checkedAt) {
    }
}
