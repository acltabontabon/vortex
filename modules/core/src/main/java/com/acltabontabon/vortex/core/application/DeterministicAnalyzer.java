package com.acltabontabon.vortex.core.application;

import com.acltabontabon.vortex.core.analysis.BreakpointDetector;
import com.acltabontabon.vortex.core.analysis.DeterministicSummary;
import com.acltabontabon.vortex.core.analysis.PercentileBasis;
import com.acltabontabon.vortex.core.analysis.RateAggregationBasis;
import com.acltabontabon.vortex.core.analysis.SloBreakpoint;
import com.acltabontabon.vortex.core.analysis.StageObservation;
import com.acltabontabon.vortex.core.analysis.SystemSaturation;
import com.acltabontabon.vortex.core.analysis.SystemSaturationDetector;
import com.acltabontabon.vortex.core.analysis.LimitFindings;
import com.acltabontabon.vortex.core.analysis.ResourceLimitDetector;
import com.acltabontabon.vortex.core.analysis.ResourceLimitFinding;
import com.acltabontabon.vortex.core.analysis.ThroughputCeiling;
import com.acltabontabon.vortex.core.analysis.ThroughputCeilingDetector;
import com.acltabontabon.vortex.core.validity.RunQualityAssessment;
import com.acltabontabon.vortex.core.evidence.WorkloadEvidence;
import com.acltabontabon.vortex.core.metrics.LatencyHistogram;
import com.acltabontabon.vortex.core.metrics.MeasuredResults;
import com.acltabontabon.vortex.core.metrics.StageTelemetry;
import com.acltabontabon.vortex.core.metrics.SamplePoint;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.LoadLevel;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.threshold.Durations;
import com.acltabontabon.vortex.core.threshold.LatencyThreshold;
import com.acltabontabon.vortex.core.threshold.Threshold;
import com.acltabontabon.vortex.core.threshold.ThresholdEvaluation;
import com.acltabontabon.vortex.core.threshold.ThresholdEvaluator;
import com.acltabontabon.vortex.core.threshold.Verdict;
import com.acltabontabon.vortex.core.workload.Stage;
import com.acltabontabon.vortex.core.workload.StageWindows;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Produces everything Vortex can say about a run without a language model.
 *
 * <p>This class is the answer to a fair question: if the AI is unavailable, what is left? The
 * answer has to be "almost all of it". A team with no local model installed still gets the question
 * the test asked, the verdict, offered versus achieved throughput, per-operation figures,
 * latency percentiles, error rate, an objective-by-objective table, and — for ramping workloads —
 * where objectives were first violated and whether the system itself stopped coping.
 *
 * <p>AI interpretation is layered on afterwards, as a separate resource. It explains and
 * hypothesises. It does not produce any of the above.
 */
public final class DeterministicAnalyzer {

    private final ThresholdEvaluator thresholdEvaluator;
    private final BreakpointDetector breakpointDetector;
    private final SystemSaturationDetector saturationDetector;
    private final ThroughputCeilingDetector ceilingDetector = new ThroughputCeilingDetector();
    private final ResourceLimitDetector resourceLimitDetector = new ResourceLimitDetector();

    public DeterministicAnalyzer(ThresholdEvaluator thresholdEvaluator,
            BreakpointDetector breakpointDetector,
            SystemSaturationDetector saturationDetector) {
        this.thresholdEvaluator = Objects.requireNonNull(thresholdEvaluator);
        this.breakpointDetector = Objects.requireNonNull(breakpointDetector);
        this.saturationDetector = Objects.requireNonNull(saturationDetector);
    }

    /**
     * Analyses a run whose validity has not been assessed.
     *
     * <p>Retained for callers with no assessment to hand. The throughput ceiling is the one finding
     * that depends on it, and without one it is reported as observed rather than suppressed — which
     * is correct here, because "no assessment" is not evidence that the generator was constrained.
     */
    public DeterministicSummary analyze(EffectiveTestPlan plan, MeasuredResults results) {
        return analyze(plan, results, RunQualityAssessment.notAssessed());
    }

