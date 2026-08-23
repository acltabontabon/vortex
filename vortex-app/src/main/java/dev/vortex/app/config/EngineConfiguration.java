package dev.vortex.app.config;

import dev.vortex.app.VortexProperties;
import dev.vortex.app.adapter.lab.DockerLocalLab;
import dev.vortex.app.adapter.observability.ActuatorObservabilityProvider;
import dev.vortex.app.adapter.probe.HttpTargetProbe;
import dev.vortex.app.adapter.process.ShutdownProcessRegistry;
import dev.vortex.app.adapter.target.docker.DockerCapabilityProbe;
import dev.vortex.app.adapter.target.docker.DockerComposeTargetExecutor;
import dev.vortex.app.adapter.target.docker.DockerImageTargetExecutor;
import dev.vortex.app.adapter.target.docker.DockerProcess;
import dev.vortex.app.adapter.target.docker.SocketTargetReadinessProbe;
import dev.vortex.core.application.PreflightService;
import dev.vortex.core.environment.EnvironmentType;
import dev.vortex.core.port.LocalLab;
import dev.vortex.app.adapter.observation.DynatraceObservationSource;
import dev.vortex.app.adapter.observation.PrometheusObservationSource;
import dev.vortex.core.port.ArtifactStore;
import dev.vortex.core.port.ObservabilityProvider;
import dev.vortex.core.port.ProductionObservationSource;
import dev.vortex.core.port.TargetExecutor;
import dev.vortex.core.port.TelemetryCollector;
import dev.vortex.core.port.PerformanceEngine;
import dev.vortex.core.port.ServiceCatalogImporter;
import dev.vortex.core.resource.ResourceSampleSinkFactory;
import dev.vortex.core.resource.ResourceTelemetryReader;
import dev.vortex.core.safety.ExecutionPolicy;
import dev.vortex.core.safety.SafetyLimits;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.k6.DockerK6Runner;
import dev.vortex.k6.K6PerformanceEngine;
import dev.vortex.k6.K6RawMetricsAggregator;
import dev.vortex.k6.K6Runner;
import dev.vortex.k6.K6ScriptGenerator;
import dev.vortex.k6.K6SummaryParser;
import dev.vortex.openapi.OpenApiCatalogImporter;
import dev.vortex.persistence.VortexWorkspace;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Wires the execution engine, the importers, the safety policy and the optional local lab.
 *
 * <p>Each of these is an adapter behind a port, chosen here and nowhere else. Switching from a local
 * k6 binary to the container image is one setting; adding a second engine, or executing through the
 * k6 Operator, would be one more bean.
 */
@Configuration(proxyBeanMethods = false)
public class EngineConfiguration {

    /**
     * Tracks every child process Vortex starts (k6, Docker/Compose), so none of them survive a
     * shutdown of Vortex itself. On POSIX, a killed JVM does not take its children with it — an
     * orphaned load generator would keep sending traffic, and an orphaned Compose stack would keep
     * consuming resources, indefinitely.
     */
    @Bean(destroyMethod = "destroyAll")
    ShutdownProcessRegistry shutdownProcessRegistry() {
        return new ShutdownProcessRegistry();
    }

    @Bean
    K6Runner k6Runner(VortexProperties properties, ShutdownProcessRegistry processes) {
        VortexProperties.Engine engine = properties.engine();
        return engine.usesDocker()
                ? new DockerK6Runner(engine.dockerExecutable(), engine.dockerImage(), processes::track)
                : new dev.vortex.k6.LocalBinaryK6Runner(engine.executable(), processes::track);
    }

    @Bean
    K6ScriptGenerator k6ScriptGenerator() {
        return new K6ScriptGenerator();
    }

    @Bean
    K6SummaryParser k6SummaryParser() {
        return new K6SummaryParser();
    }

    @Bean
    K6RawMetricsAggregator k6RawMetricsAggregator() {
        return new K6RawMetricsAggregator();
    }

