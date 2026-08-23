package dev.vortex.core.application;

import dev.vortex.core.analysis.Analysis;
import dev.vortex.core.analysis.DeterministicSummary;
import dev.vortex.core.analysis.MissingTelemetry;
import dev.vortex.core.analysis.StageObservation;

import dev.vortex.core.comparison.ExecutionComparison;
import dev.vortex.core.comparison.RegressionEvaluator;
import dev.vortex.core.comparison.RegressionVerdict;
import dev.vortex.core.evidence.AcceptanceEvidence;
import dev.vortex.core.evidence.ComparisonEvidence;
import dev.vortex.core.evidence.DeterministicFinding;
import dev.vortex.core.evidence.EvidenceContext;
import dev.vortex.core.evidence.EvidenceProvenance;
import dev.vortex.core.evidence.EvidenceSanitizer;
import dev.vortex.core.evidence.FindingDetector;
import dev.vortex.core.evidence.Interpretation;
import dev.vortex.core.evidence.ObservabilityEvidence;
import dev.vortex.core.evidence.ObservedSignal;
import dev.vortex.core.data.RequestValueOrigin;
import dev.vortex.core.evidence.OperationEvidence;
import dev.vortex.core.evidence.PerformanceEvidence;
import dev.vortex.core.evidence.ResourceTimelineEvidence;
import dev.vortex.core.evidence.RunEvidence;
import dev.vortex.core.evidence.RunIdentity;
import dev.vortex.core.evidence.SeriesPlot;
import dev.vortex.core.evidence.TimelineEvidence;
import dev.vortex.core.evidence.WorkloadEvidence;
import dev.vortex.core.execution.ExecutionState;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.metrics.MeasuredResults;
import dev.vortex.core.metrics.MetricObservation;
import dev.vortex.core.metrics.MetricSeries;
import dev.vortex.core.metrics.OperationMetrics;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.PlannedOperation;
import dev.vortex.core.evidence.TelemetryCoverage;
import dev.vortex.core.port.HostInformation;
import dev.vortex.core.port.Clock;
import dev.vortex.core.resource.ResourceKind;
import dev.vortex.core.resource.ResourceSample;
import dev.vortex.core.resource.ResourceSeriesProjection;
import dev.vortex.core.resource.ResourceSignal;
import dev.vortex.core.resource.ResourceTelemetryReader;
import dev.vortex.core.shared.Percentile;
import dev.vortex.core.threshold.LatencyThreshold;
import dev.vortex.core.threshold.Threshold;
import dev.vortex.core.threshold.ThresholdResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Assembles the presentation-neutral evidence for one completed run.
 *
 * <p>The one place a {@link RunEvidence} is built, which is what makes the sanitiser unavoidable and
 * what stops the result page, the printable report and the three exports from each deciding
 * independently what a section means.
 *
 * <p>Distinct from {@link EvidenceAssembler}, which builds the small context object the language
 * model reasons over. The two look adjacent and are not: that one selects a size-bounded subset for
 * a prompt, this one produces the complete document model for a reader. Merging them would mean
 * either sending a whole report to a local model or publishing only what a prompt happened to need.
 */
public final class RunEvidenceService {

    private final DeterministicAnalyzer analyzer;
    private final FindingDetector findingDetector;
    private final EvidenceSanitizer sanitizer;
    private final RegressionEvaluator regressions;
    private final Clock clock;

    private final HostInformation host;
    private final ResourceTelemetryReader resourceTelemetryReader;

    /** One definition of sustainable, shared with the capacity service that stores it. */
    private final dev.vortex.core.capacity.SustainableCapacityCalculator sustainableCapacity =
            new dev.vortex.core.capacity.SustainableCapacityCalculator();

    /**
     * Assembles evidence without recording which machine produced it.
     *
     * <p>Kept for callers and fixtures that have no host to describe. The host then reports itself
     * as unknown, which is honest - and visible, so a reader can tell an unrecorded machine from an
     * unremarkable one.
     */
    public RunEvidenceService(DeterministicAnalyzer analyzer, FindingDetector findingDetector,
            EvidenceSanitizer sanitizer, RegressionEvaluator regressions, Clock clock) {
        this(analyzer, findingDetector, sanitizer, regressions, clock, HostInformation.unknown(),
                ResourceTelemetryReader.unavailable());
    }