    public DeterministicSummary analyze(EffectiveTestPlan plan, MeasuredResults results,
            RunQualityAssessment quality) {

        ThresholdEvaluation evaluation = thresholdEvaluator.evaluate(plan.thresholds(), results);

        List<StageObservation> stages = deriveStages(plan, results);
        SloBreakpoint breakpoint = breakpointDetector.detectSloBreakpoint(stages).orElse(null);
        SystemSaturation saturation = plan.stages().size() >= 3
                ? saturationDetector.detect(stages)
                : null;

        return new DeterministicSummary(
                plan.intent().question(),
                evaluation.overall(),
                answerFor(plan, results, evaluation, breakpoint),
                results,
                evaluation,
                breakpoint,
                saturation,
                notesFor(plan, results, evaluation),
                limitsFrom(stages, breakpoint, saturation, quality));
    }

    /**
     * The four limits, each with its own evidence, and which of them came first.
     *
     * <p>Assembled here rather than in each detector so that "which was first" is decided once, from
     * all four, rather than by whichever finding happened to be rendered first.
     */
    private LimitFindings limitsFrom(List<StageObservation> stages, SloBreakpoint breakpoint,
            SystemSaturation saturation, RunQualityAssessment quality) {

        ThroughputCeiling ceiling = ceilingDetector.detect(stages, quality);
        ResourceLimitFinding resourceLimit = resourceLimitDetector.detect(stages);

        List<LimitFindings.FirstLimitingSignal> candidates = new java.util.ArrayList<>();
        if (breakpoint != null) {
            candidates.add(new LimitFindings.FirstLimitingSignal(
                    LimitFindings.LimitKind.OBJECTIVE_BREAKPOINT, breakpoint.level(),
                    breakpoint.describe(), null));
        }
        ceiling.levelIfPresent().ifPresent(level -> candidates.add(
                new LimitFindings.FirstLimitingSignal(LimitFindings.LimitKind.THROUGHPUT_CEILING,
                        level, ceiling.describe(), null)));
        resourceLimit.levelIfPresent().ifPresent(level -> candidates.add(
                new LimitFindings.FirstLimitingSignal(LimitFindings.LimitKind.RESOURCE_LIMIT, level,
                        resourceLimit.describe(), resourceLimit.kind())));
        if (saturation != null && saturation.wasObserved()) {
            saturation.lowerBoundIfPresent().ifPresent(level -> candidates.add(
                    new LimitFindings.FirstLimitingSignal(
                            LimitFindings.LimitKind.SYSTEM_SATURATION, level,
                            saturation.describe(), null)));
        }

        // Where two limits were reached at the same level, both are named: reporting one would
        // assert an ordering this run did not establish.
        double lowest = candidates.stream()
                .mapToDouble(signal -> signal.level().asDouble())
                .min()
                .orElse(Double.NaN);
        List<LimitFindings.FirstLimitingSignal> first = Double.isNaN(lowest) ? List.of()
                : candidates.stream()
                        .filter(signal -> signal.level().asDouble() == lowest)
                        .toList();

        return new LimitFindings(breakpoint, ceiling, resourceLimit, saturation, first);
    }

