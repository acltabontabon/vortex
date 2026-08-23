package dev.vortex.app.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.evidence.RunEvidence;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The Markdown export is read by people, in a narrow column, without Vortex open.
 *
 * <p>Golden files are used here, which they deliberately are not for the HTML pages. The objection
 * to snapshotting a page is that it breaks on every cosmetic change and a suite that breaks for
 * cosmetic reasons stops being read. Markdown has no stylesheet to churn against: if this output
 * changes, either somebody changed the wording on purpose or something is wrong, and both are worth
 * a look. The semantic assertions below still carry the meaning, so a golden mismatch is never the
 * only thing that fails.
 */
class EvidenceMarkdownWriterTest {

    private final EvidenceMarkdownWriter exporter = new EvidenceMarkdownWriter();

    private String export(RunEvidence evidence) {
        return new String(exporter.export(evidence), StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("what a reader sees first")
    class LeadingWithTheAnswer {

        @Test
        @DisplayName("the verdict is in the first line, before anything has to be read")
        void verdictLeads() {
            assertThat(export(EvidenceFixtures.rich()).lines().findFirst().orElseThrow())
                    .startsWith("## Vortex performance result")
                    .contains("PASS");
        }

        @Test
        @DisplayName("a failure says so in the first line too")
        void failureLeads() {
            assertThat(export(EvidenceFixtures.failing()).lines().findFirst().orElseThrow())
                    .contains("FAIL");
        }

        @Test
        @DisplayName("the conditions travel with the numbers, never in a footnote")
        void qualificationsAppearBeforeTheFigures() {
            String markdown = export(EvidenceFixtures.rich());

            // A throughput figure from a run against mocked dependencies means something different,
            // and a reader must meet that caveat before they meet the number.
            assertThat(markdown.indexOf("cannot establish production capacity"))
                    .isLessThan(markdown.indexOf("### Performance"));
        }
    }

    @Nested
    @DisplayName("sections")
    class Sections {

        @Test
        void appearInTheOrderAReaderNeedsThem() {
            String markdown = export(EvidenceFixtures.rich());

            assertThat(indexOfEach(markdown, List.of(
                    "## Vortex performance result", "### Workload", "### Performance",
                    "### Acceptance criteria", "### Operations", "### Over time",
                    "### From the service under test", "### Findings",
                    "<summary>Reproducing this run</summary>")))
                    .isSorted();
        }

        @Test
        @DisplayName("a section with no data is absent, rather than present and empty")
        void emptySectionsAreOmitted() {
            String markdown = export(EvidenceFixtures.sparse());

            assertThat(markdown)
                    .doesNotContain("### Over time")
                    .doesNotContain("### From the service under test");
        }
    }

    @Nested
    @DisplayName("the things a report must never let a reader misread")
    class Honesty {

        @Test
        @DisplayName("expected and achieved workload sit side by side")
        void offeredAndAchievedAreBothShown() {
            String markdown = export(EvidenceFixtures.rich());

            assertThat(markdown).contains("| | Configured | Achieved |");
            assertThat(markdown).contains("20 requests/sec").contains("19.8 requests/sec");
        }

        @Test
        @DisplayName("an operation that issued nothing shows no traffic, never zeroes")
        void noTrafficIsNotAPerfectScore() {
            String markdown = export(EvidenceFixtures.sparse());

            // "0 ms, 0% errors" would read as a flawless result for an operation that never ran.
            assertThat(markdown).contains("no traffic");
        }

        @Test
        @DisplayName("latency that was never measured produces no maximum at all")
        void unmeasuredLatencyIsNotZero() {
            String markdown = export(EvidenceFixtures.sparse());

            assertThat(markdown).doesNotContain("| max | 0 ms |");
        }

        @Test
        @DisplayName("a run with no objectives explains itself instead of showing an empty table")
        void noObjectivesIsExplained() {
            assertThat(export(EvidenceFixtures.sparse()))
                    .contains("neither pass nor fail");
        }

        @Test
        @DisplayName("a period where nothing was measured stays blank in the sparkline")
        void gapsAreNotBridged() {
            String markdown = export(EvidenceFixtures.rich());

            String throughput = markdown.lines()
                    .filter(line -> line.contains("Throughput"))
                    .findFirst().orElseThrow();

            // The fixture has a four-bucket hole. Closing it up would invent a measurement, which
            // is the same rule the charts follow.
            assertThat(throughput).contains("  ");
        }
    }

    @Nested
    @DisplayName("golden")
    class Golden {

        @Test
        void aCompleteRunRendersExactly() throws IOException {
            assertThat(export(EvidenceFixtures.rich())).isEqualTo(golden("rich-run.md"));
        }

        @Test
        void aSparseRunRendersExactly() throws IOException {
            assertThat(export(EvidenceFixtures.sparse())).isEqualTo(golden("sparse-run.md"));
        }

        private String golden(String name) throws IOException {
            try (InputStream stream = getClass().getResourceAsStream("/golden/" + name)) {
                if (stream == null) {
                    throw new IllegalStateException("Missing golden file: " + name);
                }
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    @Test
    @DisplayName("the same evidence renders identically, so a diff means a real change")
    void renderingIsDeterministic() {
        assertThat(export(EvidenceFixtures.rich())).isEqualTo(export(EvidenceFixtures.rich()));
    }

    private static List<Integer> indexOfEach(String haystack, List<String> needles) {
        return needles.stream().map(needle -> {
            int index = haystack.indexOf(needle);
            assertThat(index).as("section '%s' is missing entirely", needle).isNotNegative();
            return index;
        }).toList();
    }
}