    /**
     * The engine, exposed by its concrete type.
     *
     * <p>Registered once. It satisfies the {@link PerformanceEngine} port for everything in the
     * domain, while {@code TestRunner} additionally needs the adapter-level detail of whether this
     * runner requires the target address to be rewritten — a fact about how load is generated, not
     * about what a performance test is.
     */
    @Bean
    K6PerformanceEngine performanceEngine(K6Runner runner, K6ScriptGenerator generator,
            K6SummaryParser parser, K6RawMetricsAggregator aggregator, VortexWorkspace workspace,
            VortexProperties properties) {
        return new K6PerformanceEngine(runner, generator, parser, aggregator, workspace.root(),
                properties.version(), properties.engine().compressRawMetrics());
    }

    @Bean
    ServiceCatalogImporter openApiImporter() {
        return new OpenApiCatalogImporter(Clock.systemUTC());
    }

    @Bean
    PreflightService.TargetProbe targetProbe() {
        return new HttpTargetProbe();
    }

    @Bean
    ObservabilityProvider actuatorObservabilityProvider(RestClient.Builder builder) {
        return new ActuatorObservabilityProvider(builder);
    }

    /**
     * Prometheus and Dynatrace as during-run providers, when this installation has been pointed at
     * one.
     *
     * <p>Registered only when configured, rather than registered always and inert: a provider that
     * exists but can never answer would appear in every run's list of providers consulted, which
     * would make "we asked and it had nothing" indistinguishable from "there is nothing to ask".
     */
    @Bean
    @ConditionalOnProperty("vortex.observability.prometheus-endpoint")
    ObservabilityProvider prometheusObservabilityProvider(RestClient.Builder builder,
            VortexProperties properties) {
        return new dev.vortex.app.adapter.observability.PrometheusObservabilityProvider(
                builder, properties.observability().prometheusEndpoint());
    }

    @Bean
    @ConditionalOnProperty("vortex.observability.dynatrace-endpoint")
    ObservabilityProvider dynatraceObservabilityProvider(RestClient.Builder builder,
            VortexProperties properties) {
        return new dev.vortex.app.adapter.observability.DynatraceObservabilityProvider(
                builder, properties.observability().dynatraceEndpoint(),
                properties.observability().dynatraceEntity());
    }

    /**
     * Where production traffic can be fetched from, as opposed to what the service under test says
     * about itself while a run is in progress.
     *
     * <p>Both are registered whatever a project is configured to use. Which one answers is decided
     * per project by {@code supports(...)}, not by which beans happen to exist, so a repository that
     * commits a Dynatrace source and one that commits a Prometheus source both work in the same
     * installation.
     */
    @Bean
    ProductionObservationSource prometheusObservationSource(RestClient.Builder builder) {
        return new PrometheusObservationSource(builder);
    }

    @Bean
    ProductionObservationSource dynatraceObservationSource(RestClient.Builder builder) {
        return new DynatraceObservationSource(builder);
    }

    /**
     * Vortex watching the machine it is running on, for every run.
     *
     * <p>Declared as its own bean rather than joining the provider list, because it is handed to the
     * collector separately: the providers below are probed and may all decline, while this one is
     * always sampled. Distinguishing "the service could not go faster" from "we could not ask
     * faster" is not an optional enrichment.
     */
    @Bean
    dev.vortex.app.adapter.observability.LoadGeneratorObservabilityProvider loadGeneratorObserver() {
        return new dev.vortex.app.adapter.observability.LoadGeneratorObservabilityProvider();
    }

    /**
     * Where each run's raw resource samples are streamed to, as they are taken.
     *
     * <p>Its own bean, rather than assembled inline in {@link #telemetryCollector}, because it is
     * the one place {@code ArtifactStore} and JSON meet for this feature — the collector itself never
     * sees either.
     */
    @Bean
    ResourceSampleSinkFactory resourceSampleSinkFactory(ArtifactStore artifacts) {
        return new dev.vortex.app.adapter.observability.ArtifactResourceSampleSinkFactory(artifacts);
    }

    /** The read-side counterpart, for {@code RunEvidenceService} to build a run's resource charts
     *  from the same artifact {@link #resourceSampleSinkFactory} streamed into. */
    @Bean
    ResourceTelemetryReader resourceTelemetryReader(ArtifactStore artifacts) {
        return new dev.vortex.app.adapter.observability.ArtifactResourceTelemetryReader(artifacts);
    }