    /**
     * A one-line answer to the question the test asked.
     *
     * <p>The result page opens with this rather than with a chart, because "can the service sustain
     * 120 requests/sec within its objectives?" has an answer, and leading with the answer is more
     * useful than leading with the evidence for it.
     */
    private String answerFor(EffectiveTestPlan plan, MeasuredResults results,
            ThresholdEvaluation evaluation, SloBreakpoint breakpoint) {

        if (evaluation.results().isEmpty()) {
            return "No objectives were configured, so this run measured behaviour but did not "
                    + "produce a verdict.";
        }

        return switch (evaluation.overall()) {
            case PASS -> {
                String achieved = results.achievedRateIfPresent()
                        .map(RequestsPerSecond::displayWithUnit)
                        .orElse("its offered load");
                yield "The service sustained " + achieved + " and met every objective.";
            }
            case FAIL -> {
                if (breakpoint != null) {
                    yield "Objectives were first violated at "
                            + breakpoint.level().displayWithUnit()
                            + breakpoint.highestCompliantLevelIfPresent()
                            .map(level -> ", with the highest compliant level at "
                                    + level.displayWithUnit() + ".")
                            .orElse(".");
                }
                List<String> failed = evaluation.failures().stream()
                        .map(r -> r.threshold().describe() + " (observed " + r.observed() + ")")
                        .toList();
                yield "Objectives violated: " + String.join("; ", failed) + ".";
            }
            case NOT_EVALUATED -> evaluation.unevaluated().size() + " of "
                    + evaluation.results().size() + " objectives could not be checked because the "
                    + "measurements they need were not collected.";
        };
    }

    private List<String> notesFor(EffectiveTestPlan plan, MeasuredResults results,
            ThresholdEvaluation evaluation) {
        List<String> notes = new ArrayList<>();

        notes.add(plan.classification().caveat());

        LoadLevel comparisonBasis = plan.idealizedAverageArrivalRate().orElse(plan.peakLevel());
        boolean ramping = plan.stages().size() > 1;
        results.deliveredFraction(comparisonBasis).ifPresent(delivered -> {
            double shortfall = 1.0 - delivered;
            if (shortfall > 1.0 - WorkloadEvidence.SHORTFALL) {
                notes.add(ramping
                        ? "The achieved throughput was " + Math.round(shortfall * 100)
                                + "% below the load its ramp asked for, which peaked at "
                                + plan.peakLevel().displayWithUnit()
                                + ". Either the service could not absorb the offered traffic, or "
                                + "the load generator could not sustain it — check the raw output "
                                + "before treating this as a service limit."
                        : "The achieved throughput was " + Math.round(shortfall * 100)
                                + "% below the offered " + plan.peakLevel().displayWithUnit()
                                + ". Either the service could not absorb the offered traffic, or "
                                + "the load generator could not sustain it — check the raw output "
                                + "before treating this as a service limit.");
            }
        });

        if (plan.workloadModel() == com.acltabontabon.vortex.core.workload.WorkloadModel.CLOSED) {
            notes.add("This was a concurrency workload: " + plan.peakLevel().displayWithUnit()
                    + " were held, and the throughput reached is an outcome of how fast the service "
                    + "responded rather than a level that was offered to it. It is not comparable "
                    + "with an arrival-rate result at the same number.");
        }

        if (plan.stages().size() > 1 && plan.thresholds().hasOperationScopedThresholds()) {
            notes.add("The breakpoint below reflects this run's overall objectives only. "
                    + "Per-operation objectives are evaluated against each operation's own "
                    + "measurements for the run as a whole, but the time series is aggregate, so "
                    + "they cannot be placed at a particular traffic level. One operation may have "
                    + "left its target earlier than the breakpoint shown.");
        }

        if (results.hasPerOperationBreakdown() && results.perOperation().size() > 1) {
            notes.add("This run exercised " + results.perOperation().size() + " operations "
                    + "concurrently. Aggregate latency mixes them together — read the per-operation "
                    + "figures before concluding anything about a particular one.");
        }

        if (evaluation.overall() == Verdict.NOT_EVALUATED && !evaluation.unevaluated().isEmpty()) {
            notes.add("Objectives that could not be checked are reported as unevaluated rather than "
                    + "passed. An objective that was never measured has not been met.");
        }

        if (plan.thresholds().isEmpty()) {
            notes.add("This test had no objectives configured, so it cannot pass or fail. Add "
                    + "latency and error-rate thresholds to turn future runs into evidence.");
        }

        return notes;
    }

