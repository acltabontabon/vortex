package dev.vortex.core.comparison;

import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.metrics.MeasuredResults;
import dev.vortex.core.plan.ExperimentCompatibility;
import dev.vortex.core.plan.ExperimentIdentity;
import dev.vortex.core.shared.Percentile;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Compares two executions and, only when they genuinely tested the same experiment, decides whether
 * performance regressed.
 *
 * <p>Almost all of the value here is in the refusal rather than the arithmetic. Computing "p95 went
 * from 280 ms to 405 ms, +44.6%" is trivial; knowing whether that sentence means anything is not.
 *
 * <p>Whether it means anything is not decided here. {@link ExperimentIdentity} owns that question
 * for the whole product, so the definition of "the same experiment" cannot fork between the
 * fingerprint that indexes runs and the check that compares them — which is exactly what happened
 * when this class kept its own field-by-field equivalence test.
 *
 * <p>What this class adds is the layer identity cannot express, because it is a property of the
 * <em>executions</em> rather than of the experiment:
 *
 * <ul>
 *   <li><strong>A run that measured nothing.</strong> Two failed runs have identical plans and no
 *       results, which would otherwise arrive at "Unchanged" — the most confident statement Vortex
 *       could make about the least evidence it has.</li>
 *   <li><strong>A measurement that moved away from zero.</strong> 0% errors becoming 25% has no
 *       percentage change at all, and a healthy baseline is the normal baseline, so relative
 *       arithmetic silently drops the single most important regression a service can have.</li>
 * </ul>
 *
 * <p>Note what is deliberately <em>not</em> a difference: the release under test. Two runs of the
 * same experiment against different builds are the ideal comparison, not an incomparable pair.
 */
public final class RegressionEvaluator {

    /**
     * Relative change below which a difference is treated as run-to-run variance.
     *
     * <p>Local performance runs are noisy: scheduling, JIT warm-up and background processes move
     * p95 by a few percent between identical runs. Reporting every such wobble as a regression
     * would make the signal useless.
     */
    private static final BigDecimal NOISE_THRESHOLD_PERCENT = BigDecimal.valueOf(10);

    public ExecutionComparison compare(TestExecution baseline, TestExecution candidate) {
        BaselineEligibility eligibility = BaselineEligibility.of(baseline.quality());

        ExperimentCompatibility compatibility =
                ExperimentIdentity.compare(baseline.plan(), candidate.plan())
                        .and(unmeasured(baseline, candidate))
                        .and(coverage(baseline, candidate))
                        .and(ineligible(eligibility));

        List<MetricDelta> deltas = computeDeltas(baseline, candidate);
        List<RefusedDelta> refused = new ArrayList<>();

        // A run that did not measure what it claims to is not compared against at all. Everything
        // else keeps the deltas its degradation does not undermine — refusing the whole comparison
        // would, early on, leave almost every team with no baseline, since partial telemetry is the
        // normal case.
        if (!eligibility.offeredAsBaseline()) {
            eligibility.refusedDeltas().forEach(kind ->
                    refused.add(new RefusedDelta(kind, eligibility.reason())));
            deltas = List.of();
        } else {
            eligibility.refusedDeltas().forEach(kind ->
                    refused.add(new RefusedDelta(kind, eligibility.reason())));
            deltas = deltas.stream().filter(delta -> eligibility.permits(delta.kind())).toList();
        }

        return new ExecutionComparison(baseline.id(), candidate.id(), compatibility, deltas,
                refused, eligibility);
    }

    /**
     * Telemetry coverage that differs between the two runs.
     *
     * <p>Stated as a difference in the experiment rather than left to surface as a regression. Two
     * runs with different observability are not equally informative, and a resource that appears to
     * have improved because nobody measured it the second time is the specific wrong conclusion this
     * prevents.
     */
    private List<String> coverage(TestExecution baseline, TestExecution candidate) {
        String before = coverageSignature(baseline);
        String after = coverageSignature(candidate);
        if (before.equals(after)) {
            return List.of();
        }
        return List.of("telemetry coverage differs: the baseline observed ["
                + (before.isBlank() ? "nothing" : before) + "] and this run observed ["
                + (after.isBlank() ? "nothing" : after) + "]");
    }

    private String coverageSignature(TestExecution execution) {
        return execution.resultsIfPresent()
                .map(results -> results.observations().stream()
                        .map(observation -> observation.source().id())
                        .distinct()
                        .sorted()
                        .reduce((left, right) -> left + "," + right)
                        .orElse(""))
                .orElse("");
    }

    private List<String> ineligible(BaselineEligibility eligibility) {
        return eligibility.offeredAsBaseline() ? List.of() : List.of(eligibility.reason());
    }

    /**
     * Identical experiments are not enough: a run has to have produced measurements.
     *
     * <p>Reported as a difference rather than as an error so the UI explains which side is missing
     * evidence, instead of showing an empty table beside a confident verdict.
     */
    private List<String> unmeasured(TestExecution baseline, TestExecution candidate) {
        List<String> problems = new ArrayList<>();
        if (baseline.resultsIfPresent().isEmpty()) {
            problems.add("the baseline run produced no measurements ("
                    + baseline.state().label().toLowerCase(Locale.ROOT) + ")");
        }
        if (candidate.resultsIfPresent().isEmpty()) {
            problems.add("the candidate run produced no measurements ("
                    + candidate.state().label().toLowerCase(Locale.ROOT) + ")");
        }
        return problems;
    }

