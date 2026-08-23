package dev.vortex.app.service;

import dev.vortex.core.analysis.Analysis;
import dev.vortex.core.application.AnalysisService;
import dev.vortex.core.shared.ExecutionId;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs AI interpretation in the background, so a slow model never holds a request open.
 *
 * <p>Inference against a local model can take anything from a second to a minute. Blocking a page
 * load on that would make the assistant feel like part of the critical path, which is precisely
 * what it is not: the run is already complete and already has a verdict by the time anything here
 * is called.
 *
 * <p>Requests are de-duplicated per execution. Clicking twice should not start two analyses of the
 * same run.
 */
@Service
public class AnalysisRunner {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRunner.class);

    private final AnalysisService analyses;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public AnalysisRunner(AnalysisService analyses) {
        this.analyses = analyses;
    }

    /**
     * Starts an interpretation if one is not already running for this execution.
     *
     * @return whether a new analysis was started
     */
    public boolean start(ExecutionId executionId) {
        if (!inFlight.add(executionId.value())) {
            return false;
        }

        Thread.ofVirtual()
                .name("vortex-analysis-" + executionId.value())
                .start(() -> {
                    try {
                        Analysis analysis = analyses.analyze(executionId);
                        log.info("Analysis of {} finished: {}", executionId, analysis.state());
                    } catch (RuntimeException e) {
                        // Contained on purpose. A failed interpretation is a failed interpretation;
                        // the execution and its measurements are untouched.
                        log.warn("Analysis of {} failed: {}", executionId, e.getMessage());
                    } finally {
                        inFlight.remove(executionId.value());
                    }
                });
        return true;
    }

    public boolean isRunning(ExecutionId executionId) {
        return inFlight.contains(executionId.value());
    }

    public Optional<Analysis> latest(ExecutionId executionId) {
        return analyses.latest(executionId);
    }

    public java.util.List<Analysis> history(ExecutionId executionId) {
        return analyses.history(executionId);
    }

    public dev.vortex.core.port.PerformanceAssistant.Availability availability() {
        return analyses.availability();
    }
}