    /**
     * Reconstructs per-stage observations by mapping the time series onto the workload's stages.
     *
     * <p>Public because capacity evidence needs the same per-stage view: the highest traffic level
     * that still met every objective is derived from these observations, not from the aggregate.
     *
     * <p>Stage boundaries come from {@link StageWindows}, anchored at the first sample the run
     * actually produced rather than at the moment Vortex asked for it — a load generator takes a
     * moment to come up, and anchoring on the intent shifts every boundary by that moment. Buckets
     * are attributed to whichever stage was active when they were recorded.
     *
     * <p>Where the collector managed to cut its own samples by stage, those signals are joined on
     * here, carrying the basis on which the alignment was made. That basis is not decoration: a
     * finding resting on a boundary Vortex computed cannot claim the same strength as one resting on
     * a boundary it measured.
     */
    public List<StageObservation> deriveStages(EffectiveTestPlan plan, MeasuredResults results) {
        List<Stage> stages = plan.stages();
        if (stages.isEmpty() || results.series().isEmpty()) {
            return List.of();
        }

        List<SamplePoint> points = results.series().points();
        var start = points.getFirst().at();

        // Prefer boundaries the run actually established. k6 reports its virtual-user count, so a
        // concurrency workload's plateaus can be measured; an arrival-rate workload's cannot, and
        // falls back to the planned durations while saying so.
        List<StageWindows.StageWindow> observed =
                StageWindows.fromObservedVirtualUsers(stages, points);
        List<StageWindows.StageWindow> windows =
                observed.isEmpty() ? StageWindows.fromPlan(stages, start) : observed;

        List<StageObservation> observations = new ArrayList<>();

        for (int i = 0; i < windows.size(); i++) {
            StageWindows.StageWindow stage = windows.get(i);
            var stageStart = stage.window().start();
            var stageEnd = stage.window().end();

            List<SamplePoint> inStage = points.stream()
                    .filter(p -> !p.at().isBefore(stageStart) && p.at().isBefore(stageEnd))
                    .toList();
            if (inStage.isEmpty()) {
                continue;
            }

            StageTelemetry telemetry = results.stageTelemetry().stream()
                    .filter(candidate -> candidate.stageIndex() == stage.index())
                    .findFirst()
                    .orElse(null);

            // Absent for the first stage: k6 never ramps into it (startRate is set to its own
            // target from t=0), so there is nothing to correct rateShortfall() against.
            LoadLevel rampStartLevel = i == 0 ? null : windows.get(i - 1).target();

            // Computed once per stage and reused everywhere this stage's p95/rates are derived
            // (violatedIn() below calls the same stageP95()/stageErrorRate() methods), so the
            // reported basis can never diverge from what was actually computed.
            PercentileBasis percentileBasis = allHaveHistograms(inStage)
                    ? PercentileBasis.MERGED_HISTOGRAM
                    : PercentileBasis.LEGACY_AVERAGED_BUCKET_PERCENTILES;
            RateAggregationBasis rateBasis = allHaveRateEvidence(inStage)
                    ? RateAggregationBasis.PRESERVED_COUNTS
                    : RateAggregationBasis.LEGACY_DERIVED_BUCKET_VALUES;

            observations.add(new StageObservation(
                    stage.target(),
                    stageRequestRate(inStage).orElse(null),
                    stageP95(inStage).orElse(null),
                    stageErrorRate(inStage),
                    inStage.size(),
                    violatedIn(plan, inStage),
                    telemetry == null ? List.of() : telemetry.signals(),
                    // The collector's own basis where it has one — it may have measured boundaries
                    // this walk could only compute — and this walk's otherwise.
                    telemetry == null ? stage.basis() : telemetry.basis(),
                    telemetry == null ? List.of() : telemetry.resourceSignals(),
                    requestsIn(inStage),
                    rampStartLevel,
                    percentileBasis,
                    rateBasis));
        }

        return observations;
    }

