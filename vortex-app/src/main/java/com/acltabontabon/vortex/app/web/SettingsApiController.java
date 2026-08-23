package com.acltabontabon.vortex.app.web;

import com.acltabontabon.vortex.ai.AiSettings;
import com.acltabontabon.vortex.ai.OllamaAvailability;
import com.acltabontabon.vortex.app.VortexProperties;
import com.acltabontabon.vortex.app.config.AiModelPreferenceStore;
import com.acltabontabon.vortex.app.config.LoadGeneratorBudgetPreferenceStore;
import com.acltabontabon.vortex.app.config.LoadGeneratorBudgetSettings;
import com.acltabontabon.vortex.app.service.LocalLabRunner;
import com.acltabontabon.vortex.core.evidence.HostShape;
import com.acltabontabon.vortex.core.port.PerformanceAssistant;
import com.acltabontabon.vortex.core.port.PerformanceEngine;
import com.acltabontabon.vortex.core.resource.LoadGeneratorResourceBudget;
import com.acltabontabon.vortex.core.resource.LoadGeneratorResourceBudgetResolver;
import com.acltabontabon.vortex.core.resource.ResolvedLoadGeneratorBudget;
import com.acltabontabon.vortex.core.target.CpuAllocation;
import com.acltabontabon.vortex.core.target.MemoryAllocation;
import com.acltabontabon.vortex.core.target.ResourceEnvelopeRequest;
import com.acltabontabon.vortex.persistence.VortexWorkspace;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
    private final LoadGeneratorBudgetSettings loadGeneratorBudgetSettings;
    private final LoadGeneratorBudgetPreferenceStore loadGeneratorBudgetPreferences;
    private final LoadGeneratorResourceBudgetResolver loadGeneratorResourceBudgetResolver;

    public SettingsApiController(VortexProperties properties, PerformanceEngine engine,
            PerformanceAssistant assistant, OllamaAvailability ollama, AiSettings aiSettings,
            AiModelPreferenceStore aiModelPreferences, LocalLabRunner lab, VortexWorkspace workspace,
            LoadGeneratorBudgetSettings loadGeneratorBudgetSettings,
            LoadGeneratorBudgetPreferenceStore loadGeneratorBudgetPreferences,
            LoadGeneratorResourceBudgetResolver loadGeneratorResourceBudgetResolver) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.assistant = Objects.requireNonNull(assistant, "assistant");
        this.ollama = Objects.requireNonNull(ollama, "ollama");
        this.aiSettings = Objects.requireNonNull(aiSettings, "aiSettings");
        this.aiModelPreferences = Objects.requireNonNull(aiModelPreferences, "aiModelPreferences");
        this.lab = Objects.requireNonNull(lab, "lab");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.loadGeneratorBudgetSettings =
                Objects.requireNonNull(loadGeneratorBudgetSettings, "loadGeneratorBudgetSettings");
        this.loadGeneratorBudgetPreferences =
                Objects.requireNonNull(loadGeneratorBudgetPreferences, "loadGeneratorBudgetPreferences");
        this.loadGeneratorResourceBudgetResolver = Objects.requireNonNull(
                loadGeneratorResourceBudgetResolver, "loadGeneratorResourceBudgetResolver");
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
            String workspacePath, LoadGeneratorSettingsDto loadGenerator) {}

    /** As saved — {@code cpuMillicores}/{@code memoryMebibytes} are only meaningful when
     *  {@code mode} is {@code "custom"}. */
    public record ConfiguredLoadGeneratorBudgetDto(String mode, Integer cpuMillicores,
            Integer memoryMebibytes) {}

    public record ResourceEnvelopeDto(Integer cpuMillicores, Long memoryBytes) {}

    public record HostShapeDto(String operatingSystem, String osVersion, String architecture,
            int availableProcessors, long totalMemoryBytes) {}

    /** What a budget resolves to right now, on this host. */
    public record ResolvedLoadGeneratorBudgetDto(String mode, ResourceEnvelopeDto allocation,
            HostShapeDto detectedHost, ResourceEnvelopeDto osAndVortexReserve,
            ResourceEnvelopeDto sutReserve, boolean colocatedWithManagedSut) {}

    /**
     * Three distinct things, never conflated: {@code configured} is what was saved; {@code effective}
     * is what actually applies right now given {@code configured.mode}; {@code automaticPreview} is
     * always what Automatic would currently choose, regardless of the saved mode, so a Custom user can
     * see what switching back would give them without {@code effective} ever silently overriding their
     * saved values.
     */
    public record LoadGeneratorSettingsDto(ConfiguredLoadGeneratorBudgetDto configured,
            ResolvedLoadGeneratorBudgetDto effective, ResolvedLoadGeneratorBudgetDto automaticPreview) {}

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
                workspace.root().toString(),
                loadGeneratorSettings());
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

    private LabStatusDto toDto(com.acltabontabon.vortex.core.port.LocalLab.LabStatus status) {
        return new LabStatusDto(status.dockerAvailable(), status.daemonRunning(),
                status.composeAvailable(), status.isUsable(), status.version(), status.remedy());
    }

    private static final int MINIMUM_VIABLE_MEMORY_MEBIBYTES = 128;

    public record ChooseLoadGeneratorBudgetRequest(String mode, Integer cpuMillicores,
            Integer memoryMebibytes) {}

    public record ChooseLoadGeneratorBudgetResponse(String message) {}

    /**
     * Switches the load generator's resource budget, effective for the next run that resolves it, and
     * writes it to {@code ~/.vortex/config.yaml} so it survives a restart.
     */
    @PostMapping("/load-generator")
    public ChooseLoadGeneratorBudgetResponse chooseLoadGeneratorBudget(
            @RequestBody ChooseLoadGeneratorBudgetRequest request) {
        LoadGeneratorResourceBudget budget = parseLoadGeneratorBudget(request);
        loadGeneratorBudgetSettings.update(budget);
        loadGeneratorBudgetPreferences.saveBudget(budget);
        return new ChooseLoadGeneratorBudgetResponse(
                budget.mode() == LoadGeneratorResourceBudget.BudgetMode.CUSTOM
                        ? "Now using a custom load generator budget."
                        : "Now using an automatic load generator budget.");
    }

    private LoadGeneratorResourceBudget parseLoadGeneratorBudget(
            ChooseLoadGeneratorBudgetRequest request) {
        String mode = request.mode() == null ? "" : request.mode().trim().toLowerCase(Locale.ROOT);
        if (!"custom".equals(mode)) {
            return LoadGeneratorResourceBudget.automatic();
        }
        if (request.cpuMillicores() == null || request.memoryMebibytes() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A custom load generator budget needs both a CPU and a memory value.");
        }
        if (request.memoryMebibytes() < MINIMUM_VIABLE_MEMORY_MEBIBYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A memory budget below " + MINIMUM_VIABLE_MEMORY_MEBIBYTES + " MiB is not enough "
                            + "for the load generator to run.");
        }
        try {
            return LoadGeneratorResourceBudget.custom(
                    CpuAllocation.ofMillicores(request.cpuMillicores()),
                    MemoryAllocation.ofMebibytes(request.memoryMebibytes()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    /**
     * Settings has no run to reason about yet, so it previews the same conservative assumption a run
     * makes before it knows its own target: that a colocated, Vortex-managed system under test might
     * share this host. See {@link LoadGeneratorResourceBudgetResolver}.
     */
    private static final boolean CONSERVATIVELY_ASSUME_COLOCATED_SUT = true;

    private LoadGeneratorSettingsDto loadGeneratorSettings() {
        LoadGeneratorResourceBudget configured = loadGeneratorBudgetSettings.current();
        ResolvedLoadGeneratorBudget effective = loadGeneratorResourceBudgetResolver.resolve(
                configured, CONSERVATIVELY_ASSUME_COLOCATED_SUT);
        ResolvedLoadGeneratorBudget automaticPreview = loadGeneratorResourceBudgetResolver.resolve(
                LoadGeneratorResourceBudget.automatic(), CONSERVATIVELY_ASSUME_COLOCATED_SUT);

        return new LoadGeneratorSettingsDto(toConfiguredDto(configured), toResolvedDto(effective),
                toResolvedDto(automaticPreview));
    }

    private ConfiguredLoadGeneratorBudgetDto toConfiguredDto(LoadGeneratorResourceBudget configured) {
        return new ConfiguredLoadGeneratorBudgetDto(
                configured.mode().name().toLowerCase(Locale.ROOT),
                configured.envelope().cpuIfPresent().map(CpuAllocation::millicores).orElse(null),
                configured.envelope().memoryIfPresent()
                        .map(memory -> (int) (memory.bytes() / (1024 * 1024))).orElse(null));
    }

    private ResolvedLoadGeneratorBudgetDto toResolvedDto(ResolvedLoadGeneratorBudget resolved) {
        return new ResolvedLoadGeneratorBudgetDto(
                resolved.mode().name().toLowerCase(Locale.ROOT),
                toEnvelopeDto(resolved.allocation()),
                toHostShapeDto(resolved.detectedHost()),
                toEnvelopeDto(resolved.osAndVortexReserve()),
                toEnvelopeDto(resolved.sutReserve()),
                resolved.colocatedWithManagedSut());
    }

    private ResourceEnvelopeDto toEnvelopeDto(ResourceEnvelopeRequest envelope) {
        return new ResourceEnvelopeDto(
                envelope.cpuIfPresent().map(CpuAllocation::millicores).orElse(null),
                envelope.memoryIfPresent().map(MemoryAllocation::bytes).orElse(null));
    }

    private HostShapeDto toHostShapeDto(HostShape host) {
        return new HostShapeDto(host.operatingSystem(), host.osVersion(), host.architecture(),
                host.availableProcessors(), host.totalMemoryBytes());
    }
}
