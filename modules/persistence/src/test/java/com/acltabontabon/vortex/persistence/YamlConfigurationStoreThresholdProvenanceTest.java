package com.acltabontabon.vortex.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.port.ConfigurationStore;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.shared.Percentile;
import com.acltabontabon.vortex.core.threshold.LatencyThreshold;
import com.acltabontabon.vortex.core.threshold.ThresholdSet;
import com.acltabontabon.vortex.core.threshold.recommend.EvidenceQuality;
import com.acltabontabon.vortex.core.threshold.recommend.ThresholdProvenance;
import com.acltabontabon.vortex.core.threshold.recommend.ThresholdSetProvenance;
import com.acltabontabon.vortex.core.threshold.recommend.ThresholdSource;
import com.acltabontabon.vortex.core.workload.Observation;
import com.acltabontabon.vortex.persistence.config.YamlConfigurationStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Threshold provenance is snapshotted evidence, not just another value — this checks it survives a
 * write/read round trip exactly, and that a plain manual threshold set writes no {@code evidence:}
 * block at all.
 */
class YamlConfigurationStoreThresholdProvenanceTest {

    private final YamlConfigurationStore store = new YamlConfigurationStore();

    @Test
    void derivedProvenanceRoundTripsThroughRenderAndParse() {
        LatencyThreshold threshold = LatencyThreshold.of(Percentile.P95, Duration.ofMillis(550));
        ThresholdProvenance provenance = ThresholdProvenance.derived(
                ThresholdSource.PRODUCTION_BASELINE, "Prometheus (checkout-service)",
                Observation.over(Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-31T00:00:00Z")),
                "Your observed production 620 ms x 1.10 = 682 ms, rounded to 700 ms.",
                EvidenceQuality.STRONG, "exec-184", Instant.parse("2026-08-29T00:00:00Z"));

        ProjectConfiguration configuration = ProjectConfiguration.empty()
                .withThresholds(ThresholdSet.of(threshold),
                        new ThresholdSetProvenance(Map.of(threshold.id(), provenance)));

        String yaml = store.render(configuration);
        ConfigurationStore.LoadResult result = store.parse(yaml, "vortex.yaml");

        assertThat(result.problems()).isEmpty();
        ThresholdProvenance roundTripped = result.configuration().thresholdProvenance()
                .forThreshold(threshold.id()).orElseThrow();

        assertThat(roundTripped.source()).isEqualTo(ThresholdSource.PRODUCTION_BASELINE);
        assertThat(roundTripped.detail()).isEqualTo("Prometheus (checkout-service)");
        assertThat(roundTripped.observedAt().fromIfPresent()).contains(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(roundTripped.observedAt().toIfPresent()).contains(Instant.parse("2026-07-31T00:00:00Z"));
        assertThat(roundTripped.derivation()).isEqualTo("Your observed production 620 ms x 1.10 = 682 ms, rounded to 700 ms.");
        assertThat(roundTripped.quality()).isEqualTo(EvidenceQuality.STRONG);
        assertThat(roundTripped.baselineExecutionId()).isEqualTo("exec-184");
        assertThat(roundTripped.derivedAt()).isEqualTo(Instant.parse("2026-08-29T00:00:00Z"));
    }

    @Test
    void manualProvenanceRoundTrips() {
        LatencyThreshold threshold = LatencyThreshold.of(Percentile.P95, Duration.ofMillis(550));
        Instant derivedAt = Instant.parse("2026-08-29T00:00:00Z");
        ThresholdProvenance provenance = ThresholdProvenance.manual(derivedAt);

        ProjectConfiguration configuration = ProjectConfiguration.empty()
                .withThresholds(ThresholdSet.of(threshold),
                        new ThresholdSetProvenance(Map.of(threshold.id(), provenance)));

        String yaml = store.render(configuration);
        ConfigurationStore.LoadResult result = store.parse(yaml, "vortex.yaml");

        assertThat(result.problems()).isEmpty();
        ThresholdProvenance roundTripped = result.configuration().thresholdProvenance()
                .forThreshold(threshold.id()).orElseThrow();
        assertThat(roundTripped.source()).isEqualTo(ThresholdSource.MANUAL_OBJECTIVE);
        assertThat(roundTripped.derivedAt()).isEqualTo(derivedAt);
    }

    @Test
    void aThresholdSetWithNoRecordedEvidenceWritesNoEvidenceBlockAtAll() {
        ThresholdSet thresholds = ThresholdSet.of(LatencyThreshold.of(Percentile.P95, Duration.ofMillis(550)));
        ProjectConfiguration configuration = ProjectConfiguration.empty().withThresholds(thresholds);

        String yaml = store.render(configuration);

        assertThat(yaml).doesNotContain("evidence:");
    }

    @Test
    void legacyFilesWithNoEvidenceBlockParseWithEmptyProvenance() {
        String yaml = """
                version: 1
                service:
                  name: checkout-service
                thresholds:
                  latency:
                    p95: 500ms
                """;

        ConfigurationStore.LoadResult result = store.parse(yaml, "vortex.yaml");

        assertThat(result.problems()).isEmpty();
        assertThat(result.configuration().thresholdProvenance().isEmpty()).isTrue();
    }

    @Test
    void aThresholdRemovedFromTheSetLeavesNoOrphanedEvidenceEntry() {
        LatencyThreshold p95 = LatencyThreshold.of(Percentile.P95, Duration.ofMillis(550));
        ThresholdProvenance provenance = ThresholdProvenance.manual(Instant.parse("2026-08-29T00:00:00Z"));
        // Provenance recorded for a threshold id that is not actually in the configured set.
        ProjectConfiguration configuration = ProjectConfiguration.empty()
                .withThresholds(ThresholdSet.of(p95),
                        new ThresholdSetProvenance(Map.of("latency.p99", provenance)));

        String yaml = store.render(configuration);

        assertThat(yaml).doesNotContain("evidence:");
    }
}
