package com.acltabontabon.vortex.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acltabontabon.vortex.core.application.ProjectService;
import com.acltabontabon.vortex.core.application.ThresholdHistoryEntry;
import com.acltabontabon.vortex.core.application.ThresholdHistoryService;
import com.acltabontabon.vortex.core.application.ThresholdRecommendationService;
import com.acltabontabon.vortex.core.port.Clock;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.threshold.recommend.ThresholdEvidence;
import com.acltabontabon.vortex.core.threshold.recommend.ThresholdRecommender;
import com.acltabontabon.vortex.core.threshold.recommend.ThresholdSanityChecker;
import com.acltabontabon.vortex.core.workload.Workload;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The Threshold Assistant's backend surface — evidence-backed recommendations, live sanity checking,
 * saving a workload's own thresholds, and reading a threshold's history.
 */
@WebMvcTest(controllers = ThresholdsApiController.class)
@org.springframework.context.annotation.Import(ThresholdsApiControllerTest.RealDeterministicBeans.class)
@DisplayName("threshold assistant")
class ThresholdsApiControllerTest {

    private static final String SERVICE = "checkout";
    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProjectService projects;
    @MockitoBean
    private ThresholdRecommendationService evidence;
    @MockitoBean
    private ThresholdHistoryService history;
    @MockitoBean
    private Clock clock;

