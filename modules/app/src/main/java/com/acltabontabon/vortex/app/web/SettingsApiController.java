package com.acltabontabon.vortex.app.web;

import com.acltabontabon.vortex.ai.AiSettings;
import com.acltabontabon.vortex.ai.OllamaAvailability;
import com.acltabontabon.vortex.app.VortexProperties;
import com.acltabontabon.vortex.app.adapter.observation.PrometheusDefaultsConnectionTest;
import com.acltabontabon.vortex.app.config.AiModelPreferenceStore;
import com.acltabontabon.vortex.app.config.DynatraceMcpPreferenceStore;
import com.acltabontabon.vortex.app.config.LoadGeneratorBudgetPreferenceStore;
import com.acltabontabon.vortex.app.config.LoadGeneratorBudgetSettings;
import com.acltabontabon.vortex.app.config.PrometheusDefaultsPreferenceStore;
import com.acltabontabon.vortex.app.config.PrometheusDefaultsSettings;
import com.acltabontabon.vortex.app.service.LocalLabRunner;
import com.acltabontabon.vortex.core.environment.SecretReferences;
import com.acltabontabon.vortex.core.evidence.HostShape;
import com.acltabontabon.vortex.core.port.PerformanceAssistant;
import com.acltabontabon.vortex.core.port.PerformanceEngine;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.NotRetrieved;
import com.acltabontabon.vortex.core.resource.LoadGeneratorResourceBudget;
import com.acltabontabon.vortex.core.resource.LoadGeneratorResourceBudgetResolver;
import com.acltabontabon.vortex.core.resource.ResolvedLoadGeneratorBudget;
import com.acltabontabon.vortex.core.target.CpuAllocation;
import com.acltabontabon.vortex.core.target.MemoryAllocation;
import com.acltabontabon.vortex.core.target.ResourceEnvelopeRequest;
import com.acltabontabon.vortex.core.threshold.Durations;
import com.acltabontabon.vortex.dynatrace.DynatraceMcpAvailability;
import com.acltabontabon.vortex.dynatrace.DynatraceMcpClientFactory;
import com.acltabontabon.vortex.dynatrace.DynatraceMcpConnectionTest;
import com.acltabontabon.vortex.dynatrace.DynatraceMcpSettings;
import com.acltabontabon.vortex.persistence.VortexWorkspace;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final DynatraceMcpSettings dynatraceMcpSettings;
    private final DynatraceMcpPreferenceStore dynatraceMcpPreferences;
    private final DynatraceMcpAvailability dynatraceMcpAvailability;
    private final DynatraceMcpClientFactory dynatraceMcpClients;
    private final DynatraceMcpConnectionTest dynatraceMcpConnectionTest;
    private final PrometheusDefaultsSettings prometheusDefaultsSettings;
    private final PrometheusDefaultsPreferenceStore prometheusDefaultsPreferences;
    private final PrometheusDefaultsConnectionTest prometheusDefaultsConnectionTest;

    public SettingsApiController(VortexProperties properties, PerformanceEngine engine,
            PerformanceAssistant assistant, OllamaAvailability ollama, AiSettings aiSettings,
            AiModelPreferenceStore aiModelPreferences, LocalLabRunner lab, VortexWorkspace workspace,
            LoadGeneratorBudgetSettings loadGeneratorBudgetSettings,
            LoadGeneratorBudgetPreferenceStore loadGeneratorBudgetPreferences,
            LoadGeneratorResourceBudgetResolver loadGeneratorResourceBudgetResolver,
            DynatraceMcpSettings dynatraceMcpSettings, DynatraceMcpPreferenceStore dynatraceMcpPreferences,
            DynatraceMcpAvailability dynatraceMcpAvailability, DynatraceMcpClientFactory dynatraceMcpClients,
            DynatraceMcpConnectionTest dynatraceMcpConnectionTest,
            PrometheusDefaultsSettings prometheusDefaultsSettings,
            PrometheusDefaultsPreferenceStore prometheusDefaultsPreferences,
            PrometheusDefaultsConnectionTest prometheusDefaultsConnectionTest) {
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
        this.dynatraceMcpSettings = Objects.requireNonNull(dynatraceMcpSettings, "dynatraceMcpSettings");
        this.dynatraceMcpPreferences =
                Objects.requireNonNull(dynatraceMcpPreferences, "dynatraceMcpPreferences");
        this.dynatraceMcpAvailability =
                Objects.requireNonNull(dynatraceMcpAvailability, "dynatraceMcpAvailability");
        this.dynatraceMcpClients = Objects.requireNonNull(dynatraceMcpClients, "dynatraceMcpClients");
        this.dynatraceMcpConnectionTest =
                Objects.requireNonNull(dynatraceMcpConnectionTest, "dynatraceMcpConnectionTest");
        this.prometheusDefaultsSettings =
                Objects.requireNonNull(prometheusDefaultsSettings, "prometheusDefaultsSettings");
        this.prometheusDefaultsPreferences =
                Objects.requireNonNull(prometheusDefaultsPreferences, "prometheusDefaultsPreferences");
        this.prometheusDefaultsConnectionTest =
                Objects.requireNonNull(prometheusDefaultsConnectionTest, "prometheusDefaultsConnectionTest");
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
            String workspacePath, LoadGeneratorSettingsDto loadGenerator,
            DynatraceMcpSettingsDto dynatraceMcp, DynatraceMcpAvailabilityDto dynatraceMcpAvailability,
            PrometheusDefaultsDto prometheusDefaults) {}

    public record DynatraceMcpSettingsDto(boolean enabled, String endpoint, String defaultWindowDisplay,
            String organization) {}

    public record DynatraceMcpAvailabilityDto(boolean available, String problem, String remedy) {}

    /** {@code configured} is derived ({@code !endpoint.isBlank()}) — there is nothing to enable or
     *  disable here, only something to have typed in or not. */
    public record PrometheusDefaultsDto(String endpoint, String windowDisplay, Map<String, String> headers,
            String serviceLabel, String routeLabel, String methodLabel, boolean configured) {}

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
                loadGeneratorSettings(),
                toDto(dynatraceMcpSettings),
                toDto(dynatraceMcpAvailability.check()),
                toDto(prometheusDefaultsSettings));
    }

    private DynatraceMcpSettingsDto toDto(DynatraceMcpSettings settings) {
        return new DynatraceMcpSettingsDto(settings.enabled(), settings.endpoint(),
                Durations.display(settings.defaultWindow()), settings.organization());
    }

    private DynatraceMcpAvailabilityDto toDto(DynatraceMcpAvailability.Availability availability) {
        return new DynatraceMcpAvailabilityDto(availability.available(), availability.problem(),
                availability.remedy());
    }

    private PrometheusDefaultsDto toDto(PrometheusDefaultsSettings settings) {
        var d = settings.current();
        Map<String, String> masked = new LinkedHashMap<>();
        d.headers().forEach((k, v) -> masked.put(k, SecretReferences.mask(v)));
        return new PrometheusDefaultsDto(d.endpoint(), Durations.display(d.window()), masked,
                d.serviceLabel(), d.routeLabel(), d.methodLabel(), d.configured());
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

    // ==================================================================== Dynatrace MCP

    public record SaveDynatraceMcpRequest(boolean enabled, String endpoint, String defaultWindow,
            String organization) {
        public SaveDynatraceMcpRequest {
            organization = organization == null ? "" : organization;
        }
    }

    public record SaveDynatraceMcpResponse(String message) {}

    /**
     * Saves the Dynatrace MCP connection, effective immediately, and writes it to
     * {@code ~/.vortex/config.yaml} so it survives a restart — the same pattern as
     * {@link #chooseModel}. Invalidates any recorded Test Connection result: a prior success no
     * longer speaks for the endpoint that just changed.
     */
    @PostMapping("/dynatrace-mcp")
    public SaveDynatraceMcpResponse saveDynatraceMcp(@RequestBody SaveDynatraceMcpRequest request) {
        try {
            Duration window = parseWindow(request.defaultWindow());
            dynatraceMcpSettings.reconfigure(request.enabled(), request.endpoint(), window, request.organization());
            dynatraceMcpPreferences.save(request.enabled(), request.endpoint(), Durations.display(window),
                    request.organization());
            dynatraceMcpAvailability.invalidate();
            return new SaveDynatraceMcpResponse(request.enabled()
                    ? "Saved. Test the connection, then map a service to a Dynatrace entity."
                    : "Saved. Dynatrace MCP is disabled.");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    public record DynatraceMcpStageDto(String stage, boolean succeeded, String category, String detail) {}

    public record TestDynatraceMcpResponse(boolean succeeded, List<DynatraceMcpStageDto> stages,
            List<String> organizationOptions) {}

    /**
     * Tests what is in the form, not what has been saved — the same contract
     * {@code ConfigurationApiController.testObservationSource} already has, and for the same reason:
     * testing only a saved configuration would make the button useless for the case it exists to
     * serve. A successful or failed result is recorded on {@link #dynatraceMcpAvailability}, so the
     * passive Settings-page badge reflects what this click actually found instead of a permanent
     * "not checked automatically" placeholder.
     */
    @PostMapping("/dynatrace-mcp/test")
    public TestDynatraceMcpResponse testDynatraceMcp(@RequestBody SaveDynatraceMcpRequest request) {
        if (request.endpoint() == null || request.endpoint().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Enter the Dynatrace MCP endpoint before testing the connection.");
        }
        var report = dynatraceMcpConnectionTest.runBridge(request.endpoint(), dynatraceMcpSettings.queryTimeout(),
                request.organization());
        List<DynatraceMcpStageDto> stages = report.stages().stream()
                .map(stage -> new DynatraceMcpStageDto(stage.stage(), stage.succeeded(),
                        stage.category() == null ? null : stage.category().name(), stage.detail()))
                .toList();
        recordTestResult(report);
        return new TestDynatraceMcpResponse(report.succeeded(), stages, report.organizationOptions());
    }

    private void recordTestResult(DynatraceMcpConnectionTest.Report report) {
        if (report.succeeded()) {
            dynatraceMcpAvailability.recordTestResult(true, "", "");
            return;
        }
        var failedStage = report.stages().stream().filter(stage -> !stage.succeeded()).findFirst();
        String problem = failedStage.map(stage -> stage.stage() + ": " + stage.detail())
                .orElse("The Dynatrace MCP connection test failed.");
        dynatraceMcpAvailability.recordTestResult(false, problem,
                "Use Test Connection under Settings for the full detail.");
    }

    private Duration parseWindow(String display) {
        try {
            return display == null || display.isBlank() ? Duration.ofDays(30) : Durations.parse(display);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "'" + display + "' is not a period Vortex understands, e.g. 30d.", e);
        }
    }

    // ==================================================================== Prometheus defaults

    public record SavePrometheusDefaultsRequest(String endpoint, String window, List<String> headerName,
            List<String> headerValue, String serviceLabel, String routeLabel, String methodLabel) {}

    public record SavePrometheusDefaultsResponse(String message) {}

    /**
     * Saves what prefills a brand-new service's Prometheus observation source, effective
     * immediately, and writes it to {@code ~/.vortex/config.yaml} so it survives a restart — the same
     * pattern as {@link #saveDynatraceMcp}. Unlike a per-service source, a blank endpoint is a valid
     * save: it simply means no default is set.
     */
    @PostMapping("/prometheus-defaults")
    public SavePrometheusDefaultsResponse savePrometheusDefaults(
            @RequestBody SavePrometheusDefaultsRequest request) {
        try {
            String endpoint = request.endpoint() == null ? "" : request.endpoint().trim();
            if (!endpoint.isBlank() && !endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                throw new IllegalArgumentException(
                        "the Prometheus defaults endpoint must be an absolute http or https URL, or blank");
            }
            Duration window = parseWindow(request.window());
            Map<String, String> headers = headersFrom(request.headerName(), request.headerValue());
            rejectLiteralSecrets(headers);

            var defaults = new VortexProperties.PrometheusDefaults(endpoint, window, headers,
                    request.serviceLabel(), request.routeLabel(), request.methodLabel());
            prometheusDefaultsSettings.reconfigure(defaults);
            prometheusDefaultsPreferences.save(defaults.endpoint(), Durations.display(window), headers,
                    defaults.serviceLabel(), defaults.routeLabel(), defaults.methodLabel());
            return new SavePrometheusDefaultsResponse(defaults.configured()
                    ? "Saved. A brand-new Prometheus observation source will prefill from this — it "
                            + "never overrides a service that already has one configured."
                    : "Saved. No default endpoint is set, so new Prometheus sources start blank.");
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    public record TestPrometheusDefaultsResponse(boolean succeeded, String state, String message) {}

    /**
     * Tests what is in the form, not what has been saved — the same contract every other Test
     * connection button in Vortex already has. Asks nothing about any one service: a Prometheus
     * default names no service, so this only proves the endpoint is reachable and authenticates.
     */
    @PostMapping("/prometheus-defaults/test")
    public TestPrometheusDefaultsResponse testPrometheusDefaults(
            @RequestBody SavePrometheusDefaultsRequest request) {
        String endpoint = request.endpoint() == null ? "" : request.endpoint().trim();
        if (endpoint.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Enter the Prometheus endpoint before testing the connection.");
        }
        Map<String, String> headers = headersFrom(request.headerName(), request.headerValue());
        var result = prometheusDefaultsConnectionTest.test(endpoint, headers);
        return switch (result) {
            case PrometheusDefaultsConnectionTest.Connected ignored ->
                    new TestPrometheusDefaultsResponse(true, "CONNECTED", "Connected to " + endpoint + ".");
            case PrometheusDefaultsConnectionTest.Failed failed ->
                    new TestPrometheusDefaultsResponse(false, connectionState(failed.kind()), failed.message());
        };
    }

    /** Same wire vocabulary {@code ConfigurationApiController.connectionState} already established.
     *  {@code CONNECTED_NO_DATA} never applies here — there is no per-service data question at this
     *  level, only reachability and credentials. */
    private String connectionState(NotRetrieved.Kind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case UNREACHABLE -> "UNREACHABLE";
            case AUTHENTICATION_FAILED -> "AUTHENTICATION_FAILED";
            case INVALID_RESPONSE, NO_DATA -> "INVALID_RESPONSE";
        };
    }

    private Map<String, String> headersFrom(List<String> names, List<String> values) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (names == null) {
            return headers;
        }
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (name == null || name.isBlank()) {
                continue;
            }
            String value = values != null && i < values.size() ? values.get(i) : "";
            headers.put(name, value == null ? "" : value);
        }
        return headers;
    }

    /**
     * A default header value must be blank or a pure {@code ${NAME}} reference, never a literal — a
     * value that round-trips unchanged through {@link SecretReferences#mask} is one of those two;
     * anything else was a literal. This matters specifically because the Settings response masks
     * these same headers for display, and the frontend prefills a brand-new service's header rows
     * directly from that masked map — a literal here would otherwise let an untouched prefilled row
     * silently save the mask string itself as that new service's real header value.
     */
    private void rejectLiteralSecrets(Map<String, String> headers) {
        for (var entry : headers.entrySet()) {
            String value = entry.getValue();
            if (value != null && !value.isBlank() && !SecretReferences.mask(value).equals(value)) {
                throw new IllegalArgumentException("the value for header '" + entry.getKey()
                        + "' must be blank or entirely a single ${NAME} reference, e.g. '${PROM_TOKEN}' "
                        + "— not mixed with other text such as a 'Bearer ' prefix, and never a literal "
                        + "secret. Put the whole header value, prefix included, in the environment "
                        + "variable itself. Prometheus defaults are prefilled into every new service's "
                        + "form as-is, so anything less than a pure reference here cannot be shown back "
                        + "safely and would risk being saved as a literal.");
            }
        }
    }
}
