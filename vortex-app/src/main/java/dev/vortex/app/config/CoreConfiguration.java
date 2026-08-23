package dev.vortex.app.config;

import dev.vortex.core.analysis.BreakpointDetector;
import dev.vortex.core.analysis.SystemSaturationDetector;
import dev.vortex.core.application.AnalysisService;
import dev.vortex.core.application.CapacityService;
import dev.vortex.core.application.CatalogImportService;
import dev.vortex.core.application.DeterministicAnalyzer;
import dev.vortex.core.application.ComparisonAnalysisService;
import dev.vortex.core.application.ComparisonEvidenceAssembler;
import dev.vortex.core.application.EpistemicIntegrityValidator;
import dev.vortex.core.application.EvidenceAssembler;
import dev.vortex.core.application.RunEvidenceService;
import dev.vortex.core.evidence.EvidenceSanitizer;
import dev.vortex.core.evidence.FindingDetector;
import dev.vortex.core.application.EvidenceReferenceValidator;
import dev.vortex.core.application.ExecutionService;
import dev.vortex.core.application.PlanResolver;
import dev.vortex.core.application.PreflightService;
import dev.vortex.core.application.ProjectService;
import dev.vortex.core.application.RequestDataResolver;
import dev.vortex.core.application.CalibrationService;
import dev.vortex.core.calibration.CalibrationPolicy;
import dev.vortex.core.calibration.WorkloadDrift;
import dev.vortex.core.port.ProductionObservationSource;
import dev.vortex.core.capacity.HeadroomCalculator;
import dev.vortex.core.application.ComparisonService;
import dev.vortex.core.comparison.RegressionEvaluator;
import dev.vortex.core.port.ArtifactStore;
import dev.vortex.core.port.DatasetStore;
import dev.vortex.core.port.Clock;
import dev.vortex.core.port.ConfigurationStore;
import dev.vortex.core.port.PerformanceAssistant;
import dev.vortex.core.port.PerformanceEngine;
import dev.vortex.core.port.Repositories;
import dev.vortex.core.port.ServiceCatalogImporter;
import dev.vortex.core.port.TargetExecutor;
import dev.vortex.core.port.TelemetryCollector;
import dev.vortex.core.safety.ExecutionPolicy;
import dev.vortex.core.target.ExternalEndpointTargetExecutor;
import dev.vortex.core.threshold.ThresholdEvaluator;
import dev.vortex.core.workload.RateAllocator;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the domain.
 *
 * <p>{@code vortex-core} is plain Java with no Spring dependency at all — Maven enforces that it
 * has no compile dependencies whatsoever. The consequence is this class: every domain and
 * application object is constructed explicitly here, in the composition root, rather than being
 * discovered by scanning for annotations.
 *
 * <p>That is a deliberate trade. It costs a few dozen lines of obvious wiring, and it buys a domain
 * model that is ordinary Java — instantiable in a test with {@code new}, free of framework
 * lifecycle, and impossible to accidentally couple to a container.
 */
@Configuration(proxyBeanMethods = false)
public class CoreConfiguration {

    @Bean
    Clock vortexClock() {
        return Clock.systemUtc();
    }

    @Bean
    RateAllocator rateAllocator() {
        return new RateAllocator();
    }

    @Bean
    RequestDataResolver requestDataResolver() {
        return new RequestDataResolver();
    }

    @Bean
    ThresholdEvaluator thresholdEvaluator() {
        return new ThresholdEvaluator();
    }

    @Bean
    BreakpointDetector breakpointDetector() {
        return new BreakpointDetector();
    }

    @Bean
    SystemSaturationDetector systemSaturationDetector() {
        return new SystemSaturationDetector();
    }

    @Bean
    HeadroomCalculator headroomCalculator() {
        return new HeadroomCalculator();
    }

    @Bean
    CalibrationPolicy calibrationPolicy() {
        return new CalibrationPolicy();
    }

    /**
     * Whether a workload's production assumption still holds.
     *
     * <p>Pure and does no I/O — it re-runs the calibration policy against the traffic recorded now
     * and compares. Given the policy rather than constructing its own so the comparison and the
     * proposal share one rounding rule.
     */
    @Bean
    WorkloadDrift workloadDrift(CalibrationPolicy calibrationPolicy) {
        return new WorkloadDrift(calibrationPolicy);
    }

    /**
     * Fetching an observation, which is a separate act from calibrating one.
     *
     * <p>Takes every registered source rather than one: which system answers is a property of the
     * project's configuration, not of how this application context was assembled.
     */
    @Bean
    CalibrationService calibrationService(List<ProductionObservationSource> sources, Clock clock) {
        return new CalibrationService(sources, clock);
    }

    @Bean
    RegressionEvaluator regressionEvaluator() {
        return new RegressionEvaluator();
    }

    @Bean
    EvidenceAssembler evidenceAssembler() {
        return new EvidenceAssembler();
    }

    @Bean
    ComparisonEvidenceAssembler comparisonEvidenceAssembler(EvidenceAssembler evidenceAssembler) {
        return new ComparisonEvidenceAssembler(evidenceAssembler);
    }

    @Bean
    EvidenceReferenceValidator evidenceReferenceValidator() {
        return new EvidenceReferenceValidator();
    }

    @Bean
    EpistemicIntegrityValidator epistemicIntegrityValidator() {
        return new EpistemicIntegrityValidator();
    }

    @Bean
    FindingDetector findingDetector() {
        return new FindingDetector();
    }

    @Bean
    EvidenceSanitizer evidenceSanitizer() {
        return new EvidenceSanitizer();
    }