    /**
     * The recommender and sanity checker are pure, deterministic, dependency-free domain classes —
     * real instances here, the same discipline {@code SettingsApiControllerTest} uses for its own
     * lightweight settings beans, so a test asserting "contradictory percentiles are rejected" is
     * exercising the real rule rather than a mock's stubbed answer.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class RealDeterministicBeans {
        @org.springframework.context.annotation.Bean
        ThresholdRecommender thresholdRecommender() {
            return ThresholdRecommender.defaults();
        }

        @org.springframework.context.annotation.Bean
        ThresholdSanityChecker thresholdSanityChecker() {
            return new ThresholdSanityChecker();
        }
    }

    @BeforeEach
    void stubClock() {
        when(clock.now()).thenReturn(NOW);
        when(projects.configuration(ProjectId.of(SERVICE))).thenReturn(ProjectConfiguration.empty());
    }

    @Test
    @DisplayName("reads back a workload's own saved thresholds and their evidence")
    void readsBackSavedThresholdsAndEvidence() throws Exception {
        var threshold = com.acltabontabon.vortex.core.threshold.LatencyThreshold.of(
                com.acltabontabon.vortex.core.shared.Percentile.P95, Duration.ofMillis(550));
        var provenance = com.acltabontabon.vortex.core.threshold.recommend.ThresholdProvenance.manual(NOW);
        Workload workload = averageLoadWorkload().withThresholds(
                com.acltabontabon.vortex.core.threshold.ThresholdSet.of(threshold),
                new com.acltabontabon.vortex.core.threshold.recommend.ThresholdSetProvenance(
                        java.util.Map.of(threshold.id(), provenance)));
        when(projects.configuration(ProjectId.of(SERVICE)))
                .thenReturn(ProjectConfiguration.empty().withWorkload(workload));

        mvc.perform(get("/api/services/{id}/tests/{name}/thresholds", SERVICE, "average_load"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thresholds[0].id").value("latency.p95"))
                .andExpect(jsonPath("$.provenance.['latency.p95'].source").value("MANUAL_OBJECTIVE"));
    }

    @Test
    @DisplayName("reading thresholds for a missing workload is a 404")
    void readingThresholdsForAMissingWorkloadIs404() throws Exception {
        mvc.perform(get("/api/services/{id}/tests/{name}/thresholds", SERVICE, "no-such-workload"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("recommends against production evidence for a latency percentile")
    void recommendsAgainstProductionEvidence() throws Exception {
        ThresholdEvidence thresholdEvidence = new ThresholdEvidence(
                ThresholdEvidence.ProductionEvidence.latency(Duration.ofMillis(620),
                        com.acltabontabon.vortex.core.workload.Observation.unknown(), "prometheus", true, true),
                List.of());
        when(evidence.latencyEvidence(any(), any(), any(), any())).thenReturn(thresholdEvidence);

        mvc.perform(get("/api/services/{id}/tests/threshold-recommendation", SERVICE)
                        .param("workload", "average_load").param("metric", "LATENCY").param("percentile", "95"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.production.displayValue").value("620 ms"))
                .andExpect(jsonPath("$.recommendations").isNotEmpty())
                .andExpect(jsonPath("$.recommendations[0].label").value("Balanced"));
    }

    @Test
    @DisplayName("a latency recommendation with no percentile is rejected")
    void latencyRecommendationRequiresPercentile() throws Exception {
        mvc.perform(get("/api/services/{id}/tests/threshold-recommendation", SERVICE)
                        .param("workload", "average_load").param("metric", "LATENCY"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("recommendation works with no workload named at all — the normal, service-level case")
    void recommendationWorksWithoutAWorkload() throws Exception {
        ThresholdEvidence thresholdEvidence = new ThresholdEvidence(
                ThresholdEvidence.ProductionEvidence.latency(Duration.ofMillis(620),
                        com.acltabontabon.vortex.core.workload.Observation.unknown(), "prometheus", true, true),
                List.of());
        when(evidence.latencyEvidence(any(), any(), any(), any())).thenReturn(thresholdEvidence);

        mvc.perform(get("/api/services/{id}/tests/threshold-recommendation", SERVICE)
                        .param("metric", "LATENCY").param("percentile", "95"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.production.displayValue").value("620 ms"));
    }

    @Test
    @DisplayName("reads back the service-level objectives and their evidence")
    void readsBackServiceLevelThresholdsAndEvidence() throws Exception {
        var threshold = com.acltabontabon.vortex.core.threshold.LatencyThreshold.of(
                com.acltabontabon.vortex.core.shared.Percentile.P95, Duration.ofMillis(550));
        var provenance = com.acltabontabon.vortex.core.threshold.recommend.ThresholdProvenance.manual(NOW);
        ProjectConfiguration configuration = ProjectConfiguration.empty().withThresholds(
                com.acltabontabon.vortex.core.threshold.ThresholdSet.of(threshold),
                new com.acltabontabon.vortex.core.threshold.recommend.ThresholdSetProvenance(
                        java.util.Map.of(threshold.id(), provenance)));
        when(projects.configuration(ProjectId.of(SERVICE))).thenReturn(configuration);

        mvc.perform(get("/api/services/{id}/thresholds", SERVICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thresholds[0].id").value("latency.p95"))
                .andExpect(jsonPath("$.provenance.['latency.p95'].source").value("MANUAL_OBJECTIVE"));
    }

    @Test
    @DisplayName("saves service-level objectives, replacing the project's threshold set")
    void savesServiceLevelThresholds() throws Exception {
        mvc.perform(put("/api/services/{id}/thresholds", SERVICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"thresholds": [{"kind": "LATENCY", "percentile": 95, "maxMillis": 550}],
                                 "provenance": {"latency.p95": {"source": "MANUAL_OBJECTIVE"}}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        var captor = org.mockito.ArgumentCaptor.forClass(ProjectConfiguration.class);
        verify(projects).saveConfiguration(org.mockito.ArgumentMatchers.eq(ProjectId.of(SERVICE)), captor.capture());
        assertThat(captor.getValue().thresholds().byId("latency.p95")).isPresent();
        assertThat(captor.getValue().thresholdProvenance().forThreshold("latency.p95")).isPresent();
    }

    @Test
    @DisplayName("a contradictory service-level set is rejected, and nothing is persisted")
    void savingContradictoryServiceLevelSetIsRejected() throws Exception {
        mvc.perform(put("/api/services/{id}/thresholds", SERVICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"thresholds": [
                                  {"kind": "LATENCY", "percentile": 95, "maxMillis": 500},
                                  {"kind": "LATENCY", "percentile": 99, "maxMillis": 400}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false));

        verify(projects, org.mockito.Mockito.never()).saveConfiguration(any(), any());
    }

    @Test
    @DisplayName("contradictory percentiles block the sanity check")
    void contradictoryPercentilesBlockSave() throws Exception {
        mvc.perform(post("/api/services/{id}/tests/thresholds/sanity-check", SERVICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"thresholds": [
                                  {"kind": "LATENCY", "percentile": 95, "maxMillis": 500},
                                  {"kind": "LATENCY", "percentile": 99, "maxMillis": 400}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocksSave").value(true))
                .andExpect(jsonPath("$.findings[0].severity").value("INVALID"));
    }

    @Test
    @DisplayName("a proportionate threshold produces no findings")
    void proportionateThresholdIsClean() throws Exception {
        mvc.perform(post("/api/services/{id}/tests/thresholds/sanity-check", SERVICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"thresholds": [{"kind": "LATENCY", "percentile": 95, "maxMillis": 650}],
                                 "productionByThresholdId": {"latency.p95": 620}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocksSave").value(false))
                .andExpect(jsonPath("$.findings").isEmpty());
    }

    @Test
    @DisplayName("sanity-check returns the live comparison sentence, preferring production over baseline")
    void sanityCheckReturnsComparisonText() throws Exception {
        mvc.perform(post("/api/services/{id}/tests/thresholds/sanity-check", SERVICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"thresholds": [{"kind": "LATENCY", "percentile": 95, "maxMillis": 300}],
                                 "productionByThresholdId": {"latency.p95": 625},
                                 "baselineByThresholdId": {"latency.p95": 500}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparisons.['latency.p95']").value("52% stricter than current production behavior."));
    }

    @Test
    @DisplayName("p95 and p99 objectives in the same request compare against their own distinct production references")
    void distinctPercentilesUseDistinctReferences() throws Exception {
        mvc.perform(post("/api/services/{id}/tests/thresholds/sanity-check", SERVICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"thresholds": [
                                  {"kind": "LATENCY", "percentile": 95, "maxMillis": 620},
                                  {"kind": "LATENCY", "percentile": 99, "maxMillis": 900}
                                ],
                                 "productionByThresholdId": {"latency.p95": 620, "latency.p99": 900}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comparisons.['latency.p95']").value("This roughly matches current production behavior."))
                .andExpect(jsonPath("$.comparisons.['latency.p99']").value("This roughly matches current production behavior."))
                .andExpect(jsonPath("$.blocksSave").value(false));
    }

    @Test
    @DisplayName("saving a contradictory set is rejected with the domain message, and nothing is persisted")
    void savingContradictorySetIsRejected() throws Exception {
        when(projects.configuration(ProjectId.of(SERVICE))).thenReturn(
                ProjectConfiguration.empty().withWorkload(averageLoadWorkload()));

        mvc.perform(put("/api/services/{id}/tests/{name}/thresholds", SERVICE, "average_load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"thresholds": [
                                  {"kind": "LATENCY", "percentile": 95, "maxMillis": 500},
                                  {"kind": "LATENCY", "percentile": 99, "maxMillis": 400}
                                ]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false));

        verify(projects, org.mockito.Mockito.never()).saveConfiguration(any(), any());
    }

    @Test
    @DisplayName("saving a valid set persists the thresholds and their evidence")
    void savingAValidSetPersists() throws Exception {
        when(projects.configuration(ProjectId.of(SERVICE))).thenReturn(
                ProjectConfiguration.empty().withWorkload(averageLoadWorkload()));

        mvc.perform(put("/api/services/{id}/tests/{name}/thresholds", SERVICE, "average_load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"thresholds": [{"kind": "LATENCY", "percentile": 95, "maxMillis": 550}],
                                 "provenance": {"latency.p95": {"source": "MANUAL_OBJECTIVE"}}}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        var captor = org.mockito.ArgumentCaptor.forClass(ProjectConfiguration.class);
        verify(projects).saveConfiguration(org.mockito.ArgumentMatchers.eq(ProjectId.of(SERVICE)), captor.capture());
        Workload saved = captor.getValue().workloadByName("average_load").orElseThrow();
        assertThat(saved.thresholds().byId("latency.p95")).isPresent();
        assertThat(saved.thresholdProvenance().forThreshold("latency.p95")).isPresent();
    }

    @Test
    @DisplayName("saving thresholds for a workload that does not exist is a 404")
    void savingForAMissingWorkloadIs404() throws Exception {
        mvc.perform(put("/api/services/{id}/tests/{name}/thresholds", SERVICE, "no-such-workload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thresholds\": []}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("threshold history reads through to the history service")
    void thresholdHistoryReadsThroughToTheService() throws Exception {
        when(history.history(ProjectId.of(SERVICE), "average_load", "latency.p95"))
                .thenReturn(List.of(new ThresholdHistoryEntry("run-2", "550 ms", NOW),
                        new ThresholdHistoryEntry("run-1", "700 ms", NOW.minusSeconds(60))));

        mvc.perform(get("/api/services/{id}/tests/{name}/thresholds/history", SERVICE, "average_load")
                        .param("thresholdId", "latency.p95"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].value").value("550 ms"))
                .andExpect(jsonPath("$[1].value").value("700 ms"));
    }

    @Test
    @DisplayName("the narrative is a deterministic, templated summary")
    void narrativeIsDeterministic() throws Exception {
        mvc.perform(post("/api/services/{id}/tests/thresholds/narrative", SERVICE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"thresholds": [{"kind": "LATENCY", "percentile": 95, "maxMillis": 550},
                                                 {"kind": "ERROR_RATE", "maxErrorPercent": 1}]}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.narrative").value(org.hamcrest.Matchers.containsString("550 ms")))
                .andExpect(jsonPath("$.breakpointCondition").value(org.hamcrest.Matchers.containsString("OR")));
    }

    /** Minimal workload — this controller does not care about traffic shape, only the name. */
    private static Workload averageLoadWorkload() {
        return new Workload(com.acltabontabon.vortex.core.shared.WorkloadId.of("average_load"),
                "average_load", "", "", com.acltabontabon.vortex.core.workload.TestType.AVERAGE_LOAD,
                com.acltabontabon.vortex.core.workload.OperationMix.single(
                        com.acltabontabon.vortex.core.shared.OperationId.of("getOrder")),
                com.acltabontabon.vortex.core.workload.ConstantArrivalRateShape.of(20, Duration.ofMinutes(10)),
                com.acltabontabon.vortex.core.threshold.ThresholdSet.empty(),
                com.acltabontabon.vortex.core.workload.WorkloadSource.manual(), java.util.Map.of());
    }
}
