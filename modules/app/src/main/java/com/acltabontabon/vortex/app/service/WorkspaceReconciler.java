package com.acltabontabon.vortex.app.service;

import com.acltabontabon.vortex.core.application.ExecutionService;
import com.acltabontabon.vortex.core.port.PerformanceAssistant;
import com.acltabontabon.vortex.core.port.PerformanceEngine;
import com.acltabontabon.vortex.persistence.VortexWorkspace;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Brings the workspace back into a truthful state on start-up, and prints the "ready" summary a
 * terminal user is waiting for — replacing Spring Boot's own "started on port X" announcement
 * (silenced in {@code application.yaml}, see {@code docs/adr/adr-053-startup-console-output.adoc})
 * with the whole browsable URL, the workspace being read, and — a moment later — what's available.
 *
 * <p>Four things happen on {@link ApplicationReadyEvent}, and only the first three can prevent
 * start-up from being trusted, which is exactly why they need doing before anyone looks at the
 * interface.
 *
 * <p><strong>Runs left in flight.</strong> Vortex does not adopt orphaned engine processes: it
 * cannot know whether the k6 that was running still is, and resuming would risk a second load
 * generator against the same target. Those runs are marked interrupted, because history that shows
 * a test apparently still going is worse than history that admits it was cut off.
 *
 * <p><strong>Targets left running.</strong> The lease a run holds over its container is released in
 * a {@code finally} that a killed process never reaches, so the container outlives the run by however
 * long it takes somebody to notice. This is the one stale thing here that does not merely mislead:
 * an abandoned container holds its port and its share of the machine, and the next run measures a
 * host that is quietly busier than it looks.
 *
 * <p><strong>Experiment identity.</strong> When the identity contract changes, every stored
 * fingerprint indexes its run under a hash nothing will look up again, and a team's comparison
 * history vanishes without a word — "no previous compatible run exists" being indistinguishable
 * from "this is the first run". Re-indexing costs one query on an ordinary boot.
 *
 * <p>None of the first three are allowed to prevent start-up. A workspace Vortex cannot tidy is
 * still a workspace somebody may need to open in order to find out why.
 *
 * <p><strong>What's available.</strong> Runs on a detached virtual thread, after everything above,
 * since {@code PerformanceAssistant.availability()} (backed by {@code OllamaAvailability}) does a
 * real few-second HTTP probe and {@code LocalLab.status()} shells out to {@code docker} — neither
 * installed is a normal, fully-supported state for Vortex, so the ready line must never wait on them.
 */
@Component
public class WorkspaceReconciler {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceReconciler.class);

    private final ExecutionService executions;
    private final Environment environment;
    private final VortexWorkspace workspace;
    private final PerformanceEngine engine;
    private final PerformanceAssistant assistant;
    private final LocalLabRunner lab;

    public WorkspaceReconciler(ExecutionService executions, Environment environment,
            VortexWorkspace workspace, PerformanceEngine engine, PerformanceAssistant assistant,
            LocalLabRunner lab) {
        this.executions = executions;
        this.environment = environment;
        this.workspace = workspace;
        this.engine = engine;
        this.assistant = assistant;
        this.lab = lab;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcile() {
        String address = environment.getProperty("server.address", "127.0.0.1");
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "7717"));
        log.info("Ready → http://{}:{}", address, port);
        log.info("Workspace: {}", workspace.root());
        Thread.ofVirtual().name("vortex-startup-probe").start(this::logEnvironmentSummary);

        try {
            int interrupted = executions.reconcileUnfinished();
            if (interrupted > 0) {
                log.info("Marked {} run(s) as interrupted: they were still in flight when Vortex "
                        + "last stopped, and Vortex does not adopt orphaned engine processes.",
                        interrupted);
            }
        } catch (RuntimeException e) {
            log.warn("Could not reconcile unfinished runs: {}", e.getMessage());
        }

        // After reconcileUnfinished(), never before: that call is what moves the runs it just
        // interrupted out of "in flight", which is the set this one refuses to touch.
        try {
            List<String> released = executions.releaseOrphanedTargets();
            if (!released.isEmpty()) {
                log.info("Released {} target(s) left running by a previous process: {}",
                        released.size(), String.join(", ", released));
            }
        } catch (RuntimeException e) {
            log.warn("Could not release targets left running by a previous process: {}. They may "
                    + "still be holding a port and a share of this machine, which would make the "
                    + "next run's measurements look worse than the service deserves.", e.getMessage());
        }

        try {
            int reindexed = executions.reconcileExperimentIdentity();
            if (reindexed > 0) {
                log.info("Re-indexed {} execution(s) against the current experiment identity "
                        + "contract, so earlier runs remain available for comparison.", reindexed);
            }
        } catch (RuntimeException e) {
            log.warn("Could not re-index experiment identity: {}. Comparison against runs recorded "
                    + "before this version may not find them.", e.getMessage());
        }
    }

    /** Off the startup path entirely — safe to block here. Never lets a probe failure crash or spam
     *  the console of an otherwise-healthy running Vortex. */
    private void logEnvironmentSummary() {
        try {
            var engineAvailability = engine.availability();
            var aiAvailability = assistant.availability();
            var labStatus = lab.status();

            log.info("Environment: k6 {}, Docker {}, Local AI {}",
                    engineAvailability.available()
                            ? "available (" + engineAvailability.version() + ")" : "not detected",
                    labStatus.dockerAvailable() ? "available" : "not detected",
                    aiAvailability.available()
                            ? "available (" + aiAvailability.provider() + "/" + aiAvailability.model() + ")"
                            : "not detected");
        } catch (RuntimeException e) {
            log.debug("Startup environment probe failed", e);
        }
    }
}
