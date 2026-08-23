package dev.vortex.core.port;

import dev.vortex.core.execution.ExecutionProgress;
import dev.vortex.core.metrics.MeasuredResults;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.ToolVersions;
import dev.vortex.core.shared.ExecutionId;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Generates load and reports what happened.
 *
 * <p>The boundary that keeps Vortex from becoming a load-testing engine. k6 is excellent at
 * generating traffic, scheduling virtual users, evaluating thresholds and running distributed — all
 * of it commodity infrastructure that would be foolish to rebuild. Vortex's contribution is
 * everything around it: knowing what workload to model, how to run it safely, what the numbers
 * mean, and how to compare them next month.
 *
 * <p>Because the domain talks to this interface rather than to k6, adding a second engine or moving
 * to distributed execution through the k6 Operator means implementing this and nothing else.
 */
public interface PerformanceEngine {

    /** Whether the engine is usable right now, and what to do if not. */
    EngineAvailability availability();

    /**
     * Checks a plan without generating traffic.
     *
     * <p>Catches workload and configuration errors before anything is sent to a real service.
     */
    ValidationResult validate(EffectiveTestPlan plan);

    /**
     * Runs a plan to completion, reporting progress as it goes.
     *
     * <p>Blocking. Callers run it on a virtual thread. Progress arrives as pre-aggregated buckets,
     * not raw samples — see {@code dev.vortex.core.metrics.SamplePoint}.
     *
     * @param executionId    identifies the run and its artifact directory
     * @param plan           what to execute
     * @param progressSink   receives periodic progress; must not block
     * @param cancellation   polled between buckets so a run can be stopped
     * @return the normalised measurements
     */
    EngineOutcome execute(ExecutionId executionId, EffectiveTestPlan plan,
            Consumer<ExecutionProgress> progressSink, Cancellation cancellation);

    /** Versions of the engine and its runtime, recorded for reproducibility. */
    ToolVersions toolVersions();

    /** Cooperative cancellation, polled by the engine between aggregation buckets. */
    @FunctionalInterface
    interface Cancellation {

        boolean isCancelled();

        static Cancellation never() {
            return () -> false;
        }
    }

    /**
     * Whether the engine can run, and how to fix it when it cannot.
     *
     * @param available whether a run could start now
     * @param version   the detected engine version
     * @param problem   what is wrong, in plain language
     * @param remedy    what the user should do about it
     */
    record EngineAvailability(boolean available, String version, String problem, String remedy) {

        public static EngineAvailability ready(String version) {
            return new EngineAvailability(true, version, "", "");
        }

        public static EngineAvailability unavailable(String problem, String remedy) {
            return new EngineAvailability(false, "", problem, remedy);
        }
    }

    /**
     * The result of validating a plan.
     *
     * @param valid    whether the plan can be executed
     * @param problems what would prevent it, each phrased as something a user can act on
     */
    record ValidationResult(boolean valid, List<String> problems) {

        public ValidationResult {
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        public static ValidationResult ok() {
            return new ValidationResult(true, List.of());
        }

        public static ValidationResult invalid(List<String> problems) {
            return new ValidationResult(false, problems);
        }
    }

    /**
     * What a completed run produced.
     *
     * @param results       normalised measurements, absent when the run failed before producing any
     * @param exitCode      the engine's exit code
     * @param failureDetail diagnostic detail preserved for troubleshooting
     * @param artifactNames artifacts written to the execution directory
     */
    record EngineOutcome(
            MeasuredResults results,
            int exitCode,
            String failureDetail,
            List<String> artifactNames) {

        public EngineOutcome {
            artifactNames = artifactNames == null ? List.of() : List.copyOf(artifactNames);
            failureDetail = failureDetail == null ? "" : failureDetail;
        }

        public Optional<MeasuredResults> resultsIfPresent() {
            return Optional.ofNullable(results);
        }

        public boolean producedResults() {
            return results != null;
        }
    }
}