    /** Whether every point carries a pooled latency distribution to merge. */
    private boolean allHaveHistograms(List<SamplePoint> points) {
        return points.stream().allMatch(p -> p.latencyHistogramIfPresent().isPresent());
    }

    /**
     * Whether every point carries the primitive request/failure counts needed to sum rather than
     * average — one bundle, since both fields are always added or absent together (see
     * {@link RateAggregationBasis}).
     */
    private boolean allHaveRateEvidence(List<SamplePoint> points) {
        return points.stream().allMatch(p -> p.requestCountIfPresent().isPresent()
                && p.failureCountIfPresent().isPresent());
    }

    /**
     * How many requests a stage actually carried.
     *
     * <p>Summed directly from each bucket's preserved {@code requestCount} when every point in the
     * stage has one — exact, not a reconstruction. Falls back to reconstructing from rate and bucket
     * width, approximate at the edges by exactly the width of one bucket, for a run recorded before
     * request counts were preserved.
     */
    private long requestsIn(List<SamplePoint> points) {
        if (allHaveRateEvidence(points)) {
            return points.stream().mapToLong(SamplePoint::requestCount).reduce(0L, Math::addExact);
        }
        double total = 0;
        for (SamplePoint point : points) {
            if (point.requestRate() != null) {
                total += point.requestRate().asDouble() * (point.duration().toMillis() / 1000.0);
            }
        }
        return Math.round(total);
    }

    /**
     * A stage's request rate.
     *
     * <p>When every point carries preserved counts, this is {@code sum(requestCount) /
     * sum(bucket duration in seconds)} — under today's fixed nominal bucket width this is
     * algebraically identical to the legacy arithmetic mean below, since averaging N ratios that share
     * the same constant denominator equals the sum of numerators over the sum of (equal) denominators.
     * The gain is evidence provenance (resting on preserved primitive counts, not an already-derived
     * scalar) and robustness if bucket-width semantics ever stop being uniform, not a numerical
     * correction — see {@link RateAggregationBasis}. Falls back to the unweighted average of each
     * point's own rate for a run recorded before counts were preserved.
     */
    private Optional<RequestsPerSecond> stageRequestRate(List<SamplePoint> points) {
        if (allHaveRateEvidence(points)) {
            long totalRequests = points.stream()
                    .mapToLong(SamplePoint::requestCount)
                    .reduce(0L, Math::addExact);
            long totalMillis = points.stream()
                    .mapToLong(p -> p.duration().toMillis())
                    .reduce(0L, Math::addExact);
            double seconds = totalMillis / 1000.0;
            if (seconds <= 0) {
                return Optional.empty();
            }
            double rate = totalRequests / seconds;
            return rate <= 0 ? Optional.empty() : Optional.of(RequestsPerSecond.of(rate));
        }
        double[] values = points.stream()
                .map(SamplePoint::requestRateIfPresent)
                .filter(Optional::isPresent)
                .mapToDouble(rate -> rate.get().asDouble())
                .toArray();
        if (values.length == 0) {
            return Optional.empty();
        }
        double mean = Arrays.stream(values).average().orElse(0);
        return mean <= 0 ? Optional.empty() : Optional.of(RequestsPerSecond.of(mean));
    }

