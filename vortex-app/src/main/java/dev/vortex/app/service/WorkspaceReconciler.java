package dev.vortex.app.service;

import dev.vortex.core.application.ExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Brings the workspace back into a truthful state on start-up.
 *
 * <p>Two things can be stale after a process ends, and both are silent failures rather than loud
 * ones — which is exactly why they need doing before anyone looks at the interface.
 *
 * <p><strong>Runs left in flight.</strong> Vortex does not adopt orphaned engine processes: it
 * cannot know whether the k6 that was running still is, and resuming would risk a second load
 * generator against the same target. Those runs are marked interrupted, because history that shows
 * a test apparently still going is worse than history that admits it was cut off.
 *
 * <p><strong>Experiment identity.</strong> When the identity contract changes, every stored
 * fingerprint indexes its run under a hash nothing will look up again, and a team's comparison
 * history vanishes without a word — "no previous compatible run exists" being indistinguishable
 * from "this is the first run". Re-indexing costs one query on an ordinary boot.
 *
 * <p>Neither is allowed to prevent start-up. A workspace Vortex cannot tidy is still a workspace
 * somebody may need to open in order to find out why.
 */
@Component
public class WorkspaceReconciler {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceReconciler.class);

    private final ExecutionService executions;

    public WorkspaceReconciler(ExecutionService executions) {
        this.executions = executions;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcile() {
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
}
