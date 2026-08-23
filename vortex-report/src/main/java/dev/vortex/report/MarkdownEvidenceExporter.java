package dev.vortex.report;

import dev.vortex.core.evidence.DeterministicFinding;
import dev.vortex.core.evidence.ExportFormat;
import dev.vortex.core.evidence.FindingLevel;
import dev.vortex.core.evidence.ObservedSignal;
import dev.vortex.core.evidence.OperationEvidence;
import dev.vortex.core.evidence.RunEvidence;
import dev.vortex.core.metrics.LatencyPercentiles;
import dev.vortex.core.port.EvidenceExporter;
import dev.vortex.core.shared.Percentile;
import dev.vortex.core.threshold.Durations;
import dev.vortex.core.threshold.ThresholdResult;
import dev.vortex.core.threshold.Verdict;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The paste-into-a-merge-request export.
 *
 * <p>Aimed squarely at a pull request comment, a ticket, an ADR or an incident thread — places where
 * somebody will read it in a narrow column, without Vortex open, and where a wall of text gets
 * skipped. So it leads with the verdict, keeps tables narrow, and stops.
 *
 * <p>No images and no charts. The appeal of this format is that it is text: it diffs, it greps, it
 * survives being quoted. A run's shape over time is conveyed by the numbers and, where it helps, a
 * sparkline drawn in block characters — which costs nothing and degrades to something harmless
 * wherever the font is unhelpful.
 */
public final class MarkdownEvidenceExporter implements EvidenceExporter {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    /** The blocks a sparkline is drawn from, lowest to highest. */
    private static final char[] BLOCKS = {'▁', '▂', '▃', '▄', '▅',
            '▆', '▇', '█'};

    private static final int SPARKLINE_WIDTH = 40;

    @Override
    public ExportFormat format() {
        return ExportFormat.MARKDOWN;
    }

