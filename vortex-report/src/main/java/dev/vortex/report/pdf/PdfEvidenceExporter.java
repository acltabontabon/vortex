package dev.vortex.report.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import dev.vortex.core.evidence.DeterministicFinding;
import dev.vortex.core.evidence.ExportFormat;
import dev.vortex.core.evidence.FindingLevel;
import dev.vortex.core.evidence.ObservedSignal;
import dev.vortex.core.evidence.OperationEvidence;
import dev.vortex.core.evidence.RunEvidence;
import dev.vortex.core.evidence.SeriesPlot;
import dev.vortex.core.metrics.LatencyPercentiles;
import dev.vortex.core.port.EvidenceExporter;
import dev.vortex.core.shared.Percentile;
import dev.vortex.core.threshold.Durations;
import dev.vortex.core.threshold.ThresholdResult;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The performance evidence packet.
 *
 * <p>An engineering record rather than a dashboard printed to paper: black text, grey rules, one
 * accent colour for the verdict, and nothing that only works in colour. It is meant to be read in a
 * release review by somebody who does not have Vortex, months after the run, and to survive being
 * printed.
 *
 * <p>Sections appear in the order a reader needs them and are omitted entirely when their evidence
 * is absent — with one exception. Acceptance criteria always appear, because "this run asserted
 * nothing" is itself the most important thing such a report can say.
 */
public final class PdfEvidenceExporter implements EvidenceExporter {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm 'UTC'", Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    private static final float CHART_HEIGHT = 92;

    /** A PDF file identifier is 16 bytes, written as 32 hex characters. */
    private static final int FILE_ID_HEX_LENGTH = 32;

    @Override
    public ExportFormat format() {
        return ExportFormat.PDF;
    }