    /**
     * A stage's p95, pooled from every bucket's own latency distribution rather than averaged.
     *
     * <p>Averaging each bucket's own p95 is not the pooled p95 of anything — percentiles are not
     * composable through arithmetic averaging, and the error is not reliably directional. Merging
     * histograms is exact for the already-quantized distribution each one encodes; only the
     * {@link LatencyHistogram} bin quantization itself is approximate (see its own numerical
     * guarantee), not this rollup.
     */
    private Optional<Duration> stageP95(List<SamplePoint> points) {
        if (allHaveHistograms(points)) {
            LatencyHistogram pooled = LatencyHistogram.merge(points.stream()
                    .map(p -> p.latencyHistogramIfPresent().get())
                    .toList());
            return pooled.percentile(0.95);
        }
        // LEGACY FALLBACK — permanent for runs recorded before this fix, whose raw distribution no
        // longer exists. Averaging bucket p95s is not the pooled percentile, and is not reliably
        // above or below it either — it is simply the only thing this data can still produce. Never
        // "improved" in place; every run analyzed after this fix ships takes the branch above.
        long[] nanos = points.stream()
                .map(SamplePoint::p95IfPresent)
                .filter(Optional::isPresent)
                .mapToLong(p95 -> p95.get().toNanos())
                .toArray();
        if (nanos.length == 0) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofNanos((long) Arrays.stream(nanos).average().orElse(0)));
    }

    /**
     * A stage's error rate.
     *
     * <p>When every point carries preserved counts, this is {@code sum(failures) / sum(requests)} —
     * a genuine correctness fix over averaging each bucket's own error fraction unweighted, which is
     * wrong whenever bucket request volume differs (unlike bucket width, which is constant). Falls
     * back to the unweighted average for a run recorded before counts were preserved.
     */
    private ErrorRate stageErrorRate(List<SamplePoint> points) {
        if (allHaveRateEvidence(points)) {
            long totalRequests = points.stream()
                    .mapToLong(SamplePoint::requestCount)
                    .reduce(0L, Math::addExact);
            long totalFailures = points.stream()
                    .mapToLong(SamplePoint::failureCount)
                    .reduce(0L, Math::addExact);
            return totalRequests <= 0 ? ErrorRate.ZERO : ErrorRate.of(totalFailures, totalRequests);
        }
        double mean = points.stream()
                .mapToDouble(p -> p.errorRate().asFraction())
                .average()
                .orElse(0);
        return ErrorRate.ofFraction(mean);
    }

    /**
     * Which objectives were violated during a stage.
     *
     * <p>Only latency and error-rate objectives can be evaluated per stage: those are the ones the
     * time series carries. This is why a stage-level view is possible at all, and why breakpoint
     * detection is limited to those two dimensions.
     *
     * <p>Only <em>overall</em> objectives, at that. The time series is aggregate — a bucket carries
     * one p95 across every operation in the mix, with no per-operation breakdown — so an objective
     * scoped to one operation has nothing here to be evaluated against. Judging it against the
     * aggregate would compare a tight objective written for a cheap lookup against latency that is
     * mostly somebody else's, and report a breakpoint at a level where that operation may have been
     * comfortably inside its target. A confident wrong number is worse than an absent one, so a
     * scoped objective simply does not participate: it is still evaluated exactly once, against its
     * own operation's measurements, by {@link ThresholdEvaluator}.
     */
    private List<String> violatedIn(EffectiveTestPlan plan, List<SamplePoint> points) {
        List<String> violated = new ArrayList<>();

        Optional<Duration> p95 = stageP95(points);
        for (Threshold threshold : plan.thresholds().overall()) {
            if (threshold instanceof LatencyThreshold latency
                    && latency.percentile().equals(com.acltabontabon.vortex.core.shared.Percentile.P95)
                    && p95.isPresent()
                    && p95.get().compareTo(latency.maximum()) > 0) {
                violated.add(latency.id());
            }
        }

        ErrorRate errorRate = stageErrorRate(points);
        plan.thresholds().errorRateThreshold().ifPresent(threshold -> {
            if (errorRate.compareTo(threshold.maximum()) > 0) {
                violated.add(threshold.id());
            }
        });

        return violated;
    }

    /** Human summary of a stage, used in reports and AI context. */
    public static String describeStage(StageObservation stage) {
        return stage.targetLoad().displayWithUnit() + ": "
                + stage.p95IfPresent().map(Durations::display).orElse("p95 unavailable")
                + ", errors " + stage.errorRate().display()
                + (stage.isCompliant() ? ", compliant" : ", objectives violated");
    }
}
