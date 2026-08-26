package com.acltabontabon.vortex.app;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything a user can configure about Vortex itself.
 *
 * <p>Deliberately small. A settings page that exposes every internal knob is a settings page nobody
 * reads, and most of these have defaults that are simply correct — the ones worth surfacing are the
 * ones that differ between machines.
 *
 * <p>This is also the only place in the application where Bean Validation appears. The domain model
 * validates itself through its own constructors; annotations belong at the configuration boundary,
 * where the input genuinely arrives as untyped text.
 */
@ConfigurationProperties(prefix = "vortex")
public record VortexProperties(
        String version,
        Workspace workspace,
        Engine engine,
        Ai ai,
        DynatraceMcp dynatraceMcp,
        LoadGenerator loadGenerator,
        Safety safety,
        Observability observability) {

    public VortexProperties {
        version = version == null || version.isBlank() ? "0.1.0-SNAPSHOT" : version;
        workspace = workspace == null ? new Workspace(null) : workspace;
        engine = engine == null ? new Engine(null, null, null, null, true, null) : engine;
        ai = ai == null ? new Ai(null, null, null, null, false) : ai;
        dynatraceMcp = dynatraceMcp == null ? new DynatraceMcp(false, null, null, null, null) : dynatraceMcp;
        loadGenerator = loadGenerator == null ? new LoadGenerator(null, null, null) : loadGenerator;
        safety = safety == null ? new Safety(null, null, null) : safety;
        observability = observability == null ? new Observability(null, null, null) : observability;
    }

    /**
     * Where to watch the service under test from, beyond its own metrics endpoint.
     *
     * <p>Vortex's own settings rather than a project's {@code vortex.yaml}, and the distinction
     * matters: a project's {@code observation:} section points at <em>production</em>, while this
     * points at whatever watches the deployment being tested. They are different deployments, usually
     * monitored by different systems, and conflating them would have Vortex compare a test run
     * against production's own resource usage.
     *
     * <p>Absent by default. A service's Actuator endpoint is already read without any configuration,
     * and most local runs need nothing more.
     *
     * @param prometheusEndpoint the Prometheus API root that scrapes the environment under test
     * @param dynatraceEndpoint  the Dynatrace tenant that monitors it
     * @param dynatraceEntity    that environment's service entity id
     */
    public record Observability(String prometheusEndpoint, String dynatraceEndpoint,
            String dynatraceEntity) {

        public Observability {
            prometheusEndpoint = prometheusEndpoint == null ? "" : prometheusEndpoint.trim();
            dynatraceEndpoint = dynatraceEndpoint == null ? "" : dynatraceEndpoint.trim();
            dynatraceEntity = dynatraceEntity == null ? "" : dynatraceEntity.trim();
        }

        public boolean hasPrometheus() {
            return !prometheusEndpoint.isBlank();
        }

        public boolean hasDynatrace() {
            return !dynatraceEndpoint.isBlank() && !dynatraceEntity.isBlank();
        }
    }

    /**
     * Where Vortex keeps its own state.
     *
     * @param directory defaults to {@code ~/.vortex}
     */
    public record Workspace(String directory) {
    }

    /**
     * How load is generated.
     *
     * @param runner              {@code local} or {@code docker}
     * @param executable          path to the k6 binary, when it is not simply on the PATH
     * @param dockerExecutable    path to the docker binary
     * @param dockerImage         the k6 image, pinned rather than {@code latest} so runs stay reproducible
     * @param compressRawMetrics  whether to gzip the raw sample stream after a run; it is large and
     *                            highly compressible, and keeping it is what preserves source evidence
     * @param composeTimeout      how long a local lab's Compose command may take. Generous by
     *                            default: a first run pulls images before it starts anything, and
     *                            the command does not hold a request open
     */
    public record Engine(
            String runner,
            String executable,
            String dockerExecutable,
            String dockerImage,
            boolean compressRawMetrics,
            Duration composeTimeout) {

        public Engine {
            runner = runner == null || runner.isBlank() ? "local" : runner.trim().toLowerCase();
            executable = executable == null || executable.isBlank() ? "k6" : executable.trim();
            dockerExecutable = dockerExecutable == null || dockerExecutable.isBlank()
                    ? "docker" : dockerExecutable.trim();
            composeTimeout = composeTimeout == null || composeTimeout.isZero()
                    || composeTimeout.isNegative() ? Duration.ofMinutes(15) : composeTimeout;
        }

        public boolean usesDocker() {
            return "docker".equals(runner);
        }
    }

    /**
     * The local AI assistant.
     *
     * <p>Optional in every sense. Vortex starts, onboards, executes, evaluates and reports with no
     * model available; AI adds interpretation on top of results that already exist.
     *
     * @param provider     currently only {@code ollama}
     * @param baseUrl      where the provider is listening
     * @param model        which model to use — not hardcoded, because the right one depends on the
     *                     machine and on what the user has already pulled
     * @param timeout      how long to wait for inference before giving up on the analysis
     * @param logPrompts   whether to log full prompts; off by default, since prompts contain the
     *                     service's own operation names and descriptions
     */
    public record Ai(String provider, String baseUrl, String model, Duration timeout, boolean logPrompts) {

        public Ai {
            provider = provider == null || provider.isBlank() ? "ollama" : provider.trim();
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "http://localhost:11434" : baseUrl.trim();
            model = model == null ? "" : model.trim();
            timeout = timeout == null ? Duration.ofMinutes(3) : timeout;
        }

        public boolean hasModel() {
            return !model.isBlank();
        }
    }

    /**
     * The Dynatrace MCP connection: one endpoint, reached through a locally-spawned
     * {@code npx mcp-remote} bridge (see {@code docs/adr/adr-051-dynatrace-mcp-local-npx-bridge.adoc}),
     * shared by every project whose {@code observation.transport} is {@code mcp}.
     *
     * <p>Static install-wide defaults only — the runtime-mutable value a call actually reads is
     * {@code com.acltabontabon.vortex.dynatrace.DynatraceMcpSettings}, seeded from this at startup
     * and then updatable from the Settings page without a restart, the same split {@link Ai} and
     * {@code AiSettings} already use.
     *
     * <p>Nothing else to authenticate: the bridge performs Dynatrace's own interactive OAuth itself
     * on first use, so there is no header or client credential to configure here.
     *
     * @param enabled       whether Dynatrace MCP is available for use
     * @param endpoint      the MCP server's base URL
     * @param defaultWindow how far back a fetch looks when a service does not override it
     * @param queryTimeout  how long a single MCP tool call may take before Vortex gives up on it
     * @param organization  the Dynatrace organization to query, picked under Settings when the
     *                      account has more than one; blank when there's nothing to pick or nothing
     *                      chosen yet — see {@code com.acltabontabon.vortex.dynatrace.query.DqlToolSchema}
     */
    public record DynatraceMcp(boolean enabled, String endpoint, Duration defaultWindow, Duration queryTimeout,
            String organization) {

        public DynatraceMcp {
            endpoint = endpoint == null ? "" : endpoint.trim();
            defaultWindow = defaultWindow == null || defaultWindow.isZero() || defaultWindow.isNegative()
                    ? Duration.ofDays(30) : defaultWindow;
            queryTimeout = queryTimeout == null || queryTimeout.isZero() || queryTimeout.isNegative()
                    ? Duration.ofSeconds(30) : queryTimeout;
            organization = organization == null ? "" : organization.trim();
        }
    }

    /**
     * The load generator's resource budget — how much CPU and memory Vortex allows it to use.
     *
     * <p>{@code automatic} (the default) computes a conservative figure from the host a run actually
     * executes on, each time a run resolves it; {@code custom} is an advanced user's explicit choice.
     * This binding is the file's own record of the setting; the live, runtime-mutable value a run
     * actually reads is {@code com.acltabontabon.vortex.app.config.LoadGeneratorBudgetSettings}, seeded from this
     * at startup — the same split {@link Ai} and {@code com.acltabontabon.vortex.ai.AiSettings} already use.
     *
     * @param mode            {@code automatic} or {@code custom}
     * @param cpuMillicores   only meaningful when {@code mode} is {@code custom}
     * @param memoryMebibytes only meaningful when {@code mode} is {@code custom}
     */
    public record LoadGenerator(String mode, Integer cpuMillicores, Integer memoryMebibytes) {

        public LoadGenerator {
            mode = mode == null || mode.isBlank() ? "automatic" : mode.trim().toLowerCase();
        }

        public boolean isCustom() {
            return "custom".equals(mode);
        }
    }

    /**
     * The safety envelope.
     *
     * @param maxDuration  longest a single run may last
     * @param allowedHosts when non-empty, the only hosts that may be targeted
     * @param rateCeilings per-environment-type ceilings, as {@code TYPE=rate} entries
     */
    public record Safety(Duration maxDuration, List<String> allowedHosts, List<String> rateCeilings) {

        public Safety {
            maxDuration = maxDuration == null ? Duration.ofHours(4) : maxDuration;
            allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
            rateCeilings = rateCeilings == null ? List.of() : List.copyOf(rateCeilings);
        }
    }

}