    public RunEvidenceService(DeterministicAnalyzer analyzer, FindingDetector findingDetector,
            EvidenceSanitizer sanitizer, RegressionEvaluator regressions, Clock clock,
            HostInformation host) {
        this(analyzer, findingDetector, sanitizer, regressions, clock, host,
                ResourceTelemetryReader.unavailable());
    }

    public RunEvidenceService(DeterministicAnalyzer analyzer, FindingDetector findingDetector,
            EvidenceSanitizer sanitizer, RegressionEvaluator regressions, Clock clock,
            HostInformation host, ResourceTelemetryReader resourceTelemetryReader) {
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.findingDetector = Objects.requireNonNull(findingDetector, "findingDetector");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        this.regressions = Objects.requireNonNull(regressions, "regressions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.host = Objects.requireNonNull(host, "host");
        this.resourceTelemetryReader =
                Objects.requireNonNull(resourceTelemetryReader, "resourceTelemetryReader");
    }

    public RunEvidence assemble(TestExecution execution, String artifactDirectory,
            List<String> artifactNames) {
        return assemble(execution, null, null, EvidenceContext.none(), artifactDirectory,
                artifactNames);
    }

    /**
     * Builds the evidence for a completed run.
     *
     * @param baseline an earlier run of the same experiment, or null
     * @param analysis the AI interpretation, or null when none was produced
     * @throws IllegalStateException when the run did not complete — a report of an incomplete run
     *                               would be a document about nothing
     */
    public RunEvidence assemble(TestExecution execution, Analysis analysis, TestExecution baseline,
            EvidenceContext context, String artifactDirectory, List<String> artifactNames) {

        // Never null. Headroom used to be a parameter every caller passed null for, which is how a
        // correctly calculated figure came to be displayed nowhere for months.
        context = context == null ? EvidenceContext.none() : context;

        Objects.requireNonNull(execution, "execution");
        if (execution.state() != ExecutionState.COMPLETED || execution.summaryIfPresent().isEmpty()) {
            throw new IllegalStateException(
                    "Evidence can only be assembled for a completed run. Execution "
                            + execution.id().value() + " is " + execution.state()
                            + ", so there are no settled measurements to report.");
        }

        EffectiveTestPlan plan = execution.plan();
        DeterministicSummary summary = execution.summary();
        MeasuredResults results = summary.results();

        RunIdentity identity = identity(execution, plan);
        WorkloadEvidence workload = workload(plan, results);
        PerformanceEvidence performance = new PerformanceEvidence(
                results, summary.sloBreakpoint(), summary.systemSaturation(),
                context.headroom().value().orElse(null),
                context.headroom().reason().orElse(""),
                context.production());
        AcceptanceEvidence acceptance = AcceptanceEvidence.of(summary.thresholds());
        List<OperationEvidence> operations = operations(plan, results, acceptance);
        ObservabilityEvidence observability = observability(results);
        TimelineEvidence timeline = timeline(plan, results, summary);
        ResourceTimelineEvidence resourceTimeline = resourceTimeline(execution, results, timeline);

        // The stage view reaches the detector so a resource crossing can be placed at a level of
        // load rather than merely somewhere in the run — and so the detector can see on what basis
        // that placement was made.
        List<DeterministicFinding> findings = findingDetector.detect(
                identity, workload, performance, acceptance, operations, observability,
                timeline.stages());

        return new RunEvidence(
                identity,
                sanitizer.text(summary.question()),
                summary.verdict(),
                sanitizer.text(summary.answer()),
                workload,
                performance,
                acceptance,
                operations,
                timeline,
                observability,
                resourceTimeline,
                findings,
                // Every validity finding's sentence joins the qualifications a reader sees beside
                // the result. An invalid run is not quieter than a valid one — it says more, because
                // it has to say why a number is missing.
                sanitizer.lines(withQualifications(summary.notes(), execution)),
                comparison(execution, baseline),
                Interpretation.from(analysis).orElse(null),
                provenance(execution, plan, results, observability, artifactDirectory,
                        artifactNames),
                execution.quality(),
                summary.limits(),
                // Recomputed here rather than read from a stored capacity observation: an invalid
                // run records none, and its result page still has to show which of the five
                // conditions refused it.
                sustainableCapacity.calculate(plan, timeline.stages(), execution.quality(),
                        summary.limits()));
    }

    /**
     * The summary's own notes, followed by anything the validity assessment has to say.
     *
     * <p>Appended rather than merged into a separate section so that a reader who scans only the
     * caveats sees "this run never generated the load it asked for" alongside "dependencies were
     * mocked" — both are reasons to read the number below them differently.
     */
    private List<String> withQualifications(List<String> notes, TestExecution execution) {
        List<String> all = new java.util.ArrayList<>(notes == null ? List.of() : notes);
        all.addAll(execution.quality().qualifications());
        return all;
    }

    // ------------------------------------------------------------------ identity

    private RunIdentity identity(TestExecution execution, EffectiveTestPlan plan) {
        return new RunIdentity(
                execution.id(),
                execution.projectId(),
                sanitizer.text(plan.projectName()),
                sanitizer.text(plan.serviceVersion()),
                sanitizer.text(plan.workloadName()),
                sanitizer.text(plan.workloadDescription()),
                plan.testType(),
                sanitizer.text(plan.environmentName()),
                plan.environmentType(),
                plan.classification(),
                plan.dependencyMode(),
                sanitizer.url(plan.effectiveTarget().value()),
                sanitizer.text(plan.targetRewriteReason()),
                plan.fingerprint(),
                execution.requestedAt(),
                execution.startedAt(),
                execution.finishedAt(),
                execution.duration().orElse(null));
    }

    // ------------------------------------------------------------------ workload

    private WorkloadEvidence workload(EffectiveTestPlan plan, MeasuredResults results) {
        // A closed workload has no shortfall to report: its throughput is an outcome of the
        // service's own latency, not a target that could have been missed.
        String caveat = plan.workloadModel().isOpen()
                ? ""
                : "An outcome, not a target — the virtual users went as fast as the service "
                        + "allowed.";

        return new WorkloadEvidence(
                plan.workloadModel(),
                plan.peakLevel(),
                results.achievedRate(),
                results.deliveredFraction().orElse(null),
                caveat,
                plan.workloadSource(),
                plan.totalDuration(),
                results.duration(),
                plan.stages(),
                sanitizer.lines(plan.operationMixSummary()),
                plan.estimatedRequests().orElse(null),
                sanitizer.text(plan.requestEstimateCaveat()),
                results.requests(),
                results.failures(),
                plan.scriptSource(),
                sanitizer.options(plan.k6Options()),
                sanitizer.headers(plan.headers()));
    }

    // ------------------------------------------------------------------ operations

    private List<OperationEvidence> operations(EffectiveTestPlan plan, MeasuredResults results,
            AcceptanceEvidence acceptance) {

        List<OperationEvidence> operations = new ArrayList<>();
        for (PlannedOperation planned : plan.operations()) {
            OperationMetrics metrics = results.perOperation().get(planned.operationId());
            operations.add(new OperationEvidence(
                    planned.operationId(),
                    sanitizer.text(planned.name()),
                    planned.method() + " " + sanitizer.text(planned.pathTemplate()),
                    planned.share(),
                    planned.provenance(),
                    RequestValueOrigin.of(planned.requestData(), sanitizer::text),
                    metrics,
                    scopedResults(acceptance, planned)));
        }
        return List.copyOf(operations);
    }

    /** The objectives that named this operation, so a per-operation verdict is joined once. */
    private List<ThresholdResult> scopedResults(AcceptanceEvidence acceptance,
            PlannedOperation planned) {
        return acceptance.results().stream()
                .filter(result -> result.threshold().scope().operationIfPresent()
                        .filter(planned.operationId()::equals).isPresent())
                .toList();
    }

    // ------------------------------------------------------------------ observability

    private ObservabilityEvidence observability(MeasuredResults results) {
        // Classification, where a provider supplied it, keyed by the observation it belongs to.
        // Every observation is rendered either way; carrying the classification alongside is what
        // lets a finding say "this resource reached its limit" for the ones that declared a limit,
        // and prevents it for the ones that did not.
        var classified = results.resourceSignals().stream()
                .collect(java.util.stream.Collectors.toMap(
                        signal -> signal.signalId(), signal -> signal, (first, second) -> first,
                        java.util.LinkedHashMap::new));

        List<ObservedSignal> signals = results.observations().stream()
                .map(observation -> {
                    var resource = classified.get(observation.id());
                    return resource == null
                            ? ObservedSignal.of(observation)
                            : new ObservedSignal(observation, resource);
                })
                .toList();

        // Every provider that was asked, whether or not it answered. A provider omitted because it
        // failed would leave the reader thinking nobody had looked there.
        java.util.SequencedSet<String> providers = new java.util.LinkedHashSet<>(
                results.observations().stream()
                        .map(observation -> observation.source().label())
                        .toList());
        results.telemetryGaps().stream()
                .map(gap -> gap.providerId())
                .filter(id -> !id.isBlank())
                .forEach(providers::add);

        return new ObservabilityEvidence(signals, List.copyOf(providers), gaps(results));
    }

    /**
     * Missing telemetry, each entry carrying the cause rather than only the absence.
     *
     * <p>"JVM memory telemetry unavailable" is not something an engineer can act on. "It was not
     * observed because the query returned no matching series" sends them to their label selector;
     * "because the token was refused" sends them to a permission. Collapsing the two wastes both
     * afternoons, so the reason survives all the way into the report.
     */
    private List<MissingTelemetry> gaps(MeasuredResults results) {
        return results.telemetryGaps().stream()
                .map(gap -> new MissingTelemetry(
                        sanitizer.text(gap.describe()),
                        "Without it, this run cannot settle a question about "
                                + (gap.metricName().isBlank()
                                ? "what " + gap.providerId() + " can see"
                                : gap.metricName()) + ".",
                        remedyFor(gap)))
                .toList();
    }

    private String remedyFor(dev.vortex.core.metrics.TelemetryGap gap) {
        return switch (gap.availability()) {
            case NO_DATA -> "Check the metric exists in that provider for this service and window.";
            case UNSUPPORTED -> "This provider does not publish it; another provider might.";
            case UNREACHABLE -> "Check the endpoint is correct and reachable from this machine.";
            case UNAUTHORIZED -> "Widen the credential's permissions, or point Vortex at a provider "
                    + "it is allowed to read.";
            case MALFORMED -> "Check the endpoint is the provider's API root rather than a "
                    + "dashboard or proxy.";
            case AVAILABLE -> "";
        };
    }

    // ------------------------------------------------------------------ timeline

    private TimelineEvidence timeline(EffectiveTestPlan plan, MeasuredResults results,
            DeterministicSummary summary) {

        MetricSeries series = results.series();
        if (series == null || series.isEmpty()) {
            return TimelineEvidence.empty();
        }

        List<StageObservation> stages = analyzer.deriveStages(plan, results);

        return new TimelineEvidence(
                series,
                stages,
                bands(stages),
                SeriesPlot.latency(series, latencyObjective(summary).orElse(null), "p95 Latency"),
                SeriesPlot.throughput(series, "Throughput"),
                SeriesPlot.errorRate(series, "Error rate"),
                SeriesPlot.sample(series, TimelineEvidence.MAX_TABLE_ROWS),
                SeriesPlot.peak(series).orElse(null),
                TimelineEvidence.breakpointInstant(stages, series.points()),
                TimelineEvidence.levelChangeInstant(stages, series.points()));
    }

    /** The strictest overall latency objective, drawn as the chart's reference line. */
    private Optional<Duration> latencyObjective(DeterministicSummary summary) {
        return summary.thresholds().results().stream()
                .map(ThresholdResult::threshold)
                .filter(threshold -> threshold.scope().isOverall())
                .filter(LatencyThreshold.class::isInstance)
                .map(LatencyThreshold.class::cast)
                .filter(threshold -> threshold.percentile().equals(Percentile.P95))
                .map(LatencyThreshold::maximum)
                .findFirst();
    }

    /**
     * The levels the workload held, as fractions of the run.
     *
     * <p>Derived from the stage observations rather than the plan's stages, because what a chart
     * needs to shade is what happened, not what was scheduled.
     */
    private List<TimelineEvidence.StageBand> bands(List<StageObservation> stages) {
        if (stages.size() < 2) {
            return List.of();
        }
        int total = stages.stream().mapToInt(StageObservation::sampleCount).sum();
        if (total <= 0) {
            return List.of();
        }

        List<TimelineEvidence.StageBand> bands = new ArrayList<>();
        int consumed = 0;
        for (StageObservation stage : stages) {
            double start = consumed / (double) total;
            consumed += stage.sampleCount();
            bands.add(new TimelineEvidence.StageBand(
                    stage.targetLoad().displayWithUnit(),
                    start,
                    consumed / (double) total,
                    stage.violatedThresholds().isEmpty()));
        }
        return List.copyOf(bands);
    }

    // ------------------------------------------------------------------ resource timeline

    /**
     * CPU, memory and the rest of a run's resource behaviour over time, read back from that
     * execution's own artifact and bounded to a chart-sized number of points per signal.
     *
     * <p>Deliberately separate from {@link #observability}: that method reads the peak/first/last
     * summary already carried on {@code results} in memory, unconditionally; this one reads a
     * separate, possibly-partial artifact from disk, which is why it alone needs a completeness
     * verdict rather than a plain presence flag.
     */
    private ResourceTimelineEvidence resourceTimeline(TestExecution execution, MeasuredResults results,
            TimelineEvidence timeline) {
        var read = resourceTelemetryReader.read(execution.id());
        if (read.samples().isEmpty()) {
            return new ResourceTimelineEvidence(false, read.completeness(), List.of());
        }

        // Neighbourhoods a projected series must not smooth away: the run's own stage boundaries and,
        // where it has one, the instant it stopped complying — the same instants the throughput and
        // latency charts already mark, so a reader zooming in on one sees the others line up.
        List<Instant> anchors = new ArrayList<>();
        timeline.breakpointAtIfPresent().ifPresent(anchors::add);
        timeline.levelChangeAtIfPresent().ifPresent(anchors::add);

        // One caption per signal id, the same "first classification wins" convention observability()
        // already uses. ResourceSignal itself carries no provider id, so when two providers report an
        // identically-named signal (rare, and already kept as two distinct chart series below by
        // provider+signal id) the caption text falls back to whichever was classified first — a
        // narrower gap than the chart data has, which stays correctly split either way.
        Map<String, ResourceSignal> captions = results.resourceSignals().stream()
                .collect(Collectors.toMap(ResourceSignal::signalId, signal -> signal,
                        (first, second) -> first, LinkedHashMap::new));

        Map<String, List<ResourceSample>> bySeries = read.samples().stream()
                .collect(Collectors.groupingBy(
                        sample -> sample.providerId() + " " + sample.signalId(),
                        LinkedHashMap::new, Collectors.toList()));

        Map<ResourceKind, List<ResourceTimelineEvidence.ResourceSeriesEvidence>> byKind =
                new LinkedHashMap<>();
        for (List<ResourceSample> series : bySeries.values()) {
            ResourceSample any = series.get(0);
            List<ResourceSample> projected = ResourceSeriesProjection.project(series, anchors);
            ResourceSignal caption = captions.get(any.signalId());

            List<ResourceTimelineEvidence.ResourceTimelinePoint> points = projected.stream()
                    .map(sample -> new ResourceTimelineEvidence.ResourceTimelinePoint(
                            sample.at(), sample.value()))
                    .toList();

            byKind.computeIfAbsent(any.kind(), _ -> new ArrayList<>())
                    .add(new ResourceTimelineEvidence.ResourceSeriesEvidence(
                            any.signalId(), any.providerId(), any.scope(), any.scope().label(),
                            caption != null ? sanitizer.text(caption.name()) : any.signalId(),
                            any.unit().symbol(),
                            points,
                            caption != null ? caption.observation().display() : "",
                            caption != null && caption.limitIfPresent().isPresent()
                                    ? caption.limit().display() : "",
                            caption != null
                                    ? caption.utilisation()
                                            .map(used -> Math.round(used * 1000) / 10.0 + "%")
                                            .orElse("")
                                    : "",
                            caption != null && caption.isAtItsLimit()));
        }

        List<ResourceTimelineEvidence.ResourceKindPlot> plots = byKind.entrySet().stream()
                .map(entry -> new ResourceTimelineEvidence.ResourceKindPlot(
                        entry.getKey(), entry.getKey().label(), entry.getValue()))
                .toList();

        return new ResourceTimelineEvidence(true, read.completeness(), plots);
    }

    // ------------------------------------------------------------------ comparison

    private ComparisonEvidence comparison(TestExecution execution, TestExecution baseline) {
        if (baseline == null || baseline.summaryIfPresent().isEmpty()) {
            return null;
        }
        ExecutionComparison comparison = regressions.compare(baseline, execution);
        RegressionVerdict verdict = regressions.evaluate(comparison);

        String label = baseline.plan().serviceVersionIfPresent()
                .orElseGet(() -> "run " + shortId(baseline));

        return new ComparisonEvidence(baseline.id(), sanitizer.text(label),
                baseline.finishedAt(), comparison, verdict);
    }

    private String shortId(TestExecution execution) {
        String value = execution.id().value();
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    // ------------------------------------------------------------------ provenance

    private EvidenceProvenance provenance(TestExecution execution, EffectiveTestPlan plan,
            MeasuredResults results, ObservabilityEvidence observability,
            String artifactDirectory, List<String> artifactNames) {

        // Deduplicated and ordered: the same metric sampled repeatedly is one query, and a reader
        // re-running them wants a list, not a transcript.
        List<String> queries = new LinkedHashSet<>(observability.signals().stream()
                .flatMap(signal -> signal.provenance().stream())
                .map(provenance -> provenance.providerId() + ": " + provenance.query())
                .toList()).stream().toList();

        return new EvidenceProvenance(
                EvidenceProvenance.SCHEMA_VERSION,
                execution.toolVersions(),
                plan.fingerprint() == null ? "" : plan.fingerprint().toString(),
                execution.startedAt(),
                execution.finishedAt(),
                results.window(),
                queries,
                artifactDirectory == null ? "" : artifactDirectory,
                artifactNames == null ? List.of() : artifactNames,
                reproductionCommand(plan),
                sanitizer.secretReferences(plan.headers()),
                clock.now(),
                // A capacity figure without the shape of the machine that produced it is not
                // reproducible six months later, which is what this whole record exists for.
                host.describeHost(),
                coverageOf(results, observability),
                execution.quality());
    }

    /**
     * Which providers were consulted, and what each supplied.
     *
     * <p>Built from what came back and what did not, so a provider that failed is listed rather than
     * omitted - omitting it would leave a reader believing nobody had looked there. It is also what
     * lets a comparison state that two runs differ in coverage instead of reporting that difference
     * as a regression.
     */
    private TelemetryCoverage coverageOf(MeasuredResults results,
            ObservabilityEvidence observability) {

        Map<String, Integer> returned = new java.util.LinkedHashMap<>();
        observability.signals().forEach(signal -> signal.provenance().ifPresent(provenance ->
                returned.merge(provenance.providerId(), 1, Integer::sum)));

        Map<String, Integer> asked = new java.util.LinkedHashMap<>(returned);
        results.telemetryGaps().forEach(gap -> asked.merge(gap.providerId(), 1, Integer::sum));

        Map<String, dev.vortex.core.metrics.TelemetryAvailability> worst =
                new java.util.LinkedHashMap<>();
        results.telemetryGaps().forEach(gap ->
                worst.putIfAbsent(gap.providerId(), gap.availability()));

        List<TelemetryCoverage.ProviderCoverage> providers = asked.entrySet().stream()
                .filter(entry -> !entry.getKey().isBlank())
                .map(entry -> new TelemetryCoverage.ProviderCoverage(
                        entry.getKey(),
                        worst.getOrDefault(entry.getKey(),
                                dev.vortex.core.metrics.TelemetryAvailability.AVAILABLE),
                        entry.getValue(),
                        returned.getOrDefault(entry.getKey(), 0)))
                .toList();

        return providers.isEmpty() ? TelemetryCoverage.none() : new TelemetryCoverage(providers);
    }

    /** The command that would run this test again, for a reader who has the repository. */
    private String reproductionCommand(EffectiveTestPlan plan) {
        StringBuilder command = new StringBuilder("vortex run ");
        command.append(plan.workloadName().isBlank() ? "<workload>" : plan.workloadName());
        if (!plan.environmentName().isBlank()) {
            command.append(" --environment ").append(plan.environmentName());
        }
        return sanitizer.text(command.toString());
    }
}