    @Override
    public byte[] export(RunEvidence evidence) {
        StringBuilder out = new StringBuilder(4096);

        verdict(evidence, out);
        workload(evidence, out);
        performance(evidence, out);
        criteria(evidence, out);
        operations(evidence, out);
        timeline(evidence, out);
        observability(evidence, out);
        findings(evidence, out);
        provenance(evidence, out);

        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ verdict

    private void verdict(RunEvidence evidence, StringBuilder out) {
        var identity = evidence.identity();

        out.append("## Vortex performance result — ")
                .append(evidence.verdict().label().toUpperCase(Locale.ROOT)).append("\n\n");

        if (!evidence.question().isBlank()) {
            out.append("> ").append(evidence.question()).append("\n>\n> **")
                    .append(evidence.answer()).append("**\n\n");
        }

        // Two spaces before each newline: the only way to get a line break inside a paragraph in
        // GitHub-flavoured Markdown without an empty line between every field.
        field(out, "Service", identity.serviceName()
                + identity.serviceVersionIfPresent().map(v -> " " + v).orElse(""));
        field(out, "Workload", identity.workloadName());
        field(out, "Environment", identity.environmentName()
                + " (" + identity.classification().label() + ")");
        identity.durationIfPresent().ifPresent(
                duration -> field(out, "Duration", Durations.display(duration)));
        if (identity.finishedAt() != null) {
            field(out, "Ran", TIMESTAMP.format(identity.finishedAt()));
        }
        out.append('\n');

        if (!evidence.qualifications().isEmpty()) {
            // These travel with the numbers, never in a footnote. A figure from a run against
            // mocked dependencies means something different, and the reader has to see that here.
            evidence.qualifications().forEach(note -> out.append("> ").append(note).append("\n"));
            out.append('\n');
        }
    }

    private void field(StringBuilder out, String name, String value) {
        if (value != null && !value.isBlank()) {
            out.append("**").append(name).append(":** ").append(value).append("  \n");
        }
    }

    // ------------------------------------------------------------------ workload

    private void workload(RunEvidence evidence, StringBuilder out) {
        var workload = evidence.workload();
        out.append("### Workload\n\n");
        out.append("| | Configured | Achieved |\n|---|---:|---:|\n");

        out.append("| Rate | ").append(workload.configuredPeak().displayWithUnit()).append(" | ")
                .append(workload.achievedRateIfPresent()
                        .map(rate -> rate.displayWithUnit()).orElse("—"))
                .append(" |\n");

        out.append("| Duration | ").append(Durations.display(workload.configuredDuration()))
                .append(" | ").append(Durations.display(workload.actualDuration())).append(" |\n");

        out.append("| Requests | ")
                .append(workload.estimatedRequestsIfPresent()
                        .map(estimate -> "~" + estimate).orElse("—"))
                .append(" | ").append(workload.requests()).append(" |\n\n");

        // The gap between offered and achieved is the single most informative thing a run produces,
        // so it gets its own sentence rather than being left for the reader to divide.
        workload.deliveredPercent().ifPresent(percent -> out
                .append(workload.sustainedTheTarget()
                        ? "The configured workload was sustained (" + percent + " delivered).\n\n"
                        : "**Only " + percent + " of the offered rate was delivered.** This run did "
                                + "not generate the traffic it was configured to generate.\n\n"));

        if (!workload.deliveredCaveat().isBlank()) {
            out.append(workload.deliveredCaveat()).append("\n\n");
        }
        if (!workload.operationMix().isEmpty()) {
            out.append("**Mix:** ").append(String.join(", ", workload.operationMix()))
                    .append("\n\n");
        }
    }

    // ------------------------------------------------------------------ performance

    private void performance(RunEvidence evidence, StringBuilder out) {
        var performance = evidence.performance();
        LatencyPercentiles latency = performance.latency();

        out.append("### Performance\n\n");
        out.append("| Metric | Value |\n|---|---:|\n");
        for (Percentile percentile : List.of(Percentile.P50, Percentile.P95, Percentile.P99)) {
            latency.at(percentile).ifPresent(value -> out.append("| ").append(percentile.label())
                    .append(" | ").append(Durations.display(value)).append(" |\n"));
        }
        // Guarded on the distribution rather than on the field. LatencyPercentiles defaults its
        // minimum, mean and maximum to zero, so a run that measured no latency at all would
        // otherwise report "max 0 ms" — a plausible-looking number for a measurement never taken,
        // which is the one thing this product must never print.
        if (!latency.isEmpty()) {
            out.append("| max | ").append(Durations.display(latency.maximum())).append(" |\n");
        }
        out.append("| Error rate | ").append(performance.errorRate().display()).append(" |\n");
        out.append("| Requests | ").append(performance.requests()).append(" |\n\n");

        performance.sloBreakpointIfPresent().ifPresent(breakpoint -> out
                .append("**Objectives first violated at ")
                .append(breakpoint.level().displayWithUnit()).append("** (")
                .append(breakpoint.strength().label().toLowerCase(Locale.ROOT))
                .append(" confidence, ").append(breakpoint.stagesObserved())
                .append(" levels observed).\n\n"));

        performance.headroomIfPresent().ifPresent(headroom -> out
                .append("**Headroom:** ").append(headroom.display())
                .append(" above observed production traffic, against tested SLO-compliant capacity ")
                .append(headroom.testedCapacity().display()).append(" requests/sec.\n\n"));

        // Never silence where a number was expected: a report that goes quiet reads as a tool that
        // forgot rather than one that declined, and the reason is usually the actionable part.
        performance.headroomRefusalIfPresent().ifPresent(reason -> out
                .append("**Headroom:** not stated. ").append(reason).append("\n\n"));

        if (!performance.baselineQuality().isEmpty()) {
            out.append("The production baseline behind that comparison:\n\n");
            performance.baselineQuality().forEach(fact ->
                    out.append("- ").append(fact).append('\n'));
            out.append('\n');
        }
    }

    // ------------------------------------------------------------------ criteria

    private void criteria(RunEvidence evidence, StringBuilder out) {
        var acceptance = evidence.acceptance();
        out.append("### Acceptance criteria\n\n");

        if (!acceptance.hasObjectives()) {
            out.append(acceptance.absenceExplanation()).append("\n\n");
            return;
        }

        out.append("| Criterion | Observed | Result |\n|---|---:|---|\n");
        for (ThresholdResult result : acceptance.results()) {
            out.append("| ").append(result.threshold().describe())
                    .append(" | ")
                    .append(result.observed().isBlank() ? "—" : result.observed())
                    .append(" | ").append(mark(result.verdict())).append(" |\n");
        }
        out.append('\n');

        // Unevaluated is not a pass, and a reader skimming a table of ticks must not be able to
        // come away thinking it was.
        if (!acceptance.unevaluated().isEmpty()) {
            out.append("> ").append(acceptance.unevaluated().size())
                    .append(acceptance.unevaluated().size() == 1
                            ? " objective could not be evaluated, so it has not been met.\n\n"
                            : " objectives could not be evaluated, so they have not been met.\n\n");
        }
    }

    private String mark(Verdict verdict) {
        return switch (verdict) {
            case PASS -> "PASS";
            case FAIL -> "**FAIL**";
            case NOT_EVALUATED -> "not evaluated";
        };
    }

    // ------------------------------------------------------------------ operations

    private void operations(RunEvidence evidence, StringBuilder out) {
        if (!evidence.hasOperationBreakdown()) {
            return;
        }
        out.append("### Operations\n\n");
        out.append("| Operation | Rate | p95 | p99 | Errors |\n|---|---:|---:|---:|---:|\n");

        for (OperationEvidence operation : evidence.operations()) {
            out.append("| ").append(operation.name()).append(" | ");
            if (!operation.hasTraffic()) {
                // Never zeroes. "0 ms, 0% errors" reads as a flawless result for an operation that
                // never ran.
                out.append("— | — | — | no traffic |\n");
                continue;
            }
            var metrics = operation.metrics();
            out.append(metrics.achievedRateIfPresent()
                            .map(rate -> rate.displayWithUnit()).orElse("—")).append(" | ")
                    .append(percentile(metrics.latency(), Percentile.P95)).append(" | ")
                    .append(percentile(metrics.latency(), Percentile.P99)).append(" | ")
                    .append(metrics.errorRate().display()).append(" |\n");
        }
        out.append('\n');
        requestData(evidence, out);
    }

    /**
     * Where each request's values came from.
     *
     * <p>Part of the conditions a result was measured under, in the same way the environment and the
     * dependency mode are: a capacity figure produced by replaying one account id and one produced
     * from a dataset of ten thousand distinct accounts are not the same result.
     *
     * <p>Sources only. A generated value's output is not recorded — it differs on every request by
     * design — and a secret's value never leaves the engine's process.
     */
    private void requestData(RunEvidence evidence, StringBuilder out) {
        boolean any = evidence.operations().stream().anyMatch(OperationEvidence::hasRequestData);
        if (!any) {
            return;
        }
        out.append("#### Request data\n\n");
        for (OperationEvidence operation : evidence.operations()) {
            if (!operation.hasRequestData()) {
                continue;
            }
            out.append("**").append(operation.name()).append("**\n\n");
            for (var origin : operation.requestData()) {
                out.append("- ").append(origin.describe()).append('\n');
            }
            out.append('\n');
        }
    }

    private String percentile(LatencyPercentiles latency, Percentile percentile) {
        return latency.at(percentile).map(Durations::display).orElse("—");
    }

    // ------------------------------------------------------------------ timeline

    private void timeline(RunEvidence evidence, StringBuilder out) {
        var timeline = evidence.timeline();
        if (!timeline.isRenderable()) {
            return;
        }
        out.append("### Over time\n\n");
        // One column per bucket, never more. A short run has a handful of buckets, and stretching
        // those across a fixed width would leave blanks that read as gaps in the measurements when
        // nothing is actually missing.
        int columns = Math.min(SPARKLINE_WIDTH, Math.max(2, timeline.series().size()));
        sparkline(out, "Throughput", timeline.throughputPlot(), columns);
        sparkline(out, "p95", timeline.latencyPlot(), columns);
        sparkline(out, "Errors", timeline.errorRatePlot(), columns);
        out.append('\n');
    }

    /**
     * One signal as a row of blocks, positioned on the run's time axis.
     *
     * <p>Indexed by each point's position across the run rather than by its position in the list,
     * for two reasons. A bucket where nothing was measured stays blank instead of being closed up —
     * bridging a gap would invent a measurement, which is the same rule the charts follow. And every
     * row spans the same axis, so the three lines stack into something a reader can actually compare
     * moment for moment.
     */
    private void sparkline(StringBuilder out, String label,
            dev.vortex.core.evidence.SeriesPlot plot, int columns) {
        if (!plot.hasData()) {
            return;
        }

        char[] line = new char[columns];
        java.util.Arrays.fill(line, ' ');

        for (var segment : plot.segments()) {
            for (var point : segment.points()) {
                int column = (int) Math.round(point.x() * (columns - 1));
                int height = (int) Math.round(point.y() * (BLOCKS.length - 1));
                line[Math.max(0, Math.min(columns - 1, column))] =
                        BLOCKS[Math.max(0, Math.min(BLOCKS.length - 1, height))];
            }
        }

        out.append("`").append(String.format(Locale.ROOT, "%-11s", label)).append(new String(line))
                .append("` 0–").append(dev.vortex.core.evidence.SeriesPlot
                        .axisLabel(plot.scaleMaximum()))
                .append(' ').append(plot.unitSymbol()).append("\n\n");
    }

    // ------------------------------------------------------------------ observability

    private void observability(RunEvidence evidence, StringBuilder out) {
        if (!evidence.hasObservability()) {
            return;
        }
        out.append("### From the service under test\n\n");
        out.append("| Signal | Peak | Movement | Source |\n|---|---:|---|---|\n");

        // Signals that moved first. A table led by half a dozen flat zeroes buries the one row a
        // reader needed to see. Nothing is dropped: a measurement that stayed put is still
        // evidence, and removing it from a document about evidence would be worse than ordering it
        // last.
        List<ObservedSignal> ordered = new java.util.ArrayList<>(
                evidence.observability().signals());
        ordered.sort(java.util.Comparator.comparing(ObservedSignal::roseDuringRun).reversed());

        for (ObservedSignal signal : ordered) {
            out.append("| ").append(signal.name())
                    .append(" | ").append(signal.display())
                    .append(" | ").append(signal.movement().orElse("—"))
                    .append(" | ").append(signal.source().label()).append(" |\n");
        }
        out.append('\n');
    }

    // ------------------------------------------------------------------ findings

    private void findings(RunEvidence evidence, StringBuilder out) {
        if (!evidence.hasFindings()) {
            return;
        }
        out.append("### Findings\n\n");
        for (FindingLevel level : evidence.findingLevels()) {
            for (DeterministicFinding finding : evidence.findingsAt(level)) {
                out.append("- ");
                if (level != FindingLevel.PASS) {
                    out.append("**").append(level.label()).append("** — ");
                }
                out.append(finding.headline()).append('\n');
            }
        }
        out.append('\n');
    }

    // ------------------------------------------------------------------ provenance

    private void provenance(RunEvidence evidence, StringBuilder out) {
        var provenance = evidence.provenance();
        var versions = provenance.toolVersions();

        out.append("<details>\n<summary>Reproducing this run</summary>\n\n");
        out.append("```\n").append(provenance.reproductionCommand()).append("\n```\n\n");
        field(out, "Run", evidence.identity().executionId().value());
        field(out, "Vortex", versions.vortexVersion());
        field(out, "Engine", versions.engineVersion());
        field(out, "Configuration", provenance.shortHash());
        if (!provenance.secretReferences().isEmpty()) {
            field(out, "Requires", String.join(", ", provenance.secretReferences()));
        }
        if (evidence.workload().hasRequestHeaders()) {
            field(out, "Headers", String.join(", ", evidence.workload().requestHeaders().entrySet()
                    .stream().map(header -> header.getKey() + ": " + header.getValue()).toList()));
        }
        out.append("\n</details>\n");
    }
}
