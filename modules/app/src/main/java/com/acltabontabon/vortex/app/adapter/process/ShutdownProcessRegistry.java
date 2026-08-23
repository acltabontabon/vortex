package com.acltabontabon.vortex.app.adapter.process;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks live child processes so they can be killed when Vortex shuts down.
 *
 * <p>A process spawned by {@link ProcessBuilder} is not tied to its parent JVM's lifetime on POSIX —
 * killing Vortex reparents the child to init and it keeps running. A k6 load generator orphaned this
 * way keeps sending traffic to whatever it was pointed at; an orphaned {@code docker compose} stack
 * keeps consuming resources. Every process an adapter starts should be registered here at launch, so
 * none of that survives Vortex's own shutdown.
 */
public final class ShutdownProcessRegistry {

    private final Set<Process> processes = ConcurrentHashMap.newKeySet();

    /**
     * Registers a freshly started process. Self-removes on exit, so nothing needs to unregister it.
     */
    public void track(Process process) {
        processes.add(process);
        process.onExit().thenRun(() -> processes.remove(process));
    }

    /**
     * Kills every still-tracked process immediately.
     *
     * <p>Called once, as Vortex shuts down. No grace period here — the JVM is already going down, so
     * there is no time to ask nicely.
     */
    public void destroyAll() {
        for (Process process : processes) {
            process.destroyForcibly();
        }
    }
}