    /**
     * Every provider that could answer, not one.
     *
     * <p>Which of them actually can is decided per run by probing, so an installation configured for
     * Prometheus and one relying on a service's own Actuator endpoint both work without a profile.
     */
    @Bean
    TelemetryCollector telemetryCollector(List<ObservabilityProvider> providers,
            dev.vortex.app.adapter.observability.LoadGeneratorObservabilityProvider generator,
            ResourceSampleSinkFactory resourceSampleSinkFactory, VortexProperties properties) {

        // Removed from the probed list so it is not asked twice: Spring injects every
        // ObservabilityProvider bean here, and this one is also a provider.
        List<ObservabilityProvider> ofTheService = providers.stream()
                .filter(provider -> provider != generator)
                .toList();

        // A fresh DockerProcess rather than a shared bean: it is stateless and cheap to construct,
        // and the collector only ever uses it to build a DockerContainerObservabilityProvider fresh
        // per run, the same way dockerImageTargetExecutor below builds its own.
        return new dev.vortex.app.adapter.observability.ObservabilityTelemetryCollector(
                ofTheService, generator, resourceSampleSinkFactory, new DockerProcess(),
                properties.engine().dockerExecutable());
    }

    @Bean
    LocalLab localLab(VortexProperties properties, ShutdownProcessRegistry processes) {
        return new DockerLocalLab(properties.engine().dockerExecutable(),
                properties.engine().composeTimeout(), processes::track);
    }

    /**
     * The {@link TargetExecutor} for {@link dev.vortex.core.target.DockerImageTarget} — one more
     * bean added to the list {@code ExecutionService} already collects, the same shape as {@link
     * dev.vortex.app.config.CoreConfiguration}'s {@code externalEndpointTargetExecutor}.
     */
    @Bean
    TargetExecutor dockerImageTargetExecutor(VortexProperties properties) {
        String dockerExecutable = properties.engine().dockerExecutable();
        return new DockerImageTargetExecutor(dockerExecutable, new DockerProcess(),
                new DockerCapabilityProbe(dockerExecutable), new SocketTargetReadinessProbe());
    }

    /**
     * The {@link TargetExecutor} for {@link dev.vortex.core.target.DockerComposeTarget} — attach-only,
     * same bean shape as {@link #dockerImageTargetExecutor} but with no capability probe or readiness
     * probe to inject, since this executor never starts anything and never waits for it to become
     * ready.
     */
    @Bean
    TargetExecutor dockerComposeTargetExecutor(VortexProperties properties) {
        return new DockerComposeTargetExecutor(properties.engine().dockerExecutable(),
                new DockerProcess());
    }

    /**
     * The safety envelope.
     *
     * <p>Defaults are conservative and can be raised, but only deliberately. Vortex is a tool for
     * engineers investigating their own systems, not an unrestricted traffic generator, and it does
     * not assume that whatever URL is in a config file is a URL someone meant to flood.
     */
    @Bean
    ExecutionPolicy executionPolicy(VortexProperties properties) {
        Map<EnvironmentType, RequestsPerSecond> ceilings = new EnumMap<>(EnvironmentType.class);
        for (EnvironmentType type : EnvironmentType.values()) {
            ceilings.put(type, type.defaultRateCeiling());
        }

        for (String entry : properties.safety().rateCeilings()) {
            String[] parts = entry.split("=", 2);
            if (parts.length != 2) {
                continue;
            }
            try {
                ceilings.put(EnvironmentType.valueOf(parts[0].trim().toUpperCase(java.util.Locale.ROOT)),
                        RequestsPerSecond.of(Double.parseDouble(parts[1].trim())));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "vortex.safety.rate-ceilings contains an entry Vortex could not read: '"
                                + entry + "'. Use the form TYPE=rate, for example SHARED_TEST=100.", e);
            }
        }

        return new ExecutionPolicy(new SafetyLimits(ceilings,
                properties.safety().maxDuration(),
                List.copyOf(properties.safety().allowedHosts())));
    }
}
