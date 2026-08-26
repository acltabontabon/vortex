package com.acltabontabon.vortex.app.service;

import com.acltabontabon.vortex.core.application.ComparisonAnalysisService;
import com.acltabontabon.vortex.core.comparison.ComparisonAnalysis;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs AI interpretation of a comparison in the background, mirroring {@link AnalysisRunner}.
 *
 * <p>Comparisons are not persisted the way a single execution's analyses are — the deterministic
 * comparison itself is recomputed on every page view, and this mirrors that. What is cached here is
 * deliberately small and bounded: a comparison is an exploratory, many-to-many query, and a
 * candidate compared against five different baselines in one session should not accumulate five
 * permanent rows. The cache exists only so the polled fragment can find a result that just finished,
 * not to remember it beyond the session.
 */
@Service
public class ComparisonAnalysisRunner {

    private static final Logger log = LoggerFactory.getLogger(ComparisonAnalysisRunner.class);

    /** How many recent comparisons to keep. Small on purpose — see the class Javadoc. */
    private static final int MAX_CACHED = 50;

    private record Key(ExecutionId baseline, ExecutionId candidate) {
    }

    private final ComparisonAnalysisService comparisons;
    private final Set<Key> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<Key, ComparisonAnalysis> results = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, ComparisonAnalysis> eldest) {
                    return size() > MAX_CACHED;
                }
            });

    public ComparisonAnalysisRunner(ComparisonAnalysisService comparisons) {
        this.comparisons = comparisons;
    }

    /** Starts an interpretation if one is not already running for this pair. */
    public boolean start(TestExecution baseline, TestExecution candidate) {
        Key key = new Key(baseline.id(), candidate.id());
        if (!inFlight.add(key)) {
            return false;
        }

        Thread.ofVirtual()
                .name("vortex-comparison-" + key.baseline().value() + "-" + key.candidate().value())
                .start(() -> {
                    try {
                        ComparisonAnalysis analysis = comparisons.analyze(baseline, candidate);
                        results.put(key, analysis);
                        log.info("Comparison of {} vs {} finished: {}", key.baseline(), key.candidate(),
                                analysis.state());
                    } catch (RuntimeException e) {
                        log.warn("Comparison of {} vs {} failed: {}", key.baseline(), key.candidate(),
                                e.getMessage());
                    } finally {
                        inFlight.remove(key);
                    }
                });
        return true;
    }

    public boolean isRunning(ExecutionId baseline, ExecutionId candidate) {
        return inFlight.contains(new Key(baseline, candidate));
    }

    /**
     * The most recent interpretation attempted for this pair, whatever its state — including a
     * FAILED one, so the caller can surface why it failed rather than have it silently look like no
     * interpretation was ever requested.
     */
    public Optional<ComparisonAnalysis> latest(ExecutionId baseline, ExecutionId candidate) {
        return Optional.ofNullable(results.get(new Key(baseline, candidate)));
    }

    public com.acltabontabon.vortex.core.port.PerformanceAssistant.Availability availability() {
        return comparisons.availability();
    }
}
