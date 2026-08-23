package dev.vortex.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vortex.ai.AiSettings;
import dev.vortex.ai.OllamaAvailability;
import dev.vortex.app.VortexProperties;
import dev.vortex.app.config.AiModelPreferenceStore;
import dev.vortex.app.service.LocalLabRunner;
import dev.vortex.core.port.LocalLab;
import dev.vortex.core.port.PerformanceAssistant;
import dev.vortex.core.port.PerformanceEngine;
import dev.vortex.core.workload.RateAllocator;
import dev.vortex.persistence.VortexWorkspace;
import java.nio.file.Path;
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
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AiSettings aiSettings;

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

    @BeforeEach
    void wiring() {
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
                .andExpect(jsonPath("$.workspacePath").value("/tmp/vortex-workspace"));
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
}
