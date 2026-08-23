package dev.vortex.report.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import dev.vortex.core.evidence.RunEvidence;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The PDF is the copy that leaves the building — attached to a release review, filed against a
 * change, read months later by somebody who never had Vortex installed.
 *
 * <p>Nothing here asserts a pixel or a coordinate. A test that pinned the layout would fail on every
 * spacing tweak and would be deleted within a month. What is asserted is what a reader must be able
 * to get out of the file: that it opens, that the sections are there, that the important numbers
 * are there, that a long run stays readable across pages, and that a run with no optional evidence
 * still produces a document.
 */
class PdfEvidenceExporterTest {

    private final PdfEvidenceExporter exporter = new PdfEvidenceExporter();

    @Nested
    @DisplayName("it is a real PDF")
    class Validity {

        @Test
        void hasTheStructureEveryReaderExpects() throws IOException {
            byte[] pdf = exporter.export(dev.vortex.report.ReportFixtures.rich());

            assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
            assertThat(new String(pdf, StandardCharsets.ISO_8859_1).trim()).endsWith("%%EOF");
        }

        @Test
        @DisplayName("it opens with a PDF parser, which a well-formed header alone does not prove")
        void opensAndReportsPages() throws IOException {
            PdfReader reader = new PdfReader(exporter.export(dev.vortex.report.ReportFixtures.rich()));

            assertThat(reader.getNumberOfPages()).isPositive();
            reader.close();
        }

        @Test
        void carriesMetadataThatIdentifiesTheRun() throws IOException {
            PdfReader reader = new PdfReader(exporter.export(dev.vortex.report.ReportFixtures.rich()));

            var info = reader.getInfo();
            assertThat(info.get("Title").toString()).contains("checkout-service");
            assertThat(info.get("Keywords").toString())
                    .contains("verdict=PASS")
                    .contains("configuration=");
            reader.close();
        }
    }

    @Nested
    @DisplayName("what a reader can find in it")
    class Content {

        @Test
        void everySectionIsPresent() throws IOException {
            String text = textOf(dev.vortex.report.ReportFixtures.rich());

            assertThat(text)
                    .contains("VORTEX PERFORMANCE REPORT")
                    .contains("Workload executed")
                    .contains("Performance")
                    .contains("Acceptance criteria")
                    .contains("Operation breakdown")
                    .contains("Timeline")
                    .contains("Observability evidence")
                    .contains("Findings")
                    .contains("Reproducing this run");
        }

        @Test
        @DisplayName("the verdict and the answer are on the first page, not buried")
        void verdictLeads() throws IOException {
            List<String> pages = pagesOf(dev.vortex.report.ReportFixtures.rich());

            assertThat(pages.get(0))
                    .contains("PASS")
                    .contains("Yes, with every objective met.");
        }

        @Test
        @DisplayName("the figures a reader came for are actually in the document")
        void keyValuesArePresent() throws IOException {
            String text = textOf(dev.vortex.report.ReportFixtures.rich());

            assertThat(text)
                    .contains("281 ms")              // p95
                    .contains("19.8 requests/sec")   // achieved rate
                    .contains("20 requests/sec")     // configured rate
                    .contains("0.08%")               // error rate
                    .contains("GET /orders/{id}")    // an operation
                    .contains("k6 v1.3.0");          // engine version
        }

        @Test
        @DisplayName("the conditions travel with the numbers")
        void caveatsAreCarried() throws IOException {
            assertThat(textOf(dev.vortex.report.ReportFixtures.rich()))
                    .contains("cannot establish production capacity");
        }

        @Test
        @DisplayName("a failing run says which criteria failed")
        void failuresAreLegible() throws IOException {
            String text = textOf(dev.vortex.report.ReportFixtures.failing());

            assertThat(text).contains("FAIL");
            assertThat(text).contains("p95 latency below 500 ms");
        }

        @Test
        @DisplayName("observability evidence keeps the query that produced it")
        void observabilityQueriesAreShown() throws IOException {
            assertThat(textOf(dev.vortex.report.ReportFixtures.rich()))
                    .contains("hikaricp.connections.active / hikaricp.connections.max");
        }

        @Test
        @DisplayName("charts are paired with the numbers behind them, in print as on screen")
        void chartsAreNeverAlone() throws IOException {
            assertThat(textOf(dev.vortex.report.ReportFixtures.rich()))
                    .contains("The numbers behind these charts");
        }
    }

    @Nested
    @DisplayName("longer runs")
    class Pagination {

