package com.acltabontabon.vortex.core.application;

import com.acltabontabon.vortex.core.analysis.DeterministicSummary;
import com.acltabontabon.vortex.core.analysis.EvidenceIds;
import com.acltabontabon.vortex.core.analysis.StageObservation;
import com.acltabontabon.vortex.core.metrics.MeasuredResults;
import com.acltabontabon.vortex.core.metrics.MetricObservation;
import com.acltabontabon.vortex.core.metrics.OperationMetrics;
import com.acltabontabon.vortex.core.metrics.TelemetryGap;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.plan.PlannedOperation;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.shared.Percentile;
import com.acltabontabon.vortex.core.threshold.Durations;
import com.acltabontabon.vortex.core.threshold.ThresholdResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Assembles the small, structured evidence package the AI assistant reasons over.
 *
 * <p>The accessor methods below — throughput, latency percentiles, error distribution, threshold
 * results, breakpoints, resource utilisation — are shaped as individual, size-bounded queries on
 * purpose. In this release they are called directly to build a context object, which is reliable
 * with small local models. The same shape becomes tool definitions when function calling is worth
 * enabling, without the surrounding design changing.
 *
 * <p>What this class will not do is grow into an agent. There is no loop, no memory, no vector
 * store and no retrieval layer, because none of those solve the problem at hand: explaining one
 * run's already-calculated results.
 */
public final class EvidenceAssembler {

    public AnalysisContext assemble(EffectiveTestPlan plan, DeterministicSummary summary,
            List<StageObservation> stages) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(summary, "summary");

