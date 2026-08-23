package dev.vortex.app.web;

import dev.vortex.ai.AiSettings;
import dev.vortex.ai.OllamaAvailability;
import dev.vortex.app.VortexProperties;
import dev.vortex.app.config.AiModelPreferenceStore;
import dev.vortex.app.service.LocalLabRunner;
import dev.vortex.core.port.PerformanceAssistant;
import dev.vortex.core.port.PerformanceEngine;
import dev.vortex.persistence.VortexWorkspace;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings and diagnostics, as JSON.
 *
 * <p>Deliberately narrow, mirroring {@code SettingsController}: configuration lives in
 * {@code application.yaml} and, for the one setting a user actually changes day to day — which
 * local AI model to use — in {@code ~/.vortex/config.yaml}. This controller reads the same
 * collaborators and changes nothing about what runs; it only chooses a wire shape for them.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsApiController {

    private final VortexProperties properties;
    private final PerformanceEngine engine;
    private final PerformanceAssistant assistant;
    private final OllamaAvailability ollama;
    private final AiSettings aiSettings;
    private final AiModelPreferenceStore aiModelPreferences;
    private final LocalLabRunner lab;
    private final VortexWorkspace workspace;

    public SettingsApiController(VortexProperties properties, PerformanceEngine engine,
            PerformanceAssistant assistant, OllamaAvailability ollama, AiSettings aiSettings,
            AiModelPreferenceStore aiModelPreferences, LocalLabRunner lab, VortexWorkspace workspace) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.assistant = Objects.requireNonNull(assistant, "assistant");
        this.ollama = Objects.requireNonNull(ollama, "ollama");
        this.aiSettings = Objects.requireNonNull(aiSettings, "aiSettings");
        this.aiModelPreferences = Objects.requireNonNull(aiModelPreferences, "aiModelPreferences");
        this.lab = Objects.requireNonNull(lab, "lab");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
    }

    public record EngineSettingsDto(boolean usesDocker, String runner, String executable,
            String dockerImage, boolean compressRawMetrics) {}

    public record EngineAvailabilityDto(boolean available, String version, String problem,
            String remedy) {}

    public record AiSettingsDto(String provider, String baseUrl, String model) {}

    public record AiAvailabilityDto(boolean available, String provider, String model,
            String problem, String remedy) {}

    public record LabStatusDto(boolean dockerAvailable, boolean daemonRunning,
            boolean composeAvailable, boolean usable, String version, String remedy) {}

    public record SettingsDto(String vortexVersion, EngineSettingsDto engine,
            EngineAvailabilityDto engineAvailability, AiSettingsDto aiSettings,
            AiAvailabilityDto aiAvailability, List<String> installedModels, LabStatusDto labStatus,
            String workspacePath) {}

    @GetMapping
    public SettingsDto settings() {
        return new SettingsDto(
                properties.version(),
                toDto(properties.engine()),
                toDto(engine.availability()),
                toDto(aiSettings),
                toDto(assistant.availability()),
                ollama.installedModels(),
                toDto(lab.status()),
                workspace.root().toString());
    }

    public record RetryAiResponse(AiAvailabilityDto availability, String message,
            boolean succeeded) {}

    /** Re-checks the assistant, discarding the cached answer. */
    @PostMapping("/ai/retry")
    public RetryAiResponse retryAi() {
        ollama.refresh();
        var availability = assistant.availability();
        String message = availability.available()
                ? "Connected to " + availability.provider() + " using " + availability.model() + "."
                : availability.problem();
        return new RetryAiResponse(toDto(availability), message, availability.available());
    }

    public record ChooseModelRequest(String model) {}

    public record ChooseModelResponse(String message) {}

    /**
     * Switches the model, effective immediately, and writes it to {@code ~/.vortex/config.yaml} so
     * it survives a restart.
     */
    @PostMapping("/ai/model")
    public ChooseModelResponse chooseModel(@RequestBody ChooseModelRequest request) {
        String model = request.model() == null ? "" : request.model();
        aiSettings.useModel(model);
        aiModelPreferences.saveModel(model);
        ollama.refresh();
        return new ChooseModelResponse(model.isBlank() ? "No model selected." : "Now using " + model + ".");
    }

    private EngineSettingsDto toDto(VortexProperties.Engine engine) {
        return new EngineSettingsDto(engine.usesDocker(), engine.runner(), engine.executable(),
                engine.dockerImage(), engine.compressRawMetrics());
    }

    private EngineAvailabilityDto toDto(PerformanceEngine.EngineAvailability availability) {
        return new EngineAvailabilityDto(availability.available(), availability.version(),
                availability.problem(), availability.remedy());
    }

    private AiSettingsDto toDto(AiSettings settings) {
        return new AiSettingsDto(settings.provider(), settings.baseUrl(), settings.model());
    }

    private AiAvailabilityDto toDto(PerformanceAssistant.Availability availability) {
        return new AiAvailabilityDto(availability.available(), availability.provider(),
                availability.model(), availability.problem(), availability.remedy());
    }

    private LabStatusDto toDto(dev.vortex.core.port.LocalLab.LabStatus status) {
        return new LabStatusDto(status.dockerAvailable(), status.daemonRunning(),
                status.composeAvailable(), status.isUsable(), status.version(), status.remedy());
    }
}
