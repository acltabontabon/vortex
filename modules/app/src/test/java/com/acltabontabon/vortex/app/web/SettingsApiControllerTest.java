package com.acltabontabon.vortex.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acltabontabon.vortex.ai.AiSettings;
import com.acltabontabon.vortex.ai.OllamaAvailability;
import com.acltabontabon.vortex.app.VortexProperties;
import com.acltabontabon.vortex.app.config.AiModelPreferenceStore;
import com.acltabontabon.vortex.app.config.LoadGeneratorBudgetPreferenceStore;
import com.acltabontabon.vortex.app.config.LoadGeneratorBudgetSettings;
import com.acltabontabon.vortex.app.service.LocalLabRunner;
import com.acltabontabon.vortex.core.evidence.HostShape;
import com.acltabontabon.vortex.core.port.LocalLab;
import com.acltabontabon.vortex.core.port.PerformanceAssistant;
import com.acltabontabon.vortex.core.port.PerformanceEngine;
import com.acltabontabon.vortex.core.resource.LoadGeneratorResourceBudget;
import com.acltabontabon.vortex.core.resource.LoadGeneratorResourceBudgetResolver;
import com.acltabontabon.vortex.core.resource.ResolvedLoadGeneratorBudget;
import com.acltabontabon.vortex.core.target.CpuAllocation;
import com.acltabontabon.vortex.core.target.MemoryAllocation;
import com.acltabontabon.vortex.core.target.ResourceEnvelopeRequest;
import com.acltabontabon.vortex.core.workload.RateAllocator;
import com.acltabontabon.vortex.dynatrace.DynatraceMcpAvailability;
import com.acltabontabon.vortex.dynatrace.DynatraceMcpClientFactory;
import com.acltabontabon.vortex.dynatrace.DynatraceMcpConnectionTest;
import com.acltabontabon.vortex.dynatrace.DynatraceMcpFailureCategory;
import com.acltabontabon.vortex.dynatrace.DynatraceMcpSettings;
import com.acltabontabon.vortex.app.config.DynatraceMcpPreferenceStore;
import com.acltabontabon.vortex.persistence.VortexWorkspace;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Settings' data, and the two actions it offers — the model-choice picker and its own connection
 * check — as JSON.
 */
@WebMvcTest(controllers = SettingsApiController.class)
@Import({Display.class, WorkloadView.class, WorkloadDiagramRenderer.class,
        LoadAxisRenderer.class, RateAllocator.class,
        SettingsApiControllerTest.RealAiSettings.class})
class SettingsApiControllerTest {

    @TestConfiguration
    static class RealAiSettings {
        @Bean
        AiSettings aiSettings() {
            return new AiSettings("ollama", "http://localhost:11434", "qwen3:4b", null, false);
        }

        @Bean
        LoadGeneratorBudgetSettings loadGeneratorBudgetSettings() {
            return LoadGeneratorBudgetSettings.seeded(LoadGeneratorResourceBudget.automatic());
        }

