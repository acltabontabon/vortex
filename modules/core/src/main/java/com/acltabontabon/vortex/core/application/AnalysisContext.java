package com.acltabontabon.vortex.core.application;

import java.util.List;
import java.util.Map;

/**
 * The bounded evidence package handed to the AI assistant.
 *
 * <p>Deliberately small. The temptation is to send the model everything — thirty megabytes of
 * engine output, every sample, every log line — on the theory that more context yields better
 * answers. In practice it does the opposite: it buries the signal, costs far more, and makes the
 * whole feature unusable with the modestly-sized local models Vortex is designed around.
 *
 * <p>So the model receives measurements that have <em>already been calculated</em>, each with a
 * stable evidence identifier it must cite. Everything here is a fact Vortex computed
 * deterministically; the model's job is to explain what the facts might mean, not to derive them.
 *
 * @param testKind        the kind of test, e.g. {@code STRESS}
 * @param question        what the run set out to learn
 * @param verdict         the deterministic outcome — already decided, not up for debate
 * @param classification  whether this run could answer integrated questions
 * @param trafficSummary  operations and their shares
 * @param workload        target rates and stages
 * @param measurements    the headline numbers, keyed by evidence identifier
 * @param thresholdResults objective-by-objective outcomes
 * @param stageObservations per-stage behaviour, for ramping workloads
 * @param breakpoints     what was and was not established about limits
 * @param operationSummary per-operation traffic share, throughput, latency and errors, ranked by
 *                        traffic share so the model reads about the operation carrying most of the
 *                        run before the one carrying least
 * @param availableEvidenceIds every identifier a finding is permitted to cite
 * @param absentTelemetry measurements that were not collected, stated up front so the model does
 *                        not have to guess whether their absence is meaningful
 */
public record AnalysisContext(
        String testKind,
        String question,
        String verdict,
        String classification,
        List<String> trafficSummary,
        Map<String, String> workload,
        Map<String, String> measurements,
        List<String> thresholdResults,
        List<String> stageObservations,
        Map<String, String> breakpoints,
        List<String> operationSummary,
        List<String> availableEvidenceIds,
        List<String> absentTelemetry) {

    /** Hard ceiling on stage lines, so a long soak test cannot produce an unbounded prompt. */
    public static final int MAX_STAGE_LINES = 30;

    public AnalysisContext {
        trafficSummary = trafficSummary == null ? List.of() : List.copyOf(trafficSummary);
        workload = workload == null ? Map.of() : Map.copyOf(workload);
        measurements = measurements == null ? Map.of() : Map.copyOf(measurements);
        thresholdResults = thresholdResults == null ? List.of() : List.copyOf(thresholdResults);
        stageObservations = stageObservations == null ? List.of() : List.copyOf(stageObservations);
        breakpoints = breakpoints == null ? Map.of() : Map.copyOf(breakpoints);
        operationSummary = operationSummary == null ? List.of() : List.copyOf(operationSummary);
        availableEvidenceIds =
                availableEvidenceIds == null ? List.of() : List.copyOf(availableEvidenceIds);
        absentTelemetry = absentTelemetry == null ? List.of() : List.copyOf(absentTelemetry);
    }
}