    /**
     * Produces a regression verdict, or declines to.
     *
     * <p>Only latency percentiles, error rate and achieved throughput participate: they are the
     * measurements an objective is expressed against, and therefore the ones a regression is
     * meaningful for.
     */
    public RegressionVerdict evaluate(ExecutionComparison comparison) {
        if (!comparison.supportsRegressionVerdict()) {
            return RegressionVerdict.NOT_COMPARABLE;
        }

        // Two runs that produced no measurements have not been shown to be unchanged; nothing was
        // compared.
        if (comparison.deltas().isEmpty()) {
            return RegressionVerdict.NOT_COMPARABLE;
        }

        boolean regressed = false;
        boolean improved = false;
        for (MetricDelta delta : comparison.deltas()) {
            Optional<Boolean> degraded = delta.isDegradation(NOISE_THRESHOLD_PERCENT);
            if (degraded.isEmpty()) {
                continue;
            }
            if (degraded.get()) {
                regressed = true;
            } else {
                improved = true;
            }
        }

        if (regressed) {
            return RegressionVerdict.REGRESSED;
        }
        return improved ? RegressionVerdict.IMPROVED : RegressionVerdict.UNCHANGED;
    }

    private List<MetricDelta> computeDeltas(TestExecution baseline, TestExecution candidate) {
        Optional<MeasuredResults> left = baseline.resultsIfPresent();
        Optional<MeasuredResults> right = candidate.resultsIfPresent();
        if (left.isEmpty() || right.isEmpty()) {
            return List.of();
        }

        List<MetricDelta> deltas = new ArrayList<>();
        for (Percentile percentile : List.of(Percentile.P50, Percentile.P95, Percentile.P99)) {
            Optional<Duration> a = left.get().latency().at(percentile);
            Optional<Duration> b = right.get().latency().at(percentile);
            if (a.isPresent() && b.isPresent()) {
                deltas.add(new MetricDelta(
                        percentile.label() + " latency",
                        "latency." + percentile.label(),
                        BigDecimal.valueOf(a.get().toNanos()).movePointLeft(6),
                        BigDecimal.valueOf(b.get().toNanos()).movePointLeft(6),
                        dev.vortex.core.threshold.Durations.display(a.get()) + " → "
                                + dev.vortex.core.threshold.Durations.display(b.get()),
                        true, DeltaKind.LATENCY));
            }
        }

        deltas.add(new MetricDelta("Error rate", "errorRate",
                left.get().errorRate().fraction(),
                right.get().errorRate().fraction(),
                left.get().errorRate().display() + " → " + right.get().errorRate().display(),
                true, DeltaKind.RELIABILITY));

        if (left.get().achievedRateIfPresent().isPresent()
                && right.get().achievedRateIfPresent().isPresent()) {
            deltas.add(new MetricDelta("Achieved throughput", "throughput.achieved",
                    left.get().achievedRate().value(),
                    right.get().achievedRate().value(),
                    left.get().achievedRate().display() + " → "
                            + right.get().achievedRate().displayWithUnit(),
                    false, DeltaKind.THROUGHPUT));
        }

        capacityDelta(baseline, candidate).ifPresent(deltas::add);
        qualityDelta(baseline, candidate).ifPresent(deltas::add);

        return deltas;
    }

    /**
     * How the sustainable capacity moved between two runs.
     *
     * <p>The figure a deployment decision rests on, so a change in it is the single most useful
     * thing a comparison can report. Absent when either run did not establish one — which is a real
     * and common outcome, and not the same as a capacity of zero.
     */
    private Optional<MetricDelta> capacityDelta(TestExecution baseline, TestExecution candidate) {
        Optional<dev.vortex.core.shared.RequestsPerSecond> before = sustainableRate(baseline);
        Optional<dev.vortex.core.shared.RequestsPerSecond> after = sustainableRate(candidate);
        if (before.isEmpty() || after.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MetricDelta("Sustainable capacity", "capacity.sustainable",
                before.get().value(), after.get().value(),
                before.get().display() + " → " + after.get().displayWithUnit(),
                false, DeltaKind.CAPACITY));
    }

    private Optional<dev.vortex.core.shared.RequestsPerSecond> sustainableRate(
            TestExecution execution) {
        return execution.summaryIfPresent()
                .flatMap(summary -> summary.limits().throughputCeilingIfPresent())
                .flatMap(ceiling -> ceiling.levelIfPresent())
                .filter(level -> level instanceof dev.vortex.core.shared.RequestsPerSecond)
                .map(level -> (dev.vortex.core.shared.RequestsPerSecond) level);
    }

    /**
     * Whether the two runs are equally trustworthy as experiments.
     *
     * <p>Reported as a delta of its own so a reader sees "this run is degraded and the baseline was
     * not" beside the measurements, rather than having to notice it elsewhere and reinterpret them.
     */
    private Optional<MetricDelta> qualityDelta(TestExecution baseline, TestExecution candidate) {
        var before = baseline.quality().quality();
        var after = candidate.quality().quality();
        if (before == after) {
            return Optional.empty();
        }
        return Optional.of(new MetricDelta("Run quality", "runQuality",
                BigDecimal.valueOf(before.ordinal()), BigDecimal.valueOf(after.ordinal()),
                before.label() + " → " + after.label(), true, DeltaKind.RUN_QUALITY));
    }
}