    /**
     * Assembles the evidence every renderer reads.
     *
     * <p>Not to be confused with {@link EvidenceAssembler} above, which builds the much smaller
     * context the language model reasons over. That one selects a size-bounded subset for a prompt;
     * this one produces the whole document model for a reader.
     */
    @Bean
    RunEvidenceService runEvidenceService(DeterministicAnalyzer analyzer, FindingDetector findings,
            EvidenceSanitizer sanitizer, RegressionEvaluator regressions, Clock clock,
            dev.vortex.core.port.HostInformation host,
            dev.vortex.core.resource.ResourceTelemetryReader resourceTelemetryReader) {
        return new RunEvidenceService(analyzer, findings, sanitizer, regressions, clock, host,
                resourceTelemetryReader);
    }

    /**
     * The machine Vortex is running on, recorded in every export.
     *
     * <p>A port rather than a static read for the same reason the clock is one: provenance that
     * varies with whichever machine ran the test suite is not testable.
     */
    @Bean
    dev.vortex.core.port.HostInformation hostInformation() {
        return new dev.vortex.app.adapter.JdkHostInformation();
    }

    /** Turns the configured load generator budget into a concrete allocation for the host Vortex is
     *  actually running on — used identically by Settings' live preview and by a run resolving its
     *  own budget. */
    @Bean
    dev.vortex.core.resource.LoadGeneratorResourceBudgetResolver loadGeneratorResourceBudgetResolver(
            dev.vortex.core.port.HostInformation host) {
        return new dev.vortex.core.resource.LoadGeneratorResourceBudgetResolver(host);
    }

    @Bean
    PlanResolver planResolver(RateAllocator rateAllocator, RequestDataResolver requestDataResolver,
            DatasetStore datasets) {
        return new PlanResolver(rateAllocator, requestDataResolver, datasets);
    }

    @Bean
    DeterministicAnalyzer deterministicAnalyzer(ThresholdEvaluator thresholdEvaluator,
            BreakpointDetector breakpointDetector, SystemSaturationDetector saturationDetector) {
        return new DeterministicAnalyzer(thresholdEvaluator, breakpointDetector, saturationDetector);
    }

    @Bean
    PreflightService preflightService(PerformanceEngine engine, ExecutionPolicy policy,
            PreflightService.TargetProbe targetProbe, List<TargetExecutor> targetExecutors) {
        return new PreflightService(engine, policy, name -> System.getenv(name) != null, targetProbe,
                targetExecutors);
    }

    /**
     * The only {@link TargetExecutor} this build registers today: every {@code Environment} still
     * resolves to an {@link dev.vortex.core.target.ExternalEndpointTarget}, so there is exactly one
     * kind of target to prepare. A Docker or Compose executor is a separate adapter bean added here
     * later — {@link ExecutionService} already takes the whole list and picks whichever one supports
     * a given run's target, so registering a second executor is the entire change that needs.
     */
    @Bean
    TargetExecutor externalEndpointTargetExecutor() {
        return new ExternalEndpointTargetExecutor();
    }

    @Bean
    ExecutionService executionService(PerformanceEngine engine, DeterministicAnalyzer analyzer,
            Repositories.ExecutionRepository executions, ArtifactStore artifacts,
            DatasetStore datasets, TelemetryCollector telemetry, Clock clock,
            List<TargetExecutor> targetExecutors,
            dev.vortex.core.resource.LoadGeneratorResourceBudgetResolver loadGeneratorResourceBudgetResolver,
            dev.vortex.core.port.LoadGeneratorBudgetProvider loadGeneratorBudgetProvider) {
        return new ExecutionService(engine, analyzer, executions, artifacts, datasets, telemetry,
                clock, targetExecutors, loadGeneratorResourceBudgetResolver, loadGeneratorBudgetProvider);
    }

    @Bean
    ComparisonService comparisonService(Repositories.ExecutionRepository executions,
            RegressionEvaluator evaluator) {
        return new ComparisonService(executions, evaluator);
    }

    @Bean
    AnalysisService analysisService(PerformanceAssistant assistant,
            EvidenceReferenceValidator validator, EpistemicIntegrityValidator epistemicValidator,
            Repositories.AnalysisRepository analyses, Repositories.ExecutionRepository executions) {
        return new AnalysisService(assistant, validator, epistemicValidator, analyses, executions);
    }

    @Bean
    ComparisonAnalysisService comparisonAnalysisService(ComparisonService comparisonService,
            ComparisonEvidenceAssembler comparisonEvidenceAssembler, PerformanceAssistant assistant,
            EvidenceReferenceValidator validator) {
        return new ComparisonAnalysisService(comparisonService, comparisonEvidenceAssembler,
                assistant, validator);
    }

    @Bean
    ProjectService projectService(Repositories.ProjectRepository projects,
            Repositories.ProjectConfigurationRepository configurations,
            Repositories.ServiceCatalogRepository catalogs,
            Repositories.ExecutionRepository executions,
            ConfigurationStore configurationStore, ArtifactStore artifacts, Clock clock) {
        return new ProjectService(projects, configurations, catalogs, executions, configurationStore,
                artifacts, clock);
    }

    @Bean
    CatalogImportService catalogImportService(List<ServiceCatalogImporter> importers,
            Repositories.ServiceCatalogRepository catalogs) {
        return new CatalogImportService(importers, catalogs);
    }

    @Bean
    CapacityService capacityService(Repositories.CapacityObservationRepository observations,
            HeadroomCalculator headroomCalculator, Clock clock) {
        return new CapacityService(observations, headroomCalculator, clock);
    }
}
