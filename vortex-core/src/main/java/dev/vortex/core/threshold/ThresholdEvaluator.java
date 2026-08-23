package dev.vortex.core.threshold;

import dev.vortex.core.metrics.LatencyPercentiles;
import dev.vortex.core.metrics.MeasuredResults;
import dev.vortex.core.metrics.OperationMetrics;
import dev.vortex.core.shared.ErrorRate;
import dev.vortex.core.shared.OperationId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns measurements into a pass or a fail.
 *
 * <p>This class is the reason Vortex can claim its verdicts are trustworthy. It is pure: the same
 * measurements and the same thresholds always produce the same result, on any machine, with or
 * without an AI model available. Nothing here consults a language model, and nothing downstream is
 * permitted to overrule it.
 *
 * <p>A threshold whose measurement is missing is reported as {@link Verdict#NOT_EVALUATED} rather
 * than quietly passing. Silently passing an unchecked objective is the failure mode that makes a
 * green performance report worse than no report at all. That applies just as strictly to
 * operation-scoped objectives: an operation that issued no requests has not met its latency
 * objective, it has simply never been asked.
 */
public final class ThresholdEvaluator {

    public ThresholdEvaluation evaluate(ThresholdSet thresholds, MeasuredResults results) {
        List<ThresholdResult> evaluated = new ArrayList<>();
        for (Threshold threshold : thresholds.thresholds()) {
            evaluated.add(evaluateOne(threshold, results));
        }
        return new ThresholdEvaluation(evaluated);
    }

    private ThresholdResult evaluateOne(Threshold threshold, MeasuredResults results) {
        Optional<OperationId> scoped = threshold.scope().operationIfPresent();
        if (scoped.isEmpty()) {
            return switch (threshold) {
                case LatencyThreshold latency ->
                        evaluateLatency(latency, results.latency(), "this run");
                case ErrorRateThreshold errors ->
                        evaluateErrorRate(errors, results.requests(), results.errorRate(), "this run");
            };
        }

        OperationId operation = scoped.get();
        Optional<OperationMetrics> metrics = results.forOperation(operation);
        if (metrics.isEmpty()) {
            return ThresholdResult.notEvaluated(threshold, results.hasPerOperationBreakdown()
                    ? "No measurements were recorded for " + operation.value() + ", so this objective "
                    + "could not be checked. Confirm the operation is part of the workload's mix."
                    : "This run reported no per-operation breakdown, so an objective scoped to "
                    + operation.value() + " could not be checked.");
        }
        OperationMetrics operationMetrics = metrics.get();
        if (!operationMetrics.hasTraffic()) {
            return ThresholdResult.notEvaluated(threshold,
                    operation.value() + " issued no requests during this run, so this objective could "
                            + "not be checked.");
        }
        return switch (threshold) {
            case LatencyThreshold latency ->
                    evaluateLatency(latency, operationMetrics.latency(), operation.value());
            case ErrorRateThreshold errors -> evaluateErrorRate(errors, operationMetrics.requests(),
                    operationMetrics.errorRate(), operation.value());
        };
    }

    private ThresholdResult evaluateLatency(LatencyThreshold threshold, LatencyPercentiles latency,
            String subject) {
        Optional<Duration> observed = latency.at(threshold.percentile());
        if (observed.isEmpty()) {
            return ThresholdResult.notEvaluated(threshold,
                    threshold.percentile().label() + " latency was not reported for " + subject
                            + ", so this objective could not be checked.");
        }
        Duration value = observed.get();
        String display = Durations.display(value);
        Double position = threshold.maximum().isZero()
                ? null : value.toNanos() / (double) threshold.maximum().toNanos();
        return value.compareTo(threshold.maximum()) <= 0
                ? ThresholdResult.pass(threshold, display, position)
                : ThresholdResult.fail(threshold, display, position);
    }

    private ThresholdResult evaluateErrorRate(ErrorRateThreshold threshold, long requests,
            ErrorRate observed, String subject) {
        if (requests == 0) {
            return ThresholdResult.notEvaluated(threshold,
                    "No requests were recorded for " + subject + ", so an error rate could not be "
                            + "calculated.");
        }
        double maximumFraction = threshold.maximum().fraction().doubleValue();
        Double position = maximumFraction == 0
                ? null : observed.fraction().doubleValue() / maximumFraction;
        return observed.compareTo(threshold.maximum()) <= 0
                ? ThresholdResult.pass(threshold, observed.display(), position)
                : ThresholdResult.fail(threshold, observed.display(), position);
    }
}
