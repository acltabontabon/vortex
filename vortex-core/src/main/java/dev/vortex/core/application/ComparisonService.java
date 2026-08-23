package dev.vortex.core.application;

import dev.vortex.core.comparison.ExecutionComparison;
import dev.vortex.core.comparison.RegressionEvaluator;
import dev.vortex.core.comparison.RegressionVerdict;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.plan.ExperimentIdentity;
import dev.vortex.core.port.Repositories.ExecutionRepository;
import dev.vortex.core.shared.ExecutionId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Finding the run worth comparing against, and comparing against it.
 *
 * <p>One home for the workflow, because the web interface, the command line and a pipeline must
 * agree about what "the previous run" means. A tool whose CI mode picks a different baseline from
 * its interactive mode will eventually report a regression in one and not the other, and by then
 * nobody will trust either.
 *
 * <p>The lookup is by experiment identity, not by workload name and not by recency. "The last run
 * of this project" is almost never the right baseline — it may have used a different workload, a
 * different environment or different objectives, and comparing against it would measure the
 * difference between two experiments rather than a change in the service.
 */
public final class ComparisonService {

    /**
     * How far back to look for a baseline.
     *
     * <p>More than one, because the most recent compatible run is not always the one wanted — a
     * caller may need to skip past a run of the same build. Small, because a baseline from fifty
     * runs ago is a different service.
     */
    private static final int CANDIDATE_LIMIT = 20;

    private final ExecutionRepository executions;
    private final RegressionEvaluator evaluator;

    public ComparisonService(ExecutionRepository executions, RegressionEvaluator evaluator) {
        this.executions = Objects.requireNonNull(executions, "executions");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    public Optional<TestExecution> find(ExecutionId id) {
        return executions.findById(id);
    }

    /**
     * The most recent earlier run of the same experiment.
     *
     * <p>Identity is derived from the candidate's own plan rather than read from its stored
     * fingerprint, so a run recorded under an older identity contract still finds its history.
     * The index it is matched against is kept current by
     * {@link ExecutionService#reconcileExperimentIdentity()}.
     */
    public Optional<TestExecution> previousCompatible(TestExecution candidate) {
        Objects.requireNonNull(candidate, "candidate");
        String fingerprint = ExperimentIdentity.fingerprintOf(candidate.plan()).hash();

        return executions.findCompatible(candidate.projectId(), fingerprint,
                        candidate.requestedAt(), CANDIDATE_LIMIT)
                .stream()
                .filter(earlier -> !earlier.id().equals(candidate.id()))
                .findFirst();
    }

    /**
     * Every earlier run of the same experiment, most recent first.
     *
     * <p>Offered alongside {@link #previousCompatible} so a caller can show what a comparison could
     * be made against, rather than only the one Vortex would pick.
     */
    public List<TestExecution> compatibleHistory(TestExecution candidate) {
        String fingerprint = ExperimentIdentity.fingerprintOf(candidate.plan()).hash();
        return executions.findCompatible(candidate.projectId(), fingerprint,
                        candidate.requestedAt(), CANDIDATE_LIMIT)
                .stream()
                .filter(earlier -> !earlier.id().equals(candidate.id()))
                .toList();
    }

    /** Two runs side by side, whether or not a verdict is possible. */
    public ExecutionComparison compare(TestExecution baseline, TestExecution candidate) {
        return evaluator.compare(baseline, candidate);
    }

    /** The regression verdict, or {@link RegressionVerdict#NOT_COMPARABLE}. */
    public RegressionVerdict evaluate(ExecutionComparison comparison) {
        return evaluator.evaluate(comparison);
    }

    /**
     * A comparison and its verdict together, which is how every caller actually uses this.
     *
     * @param verdict {@link RegressionVerdict#NOT_COMPARABLE} when the two ran different experiments
     */
    public record Result(TestExecution baseline, TestExecution candidate,
            ExecutionComparison comparison, RegressionVerdict verdict) {

        public boolean supportsVerdict() {
            return verdict != RegressionVerdict.NOT_COMPARABLE;
        }
    }

    public Result compareAndEvaluate(TestExecution baseline, TestExecution candidate) {
        ExecutionComparison comparison = compare(baseline, candidate);
        return new Result(baseline, candidate, comparison, evaluate(comparison));
    }
}