        @Test
        @DisplayName("a fifty-operation run spans pages and repeats the table header on each")
        void tableHeadersRepeatAcrossPages() throws IOException {
            List<String> pages = pagesOf(dev.vortex.report.ReportFixtures.manyOperations(50));

            assertThat(pages).hasSizeGreaterThan(1);

            // The column names must appear on more than one page. That is precisely what a
            // repeating header row buys, and a reader who turns over should not be looking at an
            // unlabelled grid of numbers.
            long pagesWithHeader = pages.stream()
                    .filter(page -> page.contains("Operation Rate (req/s)"))
                    .count();

            assertThat(pagesWithHeader)
                    .as("the operation table header should repeat on every page it spans")
                    .isGreaterThan(1);
        }

        @Test
        void aWideRunStillContainsEveryOperation() throws IOException {
            String text = textOf(dev.vortex.report.ReportFixtures.manyOperations(50));

            assertThat(text).contains("/resource/0/detail").contains("/resource/49/detail");
        }
    }

    @Nested
    @DisplayName("when optional evidence is missing")
    class Sparse {

        @Test
        @DisplayName("a run with no telemetry, series or objectives still produces a document")
        void sparseRunsRender() throws IOException {
            PdfReader reader = new PdfReader(exporter.export(dev.vortex.report.ReportFixtures.sparse()));

            assertThat(reader.getNumberOfPages()).isPositive();
            reader.close();
        }

        @Test
        void absentSectionsAreOmittedRatherThanEmpty() throws IOException {
            String text = textOf(dev.vortex.report.ReportFixtures.sparse());

            assertThat(text)
                    .doesNotContain("Observability evidence")
                    .doesNotContain("Timeline");
        }

        @Test
        @DisplayName("acceptance criteria appear even with none configured, because that is the finding")
        void theAbsenceOfObjectivesIsStated() throws IOException {
            assertThat(textOf(dev.vortex.report.ReportFixtures.sparse()))
                    .contains("Acceptance criteria")
                    .contains("neither pass nor fail");
        }

        @Test
        @DisplayName("latency that was never measured is a dash, never a zero")
        void unmeasuredLatencyIsNotZero() throws IOException {
            String text = textOf(dev.vortex.report.ReportFixtures.sparse());

            // "0 ms" would be a plausible-looking figure for a measurement nobody took.
            assertThat(text).doesNotContain("0 ms");
        }

        @Test
        void anOperationWithNoTrafficSaysSo() throws IOException {
            assertThat(textOf(dev.vortex.report.ReportFixtures.sparse())).contains("no traffic");
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        @DisplayName("the same evidence renders byte-identical output")
        void bytesAreIdentical() {
            // Three separate sources of variation had to be closed for this: the creation and
            // modification dates, which default to the current time, and the trailer's file
            // identifier, which the library fills with a hash of the clock and some memory
            // addresses. A report should be a function of the run, not of when it was exported.
            assertThat(exporter.export(dev.vortex.report.ReportFixtures.rich()))
                    .isEqualTo(exporter.export(dev.vortex.report.ReportFixtures.rich()));
        }

        @Test
        @DisplayName("two different runs do not collide on one identifier")
        void differentRunsDiffer() {
            assertThat(exporter.export(dev.vortex.report.ReportFixtures.rich()))
                    .isNotEqualTo(exporter.export(dev.vortex.report.ReportFixtures.failing()));
        }

        @Test
        @DisplayName("the document still parses after the identifier is rewritten")
        void rewritingTheIdentifierKeepsItValid() throws IOException {
            // The substitution edits bytes in the trailer directly, so this is the test that says
            // the cross-reference table still resolves afterwards.
            PdfReader reader = new PdfReader(exporter.export(dev.vortex.report.ReportFixtures.rich()));

            assertThat(reader.getNumberOfPages()).isPositive();
            assertThat(new PdfTextExtractor(reader).getTextFromPage(1)).contains("PASS");
            reader.close();
        }
    }

    @Test
    @DisplayName("characters outside the font are transliterated, never silently dropped")
    void unicodeSurvives() throws IOException {
        String text = textOf(dev.vortex.report.ReportFixtures.rich());

        // The arrow in a signal's movement has no WinAnsi equivalent; it must become "->" rather
        // than disappearing, which is what an unmapped glyph does by default.
        assertThat(text).contains("->");
        assertThat(text).doesNotContain("?" + "?" + "?");
    }

    // ---------------------------------------------------------------- helpers

    private String textOf(RunEvidence evidence) throws IOException {
        return String.join("\n", pagesOf(evidence));
    }

    /**
     * The document's text, one entry per page.
     *
     * <p>Extracted rather than read from the raw bytes. A PDF's content streams are compressed, so
     * scanning the file for a string would find nothing and pass for entirely the wrong reason.
     */
    private List<String> pagesOf(RunEvidence evidence) throws IOException {
        PdfReader reader = new PdfReader(exporter.export(evidence));
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            List<String> pages = new ArrayList<>();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                pages.add(extractor.getTextFromPage(page));
            }
            return pages;
        } finally {
            reader.close();
        }
    }
}
