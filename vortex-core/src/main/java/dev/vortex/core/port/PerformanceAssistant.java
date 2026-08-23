package dev.vortex.core.port;

import dev.vortex.core.analysis.Analysis;
import dev.vortex.core.analysis.DeterministicSummary;
import dev.vortex.core.capacity.ProductionObservation;
import dev.vortex.core.comparison.ComparisonAnalysis;
import dev.vortex.core.comparison.ExecutionComparison;
import dev.vortex.core.comparison.RegressionVerdict;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.shared.ExecutionId;
import java.util.List;
import java.util.Optional;

/**
 * Interprets performance evidence and proposes starting points. Never decides anything.
 *
 * <p>The division of labour is fixed and not negotiable by configuration. Measurements, threshold
 * verdicts, breakpoints, headroom and regression deltas are computed by deterministic code in
 * {@code vortex-core}. This interface receives results that have <em>already</em> been calculated
 * and adds explanation, hypotheses and suggestions on top.
 *
 * <p>Concretely: Vortex calculates that the observed peak of 120 requests/sec at a 1.5× forecast
 * policy gives 180, and applies its rounding rule. The assistant explains that this provides roughly
 * 1.5× headroom over observed production traffic. It is never asked to do the multiplication.
 *
 * <p>Every implementation must degrade gracefully. When no model is reachable, each method returns
 * an empty or failed result and the product continues to work: onboarding, configuration, execution,
 * metric collection, threshold evaluation and reporting all function with no AI at all.
 *
 * <p>Nothing here may start a test. Proposals are data, reviewed by a person before they can run.
 *
 * <p>There is deliberately no method that invents a workload. Operations are discovered from an API
 * description with certainty, and how much traffic each one receives is a fact about production that
 * a model cannot know. Asking one to guess would produce a plausible mix with no provenance, which is
 * precisely the fabricated precision Vortex exists to avoid.
 */
public interface PerformanceAssistant {

    /** Whether a model is reachable right now, and what to do if not. */
    Availability availability();

    /** Explains a set of calculated workload suggestions in plain language. */
    Optional<String> explainWorkload(ProductionObservation observation, List<String> calculatedSuggestions);

    /**
     * Interprets a completed run.
     *
     * <p>Receives the deterministic summary — never raw engine output — and must cite evidence by
     * identifier so that every claim can be resolved against a measurement that was actually taken.
     */
    Analysis analyze(ExecutionId executionId, EffectiveTestPlan plan, DeterministicSummary summary);

    /**
     * Interprets what materially changed between two executions.
     *
     * <p>Receives the deterministic comparison — deltas, compatibility, verdict — already computed
     * by {@code RegressionEvaluator}; this method never computes its own percentages. Every finding
     * it returns must cite a {@code delta:} identifier, resolved the same way an execution
     * analysis's findings are.
     */
    ComparisonAnalysis compareExecutions(TestExecution baseline, TestExecution candidate,
            ExecutionComparison comparison, RegressionVerdict verdict);

    /**
     * Whether a model can be reached.
     *
     * @param available whether requests would succeed
     * @param provider  e.g. {@code ollama}
     * @param model     the configured model
     * @param problem   what is wrong, in plain language
     * @param remedy    what the user should do about it
     */
    record Availability(boolean available, String provider, String model, String problem, String remedy) {

        public static Availability ready(String provider, String model) {
            return new Availability(true, provider, model, "", "");
        }

        public static Availability unavailable(String provider, String problem, String remedy) {
            return new Availability(false, provider, "", problem, remedy);
        }
    }
}