        @Bean
        DynatraceMcpSettings dynatraceMcpSettings() {
            return new DynatraceMcpSettings(false, "", null, null);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiSettings aiSettings;

    @Autowired
    private LoadGeneratorBudgetSettings loadGeneratorBudgetSettings;

    @Autowired
    private DynatraceMcpSettings dynatraceMcpSettings;

    @MockitoBean
    private VortexProperties properties;

    @MockitoBean
    private PerformanceEngine engine;

    @MockitoBean
    private PerformanceAssistant assistant;

    @MockitoBean
    private OllamaAvailability ollama;

    @MockitoBean
    private AiModelPreferenceStore aiModelPreferences;

    @MockitoBean
    private LocalLabRunner lab;

    @MockitoBean
    private VortexWorkspace workspace;

    @MockitoBean
    private LoadGeneratorBudgetPreferenceStore loadGeneratorBudgetPreferences;

    @MockitoBean
    private LoadGeneratorResourceBudgetResolver loadGeneratorResourceBudgetResolver;

    @MockitoBean
    private DynatraceMcpPreferenceStore dynatraceMcpPreferences;

    @MockitoBean
    private DynatraceMcpAvailability dynatraceMcpAvailability;

    @MockitoBean
    private DynatraceMcpClientFactory dynatraceMcpClients;

    @MockitoBean
    private DynatraceMcpConnectionTest dynatraceMcpConnectionTest;

    private static ResolvedLoadGeneratorBudget resolvedAutomaticBudget() {
        return new ResolvedLoadGeneratorBudget(
                LoadGeneratorResourceBudget.BudgetMode.AUTOMATIC,
                new ResourceEnvelopeRequest(CpuAllocation.ofMillicores(4000),
                        MemoryAllocation.ofMebibytes(4096)),
                new HostShape("Mac OS X", "15.6", "aarch64", 12, 34_359_738_368L),
                new ResourceEnvelopeRequest(CpuAllocation.ofMillicores(1800),
                        MemoryAllocation.ofMebibytes(3277)),
                new ResourceEnvelopeRequest(CpuAllocation.ofMillicores(5100),
                        MemoryAllocation.ofMebibytes(14_746)),
                true);
    }

    @BeforeEach
    void wiring() {
        dynatraceMcpSettings.reconfigure(false, "", null);
        when(properties.version()).thenReturn("0.1.0-SNAPSHOT");
        when(properties.engine()).thenReturn(
                new VortexProperties.Engine("local", "k6", "docker", "grafana/k6:1.3.0", true, null));
        when(engine.availability())
                .thenReturn(PerformanceEngine.EngineAvailability.ready("k6 v1.3.0"));
        when(assistant.availability())
                .thenReturn(PerformanceAssistant.Availability.ready("ollama", "qwen3:4b"));
        when(ollama.installedModels()).thenReturn(List.of("qwen3:4b", "llama3.2:3b"));
        when(lab.status()).thenReturn(new LocalLab.LabStatus(true, true, true, "24.0.0", ""));
        when(workspace.root()).thenReturn(Path.of("/tmp/vortex-workspace"));
        when(loadGeneratorResourceBudgetResolver.resolve(any(), anyBoolean()))
                .thenReturn(resolvedAutomaticBudget());
        when(dynatraceMcpAvailability.check()).thenReturn(
                new DynatraceMcpAvailability.Availability(false, "Dynatrace MCP is not enabled.",
                        "Turn it on and set the endpoint under Settings."));
    }

    @Test
    void reportsEverythingTheSettingsPageShows() throws Exception {
        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vortexVersion").value("0.1.0-SNAPSHOT"))
                .andExpect(jsonPath("$.engine.runner").value("local"))
                .andExpect(jsonPath("$.engine.usesDocker").value(false))
                .andExpect(jsonPath("$.engineAvailability.available").value(true))
                .andExpect(jsonPath("$.aiSettings.model").value("qwen3:4b"))
                .andExpect(jsonPath("$.aiAvailability.available").value(true))
                .andExpect(jsonPath("$.installedModels[0]").value("qwen3:4b"))
                .andExpect(jsonPath("$.labStatus.usable").value(true))
                .andExpect(jsonPath("$.workspacePath").value("/tmp/vortex-workspace"))
                .andExpect(jsonPath("$.loadGenerator.configured.mode").value("automatic"))
                .andExpect(jsonPath("$.loadGenerator.configured.cpuMillicores").doesNotExist())
                .andExpect(jsonPath("$.loadGenerator.effective.mode").value("automatic"))
                .andExpect(jsonPath("$.loadGenerator.effective.allocation.cpuMillicores").value(4000))
                .andExpect(jsonPath("$.loadGenerator.effective.detectedHost.availableProcessors")
                        .value(12))
                .andExpect(jsonPath("$.loadGenerator.effective.colocatedWithManagedSut").value(true))
                .andExpect(jsonPath("$.loadGenerator.automaticPreview.allocation.cpuMillicores")
                        .value(4000))
                .andExpect(jsonPath("$.dynatraceMcp.enabled").value(false))
                .andExpect(jsonPath("$.dynatraceMcpAvailability.available").value(false));
    }

    @Test
    void retryingReportsWhatItFound() throws Exception {
        when(assistant.availability())
                .thenReturn(PerformanceAssistant.Availability.ready("ollama", "qwen3:4b"));

        mockMvc.perform(post("/api/settings/ai/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(true))
                .andExpect(jsonPath("$.message").value("Connected to ollama using qwen3:4b."));

        verify(ollama).refresh();
    }

    @Test
    void retryingReportsTheProblemWhenUnavailable() throws Exception {
        when(assistant.availability()).thenReturn(PerformanceAssistant.Availability.unavailable(
                "ollama", "Ollama is not running.", "Start it with `ollama serve`."));

        mockMvc.perform(post("/api/settings/ai/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(false))
                .andExpect(jsonPath("$.message").value("Ollama is not running."));
    }

    @Test
    void choosingAModelSavesItAndTakesEffectImmediately() throws Exception {
        mockMvc.perform(post("/api/settings/ai/model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"llama3.2:3b"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Now using llama3.2:3b."));

        assertThat(aiSettings.model()).isEqualTo("llama3.2:3b");
        verify(aiModelPreferences).saveModel("llama3.2:3b");
        verify(ollama).refresh();
    }

    @Test
    void clearingTheModelIsReportedDistinctly() throws Exception {
        mockMvc.perform(post("/api/settings/ai/model")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":""}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("No model selected."));

        assertThat(aiSettings.model()).isEmpty();
    }

    @Test
    void choosingACustomLoadGeneratorBudgetSavesItAndTakesEffectImmediately() throws Exception {
        mockMvc.perform(post("/api/settings/load-generator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"custom","cpuMillicores":2000,"memoryMebibytes":2048}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Now using a custom load generator budget."));

        var current = loadGeneratorBudgetSettings.current();
        assertThat(current.mode()).isEqualTo(LoadGeneratorResourceBudget.BudgetMode.CUSTOM);
        assertThat(current.envelope().cpuIfPresent()).contains(CpuAllocation.ofMillicores(2000));
        assertThat(current.envelope().memoryIfPresent()).contains(MemoryAllocation.ofMebibytes(2048));
        verify(loadGeneratorBudgetPreferences).saveBudget(current);
    }

    @Test
    void choosingAutomaticNeedsNoValues() throws Exception {
        mockMvc.perform(post("/api/settings/load-generator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"automatic"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Now using an automatic load generator budget."));

        assertThat(loadGeneratorBudgetSettings.current().mode())
                .isEqualTo(LoadGeneratorResourceBudget.BudgetMode.AUTOMATIC);
    }

    @Test
    void customWithoutBothValuesIsRejected() throws Exception {
        mockMvc.perform(post("/api/settings/load-generator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"custom","cpuMillicores":2000}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customBelowTheViableMemoryFloorIsRejected() throws Exception {
        mockMvc.perform(post("/api/settings/load-generator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"custom","cpuMillicores":2000,"memoryMebibytes":32}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customWithNonPositiveCpuIsRejected() throws Exception {
        mockMvc.perform(post("/api/settings/load-generator")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"custom","cpuMillicores":0,"memoryMebibytes":2048}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void savingDynatraceMcpTakesEffectImmediatelyAndPersists() throws Exception {
        mockMvc.perform(post("/api/settings/dynatrace-mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"endpoint":"https://dynatrace-mcp.internal/mcp",
                                 "defaultWindow":"30d"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(
                        "Saved. Test the connection, then map a service to a Dynatrace entity."));

        assertThat(dynatraceMcpSettings.enabled()).isTrue();
        assertThat(dynatraceMcpSettings.endpoint()).isEqualTo("https://dynatrace-mcp.internal/mcp");
        verify(dynatraceMcpPreferences).save(true, "https://dynatrace-mcp.internal/mcp", "30d");
        verify(dynatraceMcpAvailability).invalidate();
    }

    @Test
    void testingASuccessfulConnectionRecordsItOnAvailability() throws Exception {
        var stageResult = new DynatraceMcpConnectionTest.StageResult("Dynatrace tool discovered", true, null, "");
        var report = new DynatraceMcpConnectionTest.Report(true, List.of(stageResult));
        when(dynatraceMcpConnectionTest.runBridge(
                org.mockito.ArgumentMatchers.eq("https://dynatrace-mcp.internal/mcp"), any()))
                .thenReturn(report);

        mockMvc.perform(post("/api/settings/dynatrace-mcp/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"endpoint":"https://dynatrace-mcp.internal/mcp",
                                 "defaultWindow":"30d"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(true))
                .andExpect(jsonPath("$.stages[0].stage").value("Dynatrace tool discovered"));

        verify(dynatraceMcpAvailability).recordTestResult(true, "", "");
    }

    @Test
    void testingAFailedConnectionRecordsTheFailedStageOnAvailability() throws Exception {
        var stageResult = new DynatraceMcpConnectionTest.StageResult("Local bridge started", false,
                DynatraceMcpFailureCategory.CONNECTION_FAILED, "connection refused");
        var report = new DynatraceMcpConnectionTest.Report(false, List.of(stageResult));
        when(dynatraceMcpConnectionTest.runBridge(
                org.mockito.ArgumentMatchers.eq("https://dynatrace-mcp.internal/mcp"), any()))
                .thenReturn(report);

        mockMvc.perform(post("/api/settings/dynatrace-mcp/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"endpoint":"https://dynatrace-mcp.internal/mcp",
                                 "defaultWindow":"30d"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(false));

        verify(dynatraceMcpAvailability).recordTestResult(false, "Local bridge started: connection refused",
                "Use Test Connection under Settings for the full detail.");
    }

    @Test
    void testingWithABlankEndpointIsRejected() throws Exception {
        mockMvc.perform(post("/api/settings/dynatrace-mcp/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"endpoint":"","defaultWindow":"30d"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importingARecognizedMcpRemoteConfigExtractsTheUrl() throws Exception {
        String pastedConfig = """
                {"dynatrace":{"command":"npx","args":["mcp-remote","https://dynatrace-mcp.internal/mcp"]}}""";
        mockMvc.perform(post("/api/settings/dynatrace-mcp/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importRequestBody(pastedConfig)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recognized").value(true))
                .andExpect(jsonPath("$.endpoint").value("https://dynatrace-mcp.internal/mcp"));
    }

    @Test
    void importingAnUnrecognizedCommandIsRefusedRatherThanExecuted() throws Exception {
        String pastedConfig = """
                {"command":"rm","args":["-rf","/"]}""";
        mockMvc.perform(post("/api/settings/dynatrace-mcp/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importRequestBody(pastedConfig)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recognized").value(false))
                .andExpect(jsonPath("$.reason").isNotEmpty());
    }

    private static String importRequestBody(String pastedConfig) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(new ImportDynatraceMcpBody(pastedConfig));
    }

    private record ImportDynatraceMcpBody(String pastedConfig) {
    }
}