        return new AnalysisContext(
                plan.testType().name(),
                summary.question(),
                summary.verdict().name(),
                plan.classification().label(),
                getTrafficSummary(plan),
                getWorkloadConfiguration(plan),
                getMeasurements(summary.results()),
                getThresholdResults(summary),
                getStageObservations(stages),
                getCapacityBreakpoints(summary, stages),
                getOperationSummary(plan, summary.results()),
                summary.availableEvidenceIds(),
                getAbsentTelemetry(summary));
    }

    List<String> getTrafficSummary(EffectiveTestPlan plan) {
        return plan.operations().stream()
                .map(operation -> operation.name() + ": " + operation.sharePercent() + "% of traffic"
                        + operation.arrivalRateIfPresent()
                        .map(rate -> " at " + rate.displayWithUnit())
                        .orElse(""))
                .toList();
    }

    Map<String, String> getWorkloadConfiguration(EffectiveTestPlan plan) {
        Map<String, String> workload = new LinkedHashMap<>();
        // Stated as model plus level rather than as a bare number. "50" means two different things
        // under the two models, and an interpretation built on the wrong one would be confidently
        // wrong in a way that reads as insight.
        workload.put("workloadModel", plan.workloadModel().label());
        workload.put("offeredLevel", plan.peakLevel().displayWithUnit());
        workload.put("duration", Durations.display(plan.totalDuration()));
        workload.put("stageCount", String.valueOf(plan.stages().size()));
        if (plan.stages().size() > 1) {
            workload.put("rampFrom", plan.stages().getFirst().target().displayWithUnit());
            workload.put("rampTo", plan.stages().getLast().target().displayWithUnit());
        }
        workload.put("operationCount", String.valueOf(plan.operations().size()));
        workload.put("workloadSource", plan.workloadSource().describe());
        workload.put("dependencies", plan.dependencyMode().label());
        return workload;
    }

    /**
     * The headline measurements, keyed by the identifier a finding must cite.
     *
     * <p>Every entry carries its unit explicitly. A bare number that turns out to have been virtual
     * users rather than requests per second is the easiest way for an interpretation to become
     * quietly wrong.
     *
     * <p>Built by {@link com.acltabontabon.vortex.core.analysis.EvidenceIds}, which is also what the validator
     * checks citations against — so the assistant is never shown a measurement it is then told it
     * may not refer to.
     */
    Map<String, String> getMeasurements(MeasuredResults results) {
        return com.acltabontabon.vortex.core.analysis.EvidenceIds.measurements(results);
    }

    List<String> getThresholdResults(DeterministicSummary summary) {
        return summary.thresholds().results().stream()
                .map(this::describeThreshold)
                .toList();
    }

    private String describeThreshold(ThresholdResult result) {
        return "threshold:" + result.thresholdId() + " — " + result.threshold().describe()
                + " → " + result.verdict().label()
                + (result.observed().isBlank() ? "" : " (observed " + result.observed() + ")");
    }

    List<String> getStageObservations(List<StageObservation> stages) {
        if (stages == null || stages.isEmpty()) {
            return List.of();
        }
        List<String> lines = stages.stream()
                .map(DeterministicAnalyzer::describeStage)
                .toList();
        if (lines.size() <= AnalysisContext.MAX_STAGE_LINES) {
            return lines;
        }
        List<String> trimmed = new ArrayList<>(lines.subList(0, AnalysisContext.MAX_STAGE_LINES));
        trimmed.add("(" + (lines.size() - AnalysisContext.MAX_STAGE_LINES)
                + " further stages omitted to keep this evidence package bounded)");
        return trimmed;
    }

    /**
     * What was established about limits, including — importantly — what was not.
     *
     * <p>Stating "system saturation: not established" explicitly matters. Left out, a model tends to
     * fill the silence with a plausible number.
     */
    Map<String, String> getCapacityBreakpoints(DeterministicSummary summary,
            List<StageObservation> stages) {
        Map<String, String> breakpoints = new LinkedHashMap<>();

        summary.sloBreakpointIfPresent().ifPresentOrElse(
                breakpoint -> {
                    breakpoints.put("sloBreakpoint", breakpoint.level().displayWithUnit());
                    breakpoints.put("sloBreakpointEvidence", breakpoint.strength().label());
                    breakpoint.highestCompliantLevelIfPresent().ifPresent(level ->
                            breakpoints.put("highestCompliantLevel", level.displayWithUnit()));
                    // Named explicitly so the model can reason about before/at/after this stage
                    // rather than re-deriving — or worse, restating — where the breakpoint is.
                    indexOfFirstViolation(stages).ifPresent(index ->
                            breakpoints.put("sloBreakpointStageIndex",
                                    "stage " + (index + 1) + " of " + stages.size()));
                },
                () -> breakpoints.put("sloBreakpoint",
                        "not reached — the service met its objectives at every level tested"));

        summary.systemSaturationIfPresent().ifPresentOrElse(
                saturation -> {
                    breakpoints.put("systemSaturation", saturation.describe());
                    breakpoints.put("systemSaturationEvidence", saturation.strength().label());
                    if (!saturation.signals().isEmpty()) {
                        breakpoints.put("saturationSignals", String.join("; ", saturation.signals()));
                    }
                },
                () -> breakpoints.put("systemSaturation",
                        "not established — this workload did not produce enough distinct traffic "
                                + "levels to tell"));

        return breakpoints;
    }

    private Optional<Integer> indexOfFirstViolation(List<StageObservation> stages) {
        for (int i = 0; i < stages.size(); i++) {
            if (!stages.get(i).isCompliant()) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    /**
     * Per-operation traffic share, throughput, latency and errors, ranked by traffic share.
     *
     * <p>A mixed run's aggregate figures describe none of its operations well, and the operation
     * that degraded is often not the one carrying the most traffic. This gives the model a
     * pre-ranked, pre-computed structure instead of leaving it to spot the pattern across a flat
     * list of per-operation measurement entries — and embeds each line's evidence ids inline, since
     * that is the block most likely to drive an operation-level finding.
     */
    List<String> getOperationSummary(EffectiveTestPlan plan, MeasuredResults results) {
        List<PlannedOperation> ranked = plan.operations().stream()
                .sorted(Comparator.comparing(PlannedOperation::share).reversed())
                .toList();

        List<String> lines = new ArrayList<>();
        for (PlannedOperation operation : ranked) {
            OperationId id = operation.operationId();
            OperationMetrics metrics = results.perOperation().get(id);

            StringBuilder line = new StringBuilder(operation.name())
                    .append(": ").append(operation.sharePercent()).append("% of traffic");

            if (metrics == null) {
                lines.add(line.append(", no measurements recorded for this operation").toString());
                continue;
            }

            List<String> ids = new ArrayList<>();
            metrics.achievedRateIfPresent().ifPresent(rate -> {
                line.append(", ").append(rate.displayWithUnit());
                ids.add(EvidenceIds.operationRate(id));
            });
            metrics.latency().at(Percentile.P95).ifPresent(p95 -> {
                line.append(", p95 ").append(Durations.display(p95));
                ids.add(EvidenceIds.operationLatency(id, Percentile.P95));
            });
            line.append(", errors ").append(metrics.errorRate().display());
            ids.add(EvidenceIds.operationErrorRate(id));

            line.append(" [").append(String.join(", ", ids)).append(']');
            lines.add(line.toString());
        }
        return lines;
    }

    /**
     * Measurements that were not collected, so their absence is explicit rather than inferred.
     *
     * <p>Two sources are consulted, and used to be entirely separate: {@link
     * MeasuredResults#telemetryGaps()} carries a precise, provider-reported cause for a signal that
     * was asked for and not obtained (see {@code TelemetryAvailability}); the category checks below
     * catch what nobody even asked for. A category already covered by a gap or an observation is not
     * repeated as a generic line — the specific reason is more useful than a duplicate vague one.
     *
     * <p>Categories are limited to what some collector in this codebase can actually supply. GC
     * pause/count and container (cgroup) throttling are deliberately not among them: nothing here
     * collects either today, and listing them as "absent" would fabricate a category rather than
     * report a real gap. That is a known limitation, not an oversight — see {@code
     * docs/02-architecture/execution-and-evidence.adoc} (Evidence model).
     */
    List<String> getAbsentTelemetry(DeterministicSummary summary) {
        MeasuredResults results = summary.results();
        List<TelemetryGap> gaps = results.telemetryGaps();

        if (results.observations().isEmpty() && gaps.isEmpty()) {
            return List.of("No telemetry was collected from the service under test. CPU, memory, "
                    + "JVM, connection-pool and database measurements are all unavailable for this "
                    + "run.");
        }

        List<String> absent = new ArrayList<>();
        for (TelemetryGap gap : gaps) {
            absent.add(gap.describe());
        }

        List<String> covered = new ArrayList<>();
        results.observations().stream().map(MetricObservation::id)
                .map(id -> id.toLowerCase(java.util.Locale.ROOT))
                .forEach(covered::add);
        gaps.stream().map(TelemetryGap::metricName)
                .map(name -> name.toLowerCase(java.util.Locale.ROOT))
                .forEach(covered::add);

        checkCategory(covered, absent, "CPU utilisation was not collected.", "cpu");
        checkCategory(covered, absent, "Memory and heap utilisation were not collected.",
                "memory", "heap");
        checkCategory(covered, absent, "Connection-pool utilisation was not collected.",
                "pool", "connection");
        checkCategory(covered, absent,
                "Queue depth (pending connections / executor backlog) was not collected.",
                "pending", "queued");
        checkCategory(covered, absent, "Downstream dependency latency was not collected.",
                "dependency.latency");
        checkCategory(covered, absent, "Downstream dependency error rate was not collected.",
                "dependency.error");
        checkCategory(covered, absent, "JVM thread-pool occupancy was not collected.", "threads");

        return absent;
    }

    private void checkCategory(List<String> covered, List<String> absent, String message,
            String... keywords) {
        boolean present = covered.stream()
                .anyMatch(name -> java.util.Arrays.stream(keywords).anyMatch(name::contains));
        if (!present) {
            absent.add(message);
        }
    }
}
