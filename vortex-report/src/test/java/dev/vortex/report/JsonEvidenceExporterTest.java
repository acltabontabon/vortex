package dev.vortex.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vortex.core.evidence.EvidenceProvenance;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The JSON export is the one output other software reads, so its shape is a promise rather than a
 * rendering choice.
 *
 * <p>These tests treat it as a contract: the version is present, absent measurements are absent
 * keys rather than nulls, and — the one most likely to be broken by accident — none of Vortex's
 * internal serialisation shape leaks into it. The persistence mapper writes type discriminators for
 * sealed hierarchies; if any of those ever appear here, somebody has reached for the wrong mapper
 * and every consumer's parser is now coupled to a Java class name.
 */
class JsonEvidenceExporterTest {

    private final JsonEvidenceExporter exporter = new JsonEvidenceExporter();
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode export(dev.vortex.core.evidence.RunEvidence evidence) throws Exception {
        return mapper.readTree(new String(exporter.export(evidence), StandardCharsets.UTF_8));
    }

    @Nested
    @DisplayName("the contract")
    class Contract {

        @Test
        @DisplayName("the schema version is present and is the first key")
        void versionIsFirst() throws Exception {
            JsonNode root = export(ReportFixtures.rich());

            assertThat(root.get("schemaVersion").asText())
                    .isEqualTo(EvidenceProvenance.SCHEMA_VERSION);
            assertThat(root.fieldNames().next()).isEqualTo("schemaVersion");
        }

        @Test
        @DisplayName("every top-level section a consumer is promised is present")
        void everySectionIsPresent() throws Exception {
            JsonNode root = export(ReportFixtures.rich());

            // Exact, and in order: this list *is* the contract. A section appearing without this
            // test changing is a section nobody decided to publish.
            assertThat(root.fieldNames()).toIterable().containsExactly(
                    "schemaVersion", "run", "verdict", "workload", "performance", "criteria",
                    "operations", "timeline", "observability", "validity", "resources", "limits",
                    "findings", "comparison", "provenance");
        }

        @Test
        @DisplayName("no internal serialisation shape leaks into the published document")
        void noInternalTypeDiscriminatorsLeak() throws Exception {
            String json = new String(exporter.export(ReportFixtures.rich()),
                    StandardCharsets.UTF_8);

            // These are how the persistence mapper tags sealed hierarchies and typed map keys.
            // Their presence would mean the internal graph is being published.
            assertThat(json)
                    .doesNotContain("\"kind\"")
                    .doesNotContain("\"shape\"")
                    .doesNotContain("LatencyThreshold")
                    .doesNotContain("ConstantArrivalRate")
                    .doesNotContain("dev.vortex");
        }

        @Test
        @DisplayName("units are named, so no number's meaning depends on knowing the source")
        void unitsAreInTheFieldNames() throws Exception {
            JsonNode workload = export(ReportFixtures.rich()).get("workload");

            assertThat(workload.has("achievedRatePerSecond")).isTrue();
            assertThat(workload.has("configuredUnit")).isTrue();
            assertThat(export(ReportFixtures.rich()).get("performance").has("errorRatePercent"))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("absence")
    class Absence {

        @Test
        @DisplayName("something never measured is an absent key, never a null")
        void absentMeasurementsAreAbsentKeys() throws Exception {
            JsonNode root = export(ReportFixtures.sparse());

            // A consumer must not have to tell "Vortex did not measure this" from "Vortex measured
            // it as nothing".
            assertThat(root.get("timeline").isNull()).isTrue();
            assertThat(root.get("observability")).isEmpty();
            assertThat(root.get("workload").has("achievedRatePerSecond")).isFalse();
            assertThat(root.get("workload").has("deliveredFraction")).isFalse();
        }

        @Test
        @DisplayName("a run with no objectives exports an empty criteria list, not a fabricated pass")
        void noObjectives() throws Exception {
            JsonNode root = export(ReportFixtures.sparse());

            assertThat(root.get("criteria")).isEmpty();
            assertThat(root.get("verdict").get("value").asText()).isEqualTo("NOT_EVALUATED");
        }

        @Test
        void absentComparisonIsExplicitlyNull() throws Exception {
            assertThat(export(ReportFixtures.rich()).get("comparison").isNull()).isTrue();
        }
    }

    @Nested
    @DisplayName("content")
    class Content {

        @Test
        void perOperationFiguresAreCarried() throws Exception {
            JsonNode operations = export(ReportFixtures.rich()).get("operations");

            assertThat(operations).isNotEmpty();
            assertThat(operations.get(0).has("hasTraffic")).isTrue();
        }

        @Test
        @DisplayName("observability carries its provenance and its start/peak/end movement")
        void observabilityKeepsProvenance() throws Exception {
            JsonNode signals = export(ReportFixtures.rich()).get("observability");

            JsonNode pool = signals.get(0);
            assertThat(pool.get("value").asDouble()).isEqualTo(94.0);
            assertThat(pool.get("trace").get("start").asDouble()).isEqualTo(31.0);
            assertThat(pool.get("provenance").get("provider").asText()).isEqualTo("actuator");

            // The second signal has no provenance, and must simply not carry the key.
            assertThat(signals.get(1).has("provenance")).isFalse();
        }

        @Test
        void findingsCarryTheirCitations() throws Exception {
            JsonNode findings = export(ReportFixtures.rich()).get("findings");

            assertThat(findings).isNotEmpty();
            findings.forEach(finding -> assertThat(finding.get("evidence")).isNotEmpty());
        }

        @Test
        void provenanceAnswersWhatProducedThis() throws Exception {
            JsonNode provenance = export(ReportFixtures.rich()).get("provenance");

            assertThat(provenance.get("engineVersion").asText()).isEqualTo("k6 v1.3.0");
            assertThat(provenance.get("configurationHash").asText()).startsWith("SHA-256:");
            assertThat(provenance.get("reproductionCommand").asText()).startsWith("workload ");
            assertThat(provenance.get("generatedAt").asText()).startsWith("2026-08-21T11:00");
        }
    }

    @Test
    @DisplayName("the same evidence exports byte-identical output, so a diff means a real change")
    void exportIsDeterministic() {
        byte[] first = exporter.export(ReportFixtures.rich());
        byte[] second = exporter.export(ReportFixtures.rich());

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("line endings are pinned, so the same run does not diff across platforms")
    void lineEndingsArePinned() {
        String json = new String(exporter.export(ReportFixtures.rich()), StandardCharsets.UTF_8);

        assertThat(json).contains("\n").doesNotContain("\r");
    }

    @Test
    @DisplayName("key order is stable, because insertion order is the contract")
    void keyOrderIsStable() throws Exception {
        List<String> first = fieldNames(export(ReportFixtures.rich()));
        List<String> second = fieldNames(export(ReportFixtures.rich()));

        assertThat(first).isEqualTo(second);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