    @Override
    public byte[] export(RunEvidence evidence) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        Document document = new Document(PageSize.A4, PdfStyle.MARGIN, PdfStyle.MARGIN,
                PdfStyle.MARGIN_TOP, PdfStyle.MARGIN);

        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new ReportPageEvents(evidence));
            metadata(document, evidence);
            document.open();
            deterministicDates(writer, document, evidence);

            var charts = new PdfChartRenderer(writer);

            title(document, evidence);
            verdict(document, evidence);
            workload(document, evidence);
            performance(document, evidence);
            criteria(document, evidence);
            operations(document, evidence);
            timeline(document, evidence, charts, writer);
            observability(document, evidence);
            findings(document, evidence);
            interpretation(document, evidence);
            provenance(document, evidence);

            document.close();
            return withDeterministicFileId(out.toByteArray(), evidence);
        } catch (RuntimeException e) {
            throw new EvidenceExportException(
                    "Vortex could not render the PDF report for run "
                            + evidence.identity().executionId().value() + ".", e);
        }
    }


    /**
     * Replaces the randomly generated file identifier with one derived from the run.
     *
     * <p>Everything else about this document is already a function of the evidence — the dates are
     * pinned, the iteration orders are fixed, the fonts are not embedded. The one remaining source
     * of variation is the trailer's {@code /ID}, which the library fills with a hash of the current
     * time and some memory addresses, and which has no public setter.
     *
     * <p>Randomness is the wrong default here. A PDF's {@code /ID} exists to identify a particular
     * file, and two exports of one finished run are not two files in any sense that matters: the
     * measurements are immutable once a run completes. Deriving the identifier from the plan
     * fingerprint, the run and the generation timestamp says exactly that, and it means a report
     * regenerated in a pipeline is byte-identical to the one in the last build rather than looking
     * like a change.
     *
     * <p>The substitution is length-preserving — 32 hex characters for 32 — so every byte offset in
     * the cross-reference table stays valid. Only the trailer is searched, and if the expected shape
     * is not found the document is returned untouched: a report with an unstable identifier is a
     * far better outcome than a corrupted one.
     */
    private byte[] withDeterministicFileId(byte[] pdf, RunEvidence evidence) {
        String marker = "/ID [<";
        String document = new String(pdf, StandardCharsets.ISO_8859_1);
        int start = document.lastIndexOf(marker);
        if (start < 0) {
            return pdf;
        }

        int firstHex = start + marker.length();
        int firstEnd = document.indexOf('>', firstHex);
        if (firstEnd < 0 || firstEnd - firstHex != FILE_ID_HEX_LENGTH) {
            return pdf;
        }

        String identifier = fileIdFor(evidence);
        StringBuilder rewritten = new StringBuilder(document);
        rewritten.replace(firstHex, firstEnd, identifier);

        // The array holds the same value twice: the original identifier and the current one, which
        // are equal for a document that has never been incrementally updated.
        int secondHex = rewritten.indexOf("<", firstEnd) + 1;
        int secondEnd = rewritten.indexOf(">", secondHex);
        if (secondHex > 0 && secondEnd - secondHex == FILE_ID_HEX_LENGTH) {
            rewritten.replace(secondHex, secondEnd, identifier);
        }

        return rewritten.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private String fileIdFor(RunEvidence evidence) {
        String seed = evidence.provenance().configurationHash()
                + evidence.identity().executionId().value()
                + evidence.provenance().generatedAt();
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of()
                    .formatHex(digest, 0, FILE_ID_HEX_LENGTH / 2);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new EvidenceExportException("SHA-256 is required but unavailable.", e);
        }
    }

    /**
     * Pins the document's timestamps to the evidence.
     *
     * <p>Without this the library stamps the current time, and two exports of the same finished run
     * would differ. A run's report should be a function of the run, not of when somebody pressed
     * the button.
     */
    private void deterministicDates(PdfWriter writer, Document document, RunEvidence evidence) {
        GregorianCalendar generatedAt =
                new GregorianCalendar(TimeZone.getTimeZone(ZoneOffset.UTC));
        generatedAt.setTimeInMillis(evidence.provenance().generatedAt().toEpochMilli());

        com.lowagie.text.pdf.PdfDate stamp = new com.lowagie.text.pdf.PdfDate(generatedAt);
        document.addCreationDate(stamp);
        // The modification date is written into the info dictionary directly; the document API
        // only offers "now" for it, which would defeat the point.
        writer.getInfo().put(com.lowagie.text.pdf.PdfName.MODDATE, stamp);
        writer.getInfo().put(com.lowagie.text.pdf.PdfName.CREATIONDATE, stamp);
    }

    private void metadata(Document document, RunEvidence evidence) {
        var identity = evidence.identity();
        document.addTitle(PdfText.winAnsi(
                "Vortex performance report - " + identity.describe()));
        document.addSubject(PdfText.winAnsi(evidence.question()));
        document.addAuthor("Vortex");
        document.addProducer();
        // Enough for a reader who has only the file to tell what it is and whether it is comparable
        // with another one.
        document.addKeywords(PdfText.winAnsi(String.join("; ",
                "verdict=" + evidence.verdict().name(),
                "run=" + identity.executionId().value(),
                "configuration=" + evidence.provenance().shortHash(),
                "classification=" + identity.classification().name(),
                "schema=" + evidence.provenance().schemaVersion())));
    }

    // ------------------------------------------------------------------ title and verdict

    private void title(Document document, RunEvidence evidence) {
        var identity = evidence.identity();

        Paragraph eyebrow = new Paragraph(PdfText.winAnsi("VORTEX PERFORMANCE REPORT"),
                PdfStyle.SMALL);
        eyebrow.setSpacingAfter(6);
        document.add(eyebrow);

        Paragraph title = new Paragraph(PdfText.winAnsi(identity.serviceName()
                + identity.serviceVersionIfPresent().map(v -> "  " + v).orElse("")),
                PdfStyle.TITLE);
        title.setSpacingAfter(2);
        document.add(title);

        Paragraph lede = new Paragraph(PdfText.winAnsi(
                identity.testType().label() + " test  ·  " + identity.workloadName()), PdfStyle.LEDE);
        lede.setSpacingAfter(14);
        document.add(lede);
    }

    private void verdict(Document document, RunEvidence evidence) {
        var identity = evidence.identity();

        if (!evidence.question().isBlank()) {
            Paragraph question = new Paragraph(PdfText.winAnsi(evidence.question()), PdfStyle.LEDE);
            question.setSpacingAfter(6);
            document.add(question);
        }

        Font verdictFont = FontFactory.getFont(FontFactory.HELVETICA, 26, Font.BOLD,
                PdfStyle.colourFor(evidence.verdict()));
        Paragraph verdict = new Paragraph(
                PdfText.winAnsi(evidence.verdict().label().toUpperCase(Locale.ROOT)), verdictFont);
        verdict.setSpacingAfter(4);
        document.add(verdict);

        if (!evidence.answer().isBlank()) {
            Paragraph answer = new Paragraph(PdfText.winAnsi(evidence.answer()), PdfStyle.BODY_BOLD);
            answer.setSpacingAfter(12);
            document.add(answer);
        }

        PdfPTable conditions = PdfStyle.table(1, 1, 1, 1);
        conditions.addCell(PdfStyle.header("Environment"));
        conditions.addCell(PdfStyle.header("Ran"));
        conditions.addCell(PdfStyle.header("Duration"));
        conditions.addCell(PdfStyle.header("Target"));
        conditions.addCell(PdfStyle.text(identity.environmentName()
                + " (" + identity.classification().label() + ")"));
        conditions.addCell(PdfStyle.text(identity.finishedAt() == null ? "-"
                : TIMESTAMP.format(identity.finishedAt())));
        conditions.addCell(PdfStyle.text(identity.durationIfPresent()
                .map(Durations::display).orElse("-")));
        conditions.addCell(PdfStyle.text(identity.targetUrl()));
        document.add(conditions);

        // The caveat travels with the figures rather than being relegated to a footnote: a number
        // from a run against mocked dependencies means something different.
        for (String qualification : evidence.qualifications()) {
            Paragraph note = new Paragraph(PdfText.winAnsi(qualification), PdfStyle.SMALL);
            note.setSpacingBefore(8);
            document.add(note);
        }
    }

    // ------------------------------------------------------------------ workload

    private void workload(Document document, RunEvidence evidence) {
        var workload = evidence.workload();
        heading(document, "Workload executed");

        PdfPTable table = PdfStyle.table(2, 1.4f, 1.4f);
        table.addCell(PdfStyle.header(""));
        table.addCell(PdfStyle.headerRight("Configured"));
        table.addCell(PdfStyle.headerRight("Achieved"));

        // Configured and achieved always sit side by side. A run that did not generate the traffic
        // it intended must not be able to look like one that did.
        table.addCell(PdfStyle.text(workload.isOpen() ? "Arrival rate" : "Concurrency"));
        table.addCell(PdfStyle.number(workload.configuredPeak().displayWithUnit()));
        table.addCell(PdfStyle.number(workload.achievedRateIfPresent()
                .map(rate -> rate.displayWithUnit()).orElse("-")));

        table.addCell(PdfStyle.text("Duration"));
        table.addCell(PdfStyle.number(Durations.display(workload.configuredDuration())));
        table.addCell(PdfStyle.number(Durations.display(workload.actualDuration())));

        table.addCell(PdfStyle.text("Requests"));
        table.addCell(PdfStyle.number(workload.estimatedRequestsIfPresent()
                .map(estimate -> "~" + estimate).orElse("-")));
        table.addCell(PdfStyle.number(String.valueOf(workload.requests())));
        document.add(table);

        workload.deliveredPercent().ifPresent(percent -> {
            String sentence = workload.sustainedTheTarget()
                    ? "The configured workload was sustained for the whole run (" + percent
                            + " delivered)."
                    : "Only " + percent + " of the offered rate was delivered. This run did not "
                            + "generate the traffic it was configured to generate.";
            paragraph(document, sentence,
                    workload.sustainedTheTarget() ? PdfStyle.BODY : PdfStyle.BODY_BOLD, 8);
        });

        if (!workload.deliveredCaveat().isBlank()) {
            paragraph(document, workload.deliveredCaveat(), PdfStyle.SMALL, 6);
        }
        if (!workload.operationMix().isEmpty()) {
            paragraph(document, "Mix: " + String.join(", ", workload.operationMix()),
                    PdfStyle.SMALL, 6);
        }
    }

    // ------------------------------------------------------------------ performance

    private void performance(Document document, RunEvidence evidence) {
        var performance = evidence.performance();
        heading(document, "Performance");

        PdfPTable table = PdfStyle.table(1, 1, 1, 1, 1, 1);
        LatencyPercentiles latency = performance.latency();

        table.addCell(PdfStyle.headerRight("p50"));
        table.addCell(PdfStyle.headerRight("p95"));
        table.addCell(PdfStyle.headerRight("p99"));
        table.addCell(PdfStyle.headerRight("max"));
        table.addCell(PdfStyle.headerRight("Errors"));
        table.addCell(PdfStyle.headerRight("Requests"));

        table.addCell(PdfStyle.number(percentile(latency, Percentile.P50)));
        table.addCell(PdfStyle.number(percentile(latency, Percentile.P95)));
        table.addCell(PdfStyle.number(percentile(latency, Percentile.P99)));
        // Guarded on the distribution: the record defaults its maximum to zero, and "0 ms" for a
        // latency nobody measured is exactly the plausible-looking figure this product must not
        // print.
        table.addCell(PdfStyle.number(
                latency.isEmpty() ? "-" : Durations.display(latency.maximum())));
        table.addCell(PdfStyle.number(performance.errorRate().display()));
        table.addCell(PdfStyle.number(String.valueOf(performance.requests())));
        document.add(table);

        performance.sloBreakpointIfPresent().ifPresent(breakpoint -> paragraph(document,
                "Objectives were first violated at " + breakpoint.level().displayWithUnit()
                        + ". " + breakpoint.describe() + " (" + breakpoint.strength().label()
                        + " confidence, from " + breakpoint.stagesObserved() + " levels.)",
                PdfStyle.BODY, 10));

        performance.systemSaturationIfPresent().ifPresent(saturation -> paragraph(document,
                saturation.describe() + " " + saturation.explanation(), PdfStyle.BODY, 8));

        performance.headroomIfPresent().ifPresent(headroom -> paragraph(document,
                "Headroom is " + headroom.display() + " above observed production traffic, against "
                        + "tested SLO-compliant capacity of " + headroom.testedCapacity().display()
                        + " requests/sec, under the conditions above.",
                PdfStyle.BODY, 8));

        performance.headroomRefusalIfPresent().ifPresent(reason -> paragraph(document,
                "Headroom is not stated. " + reason, PdfStyle.BODY, 8));

        if (!performance.baselineQuality().isEmpty()) {
            paragraph(document, "The production baseline behind that comparison: "
                    + String.join("; ", performance.baselineQuality()) + ".", PdfStyle.BODY, 8);
        }
    }

    private String percentile(LatencyPercentiles latency, Percentile percentile) {
        return latency.at(percentile).map(Durations::display).orElse("-");
    }

    // ------------------------------------------------------------------ criteria

    private void criteria(Document document, RunEvidence evidence) {
        var acceptance = evidence.acceptance();
        heading(document, "Acceptance criteria");

        // Always rendered, even when empty. "This run asserted nothing" is the single most
        // important thing a report of such a run can say, and an omitted section would not say it.
        if (!acceptance.hasObjectives()) {
            paragraph(document, acceptance.absenceExplanation(), PdfStyle.BODY, 4);
            return;
        }

        PdfPTable table = PdfStyle.table(3, 1.2f, 1.2f);
        table.addCell(PdfStyle.header("Criterion"));
        table.addCell(PdfStyle.headerRight("Observed"));
        table.addCell(PdfStyle.header("Result"));

        for (ThresholdResult result : acceptance.results()) {
            table.addCell(PdfStyle.text(result.threshold().describe()));
            table.addCell(result.observed().isBlank()
                    ? PdfStyle.muted(result.note().isBlank() ? "-" : result.note())
                    : PdfStyle.number(result.observed()));
            table.addCell(PdfStyle.verdict(result.verdict()));
        }
        document.add(table);

        if (!acceptance.unevaluated().isEmpty()) {
            paragraph(document, acceptance.unevaluated().size()
                    + (acceptance.unevaluated().size() == 1
                            ? " objective could not be evaluated, so it has not been met."
                            : " objectives could not be evaluated, so they have not been met."),
                    PdfStyle.BODY_BOLD, 8);
        }
    }

    // ------------------------------------------------------------------ operations

    private void operations(Document document, RunEvidence evidence) {
        if (!evidence.hasOperationBreakdown()) {
            return;
        }
        heading(document, "Operation breakdown");

        PdfPTable table = PdfStyle.table(2.4f, 1.15f, 1, 1, 1, 1);
        table.addCell(PdfStyle.header("Operation"));
        // The unit lives in the header rather than in every cell. It is still in the same table, so
        // no figure has forgotten what it counts, and the column stops wrapping "requests/sec"
        // across three lines.
        table.addCell(PdfStyle.headerRight("Rate (req/s)"));
        table.addCell(PdfStyle.headerRight("Requests"));
        table.addCell(PdfStyle.headerRight("p95"));
        table.addCell(PdfStyle.headerRight("p99"));
        table.addCell(PdfStyle.headerRight("Errors"));

        for (OperationEvidence operation : evidence.operations()) {
            table.addCell(PdfStyle.text(operation.name()));
            if (!operation.hasTraffic()) {
                // Never zeroes: "0 ms, 0% errors" reads as a flawless result for an operation that
                // never ran at all.
                table.addCell(PdfStyle.muted("-"));
                table.addCell(PdfStyle.muted("-"));
                table.addCell(PdfStyle.muted("-"));
                table.addCell(PdfStyle.muted("-"));
                table.addCell(PdfStyle.muted("no traffic"));
                continue;
            }
            var metrics = operation.metrics();
            table.addCell(PdfStyle.number(metrics.achievedRateIfPresent()
                    .map(rate -> rate.display()).orElse("-")));
            table.addCell(PdfStyle.number(String.valueOf(metrics.requests())));
            table.addCell(PdfStyle.number(percentile(metrics.latency(), Percentile.P95)));
            table.addCell(PdfStyle.number(percentile(metrics.latency(), Percentile.P99)));
            table.addCell(PdfStyle.number(metrics.errorRate().display()));
        }
        document.add(table);
        requestData(document, evidence);
    }

    /**
     * Where each request's values came from.
     *
     * <p>A condition the result was measured under, the same as the environment and the dependency
     * mode. Sources only: a generated value's output differs on every request by design, and a
     * secret's value never leaves the engine's process.
     */
    private void requestData(Document document, RunEvidence evidence) {
        boolean any = evidence.operations().stream().anyMatch(OperationEvidence::hasRequestData);
        if (!any) {
            return;
        }
        heading(document, "Request data");

        PdfPTable table = PdfStyle.table(1.6f, 1, 2.2f);
        table.addCell(PdfStyle.header("Operation"));
        table.addCell(PdfStyle.header("Value"));
        table.addCell(PdfStyle.header("Source"));

        for (OperationEvidence operation : evidence.operations()) {
            for (var origin : operation.requestData()) {
                table.addCell(PdfStyle.text(operation.name()));
                table.addCell(PdfStyle.text(origin.name() + " (" + origin.target().label() + ")"));
                table.addCell(PdfStyle.text(origin.source()));
            }
        }
        document.add(table);
    }

    // ------------------------------------------------------------------ timeline

    private void timeline(Document document, RunEvidence evidence,
            PdfChartRenderer charts, PdfWriter writer)
            {

        var timeline = evidence.timeline();
        if (!timeline.isRenderable()) {
            return;
        }
        heading(document, "Timeline");

        float width = document.right() - document.left();
        for (SeriesPlot plot : timeline.plots()) {
            if (!plot.hasData()) {
                continue;
            }
            // Heading and chart go into one table cell, kept together. An Image added straight to
            // the document is positioned independently of the surrounding text, which put every
            // chart at the top of the page above the heading that introduced it.
            PdfPTable holder = new PdfPTable(1);
            holder.setWidthPercentage(100);
            holder.setSpacingBefore(10);
            holder.setKeepTogether(true);

            PdfPCell cell = new PdfPCell();
            cell.setBorder(com.lowagie.text.Rectangle.NO_BORDER);
            cell.setPadding(0);
            cell.addElement(new Paragraph(PdfText.winAnsi(plot.label()), PdfStyle.H2));
            cell.addElement(charts.render(plot, width - 4, CHART_HEIGHT));
            holder.addCell(cell);
            document.add(holder);
        }

        // ADR-021's rule holds in print: the table of numbers is the other half of every chart, not
        // a fallback. A shape on a page is not something anyone can quote in a review.
        numbersBehindTheCharts(document, evidence);
    }

    private void numbersBehindTheCharts(Document document, RunEvidence evidence)
            {

        var rows = evidence.timeline().tableRows();
        if (rows.isEmpty()) {
            return;
        }
        paragraph(document, "The numbers behind these charts", PdfStyle.H2, 10);

        PdfPTable table = PdfStyle.table(1.2f, 1, 1, 1, 1);
        table.addCell(PdfStyle.header("Elapsed"));
        table.addCell(PdfStyle.headerRight("Target (req/s)"));
        table.addCell(PdfStyle.headerRight("Achieved (req/s)"));
        table.addCell(PdfStyle.headerRight("p95"));
        table.addCell(PdfStyle.headerRight("Errors"));

        var start = rows.get(0).at();
        for (var point : rows) {
            table.addCell(PdfStyle.text(SeriesPlot.elapsedLabel(
                    java.time.Duration.between(start, point.at()))));
            table.addCell(PdfStyle.number(point.targetLoadIfPresent()
                    .map(level -> level.display()).orElse("-")));
            table.addCell(PdfStyle.number(point.requestRateIfPresent()
                    .map(rate -> rate.display()).orElse("-")));
            table.addCell(PdfStyle.number(point.p95IfPresent()
                    .map(Durations::display).orElse("-")));
            table.addCell(PdfStyle.number(point.errorRate().display()));
        }
        document.add(table);
    }

    // ------------------------------------------------------------------ observability

    private void observability(Document document, RunEvidence evidence) {
        if (!evidence.hasObservability()) {
            return;
        }
        heading(document, "Observability evidence");

        PdfPTable table = PdfStyle.table(2.2f, 1, 1.6f, 1.2f);
        table.addCell(PdfStyle.header("Signal"));
        table.addCell(PdfStyle.headerRight("Peak"));
        table.addCell(PdfStyle.header("Across the run"));
        table.addCell(PdfStyle.header("Source"));

        for (ObservedSignal signal : evidence.observability().signals()) {
            table.addCell(PdfStyle.text(signal.name()));
            table.addCell(PdfStyle.number(signal.display()));
            table.addCell(PdfStyle.text(signal.movement().orElse("-")));
            table.addCell(PdfStyle.text(signal.source().label()));
        }
        document.add(table);

        // The query is what makes a number checkable rather than merely quotable.
        for (ObservedSignal signal : evidence.observability().signals()) {
            signal.provenance().ifPresent(provenance -> {
                if (!provenance.query().isBlank()) {
                    paragraph(document, signal.name() + ": " + provenance.query(), PdfStyle.MONO, 4);
                }
            });
        }
    }

    // ------------------------------------------------------------------ findings

    private void findings(Document document, RunEvidence evidence) {
        if (!evidence.hasFindings()) {
            return;
        }
        heading(document, "Findings");

        for (FindingLevel level : evidence.findingLevels()) {
            for (DeterministicFinding finding : evidence.findingsAt(level)) {
                Paragraph headline = new Paragraph();
                headline.add(new Phrase(PdfText.winAnsi(level.label().toUpperCase(Locale.ROOT) + "  "),
                        FontFactory.getFont(FontFactory.HELVETICA, 8, Font.BOLD,
                                colourFor(level))));
                headline.add(new Phrase(PdfText.winAnsi(finding.headline()), PdfStyle.BODY_BOLD));
                headline.setSpacingBefore(9);
                document.add(headline);

                if (finding.hasDetail()) {
                    paragraph(document, finding.detail(), PdfStyle.BODY, 2);
                }
                paragraph(document, finding.strength().label() + " confidence  ·  "
                        + String.join(", ", finding.evidenceIds()), PdfStyle.SMALL, 2);
            }
        }
    }

    private java.awt.Color colourFor(FindingLevel level) {
        return switch (level) {
            case FAIL -> PdfStyle.FAIL;
            case WARNING -> PdfStyle.WARN;
            case PASS -> PdfStyle.PASS;
            case OBSERVATION -> PdfStyle.MUTED;
        };
    }

    // ------------------------------------------------------------------ interpretation

    private void interpretation(Document document, RunEvidence evidence) {
        var interpretation = evidence.interpretationIfPresent().orElse(null);
        if (interpretation == null) {
            return;
        }
        heading(document, "Interpretation");

        // Fenced and attributed. A reader has to be able to tell a measurement from an opinion at a
        // glance, and this is the only section of the document that is not reproducible.
        paragraph(document, interpretation.disclaimer(), PdfStyle.SMALL, 2);
        paragraph(document, interpretation.conclusion(), PdfStyle.BODY, 8);

        interpretation.findings().forEach(finding ->
                paragraph(document, "- " + finding.statement(), PdfStyle.BODY, 3));

        paragraph(document, "Produced by " + interpretation.provenance().describe(),
                PdfStyle.SMALL, 8);
    }

    // ------------------------------------------------------------------ provenance

    private void provenance(Document document, RunEvidence evidence) {
        var provenance = evidence.provenance();
        var versions = provenance.toolVersions();
        heading(document, "Reproducing this run");

        PdfPTable table = PdfStyle.table(1, 2.6f);
        table.addCell(PdfStyle.header("Field"));
        table.addCell(PdfStyle.header("Value"));

        row(table, "Run", evidence.identity().executionId().value());
        row(table, "Vortex", versions.vortexVersion());
        row(table, "Engine", versions.engineVersion());
        row(table, "Runtime", versions.runtimeVersion());
        versions.dockerImageIfPresent().ifPresent(image -> row(table, "Container", image));
        row(table, "Configuration", provenance.configurationHash());
        row(table, "Artifacts", provenance.artifactDirectory());
        if (!provenance.secretReferences().isEmpty()) {
            row(table, "Requires", String.join(", ", provenance.secretReferences()));
        }
        // Names in full, values already masked. Reproducing a run means knowing it was
        // authenticated, not knowing what it was authenticated with.
        evidence.workload().requestHeaders().forEach(
                (name, value) -> row(table, "Header", name + ": " + value));
        for (String query : provenance.evidenceQueries()) {
            row(table, "Query", query);
        }
        row(table, "Reproduce", provenance.reproductionCommand());
        row(table, "Generated", TIMESTAMP.format(provenance.generatedAt()));
        document.add(table);
    }

    private void row(PdfPTable table, String name, String value) {
        table.addCell(PdfStyle.text(name));
        table.addCell(PdfStyle.mono(value));
    }

    // ------------------------------------------------------------------ helpers

    private void heading(Document document, String text) {
        Paragraph heading = new Paragraph(PdfText.winAnsi(text), PdfStyle.H1);
        heading.setSpacingBefore(20);
        heading.setSpacingAfter(2);
        // Keeps a heading from being stranded as the last line of a page with nothing under it.
        heading.setKeepTogether(true);
        document.add(heading);
    }

    private void paragraph(Document document, String text, Font font, float spacingBefore) {
        Paragraph paragraph = new Paragraph(PdfText.winAnsi(text), font);
        paragraph.setSpacingBefore(spacingBefore);
        document.add(paragraph);
    }
}
