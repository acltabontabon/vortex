package com.acltabontabon.vortex.app.config;

import com.acltabontabon.vortex.core.analysis.Analysis;
import com.acltabontabon.vortex.core.analysis.AnalysisProvenance;
import com.acltabontabon.vortex.core.analysis.DeterministicSummary;
import com.acltabontabon.vortex.core.analysis.Finding;
import com.acltabontabon.vortex.core.analysis.MissingTelemetry;
import com.acltabontabon.vortex.core.analysis.Recommendation;
import com.acltabontabon.vortex.core.analysis.SloBreakpoint;
import com.acltabontabon.vortex.core.analysis.StageObservation;
import com.acltabontabon.vortex.core.analysis.SystemSaturation;
import com.acltabontabon.vortex.core.capacity.CapacityObservation;
import com.acltabontabon.vortex.core.capacity.ProductionObservation;
import com.acltabontabon.vortex.core.catalog.Operation;
import com.acltabontabon.vortex.core.catalog.ParameterSpec;
import com.acltabontabon.vortex.core.catalog.RequestBodySpec;
import com.acltabontabon.vortex.core.catalog.ServiceCatalog;
import com.acltabontabon.vortex.core.environment.Environment;
import com.acltabontabon.vortex.core.environment.EnvironmentCapabilities;
import com.acltabontabon.vortex.core.environment.TargetUrl;
import com.acltabontabon.vortex.core.execution.ExecutionArtifacts;
import com.acltabontabon.vortex.core.execution.TestExecution;
import com.acltabontabon.vortex.core.intent.TestIntent;
import com.acltabontabon.vortex.core.metrics.LatencyPercentiles;
import com.acltabontabon.vortex.core.metrics.MeasuredResults;
import com.acltabontabon.vortex.core.metrics.MetricObservation;
import com.acltabontabon.vortex.core.metrics.MetricSeries;
import com.acltabontabon.vortex.core.metrics.OperationMetrics;
import com.acltabontabon.vortex.core.metrics.SamplePoint;
import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.core.plan.EffectiveTestPlan;
import com.acltabontabon.vortex.core.plan.PlanFingerprint;
import com.acltabontabon.vortex.core.plan.PlannedOperation;
import com.acltabontabon.vortex.core.plan.SafetyDecision;
import com.acltabontabon.vortex.core.plan.ToolVersions;
import com.acltabontabon.vortex.core.workload.Workload;
import com.acltabontabon.vortex.core.workload.WorkloadSource;
import com.acltabontabon.vortex.core.shared.Concurrency;
import com.acltabontabon.vortex.core.shared.ErrorRate;
import com.acltabontabon.vortex.core.shared.Percentile;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.threshold.ErrorRateThreshold;
import com.acltabontabon.vortex.core.threshold.LatencyThreshold;
import com.acltabontabon.vortex.core.threshold.ThresholdEvaluation;
import com.acltabontabon.vortex.core.threshold.ThresholdResult;
import com.acltabontabon.vortex.core.threshold.ThresholdSet;
import com.acltabontabon.vortex.core.catalog.OperationBinding;
import com.acltabontabon.vortex.core.catalog.ExpectedResponse;
import com.acltabontabon.vortex.core.workload.ConstantArrivalRateShape;
import com.acltabontabon.vortex.core.workload.ConstantConcurrencyShape;
import com.acltabontabon.vortex.core.workload.OperationMix;
import com.acltabontabon.vortex.core.workload.RampingArrivalRateShape;
import com.acltabontabon.vortex.core.workload.RampingConcurrencyShape;
import com.acltabontabon.vortex.core.workload.Stage;
import com.acltabontabon.vortex.core.workload.WeightedOperation;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Reflection hints for GraalVM native compilation.
 *
 * <p>These are the domain types that cross a serialisation boundary — stored as JSON documents in
 * the local database, or written into an execution's artifacts. Everything else in the domain is
 * reached only through ordinary calls and needs no hint.
 *
 * <p><strong>Native compilation has not been attempted.</strong> These hints are a considered
 * starting point, not a verified configuration, and the documentation says so. See
 * {@code docs/02-architecture/architecture.adoc} (Runtime status: GraalVM native image).
 *
 * <p>The known risk is elsewhere: {@code swagger-parser} is reflection-heavy and its metadata may be
 * incomplete. It is confined to {@code vortex-openapi} behind {@code ServiceCatalogImporter}
 * precisely so it can be replaced without touching anything else.
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(VortexRuntimeHints.Registrar.class)
public class VortexRuntimeHints {

    static class Registrar implements RuntimeHintsRegistrar {

        /**
         * Types Jackson constructs when reading a stored document.
         *
         * <p>Bound through their canonical constructors with parameter names, which is why
         * {@code -parameters} is enabled in the build.
         */
        private static final Class<?>[] SERIALISED_TYPES = {
                // Plans
                EffectiveTestPlan.class, PlannedOperation.class,
                PlanFingerprint.class, SafetyDecision.class, ToolVersions.class, TestIntent.class,
                Stage.class, ConstantArrivalRateShape.class, RampingArrivalRateShape.class,
                ConstantConcurrencyShape.class, RampingConcurrencyShape.class,

                // Executions
                TestExecution.class, ExecutionArtifacts.class,

                // Measurements
                MeasuredResults.class, MetricObservation.class, LatencyPercentiles.class,
                MetricSeries.class, SamplePoint.class, TimeWindow.class, OperationMetrics.class,

                // Findings
                DeterministicSummary.class, SloBreakpoint.class, SystemSaturation.class,
                StageObservation.class, Analysis.class, Finding.class, Recommendation.class,
                MissingTelemetry.class, AnalysisProvenance.class,

                // Configuration and catalog
                ServiceCatalog.class, Operation.class, ParameterSpec.class, RequestBodySpec.class,
                OperationBinding.class, ExpectedResponse.class,
                Workload.class, OperationMix.class, WeightedOperation.class, WorkloadSource.class,
                Environment.class, EnvironmentCapabilities.class,
                TargetUrl.class, ThresholdSet.class, ThresholdEvaluation.class,
                ThresholdResult.class, LatencyThreshold.class, ErrorRateThreshold.class,

                // Capacity
                CapacityObservation.class, ProductionObservation.class,

                // Value types
                RequestsPerSecond.class, Concurrency.class, Percentile.class, ErrorRate.class,
        };

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
            for (Class<?> type : SERIALISED_TYPES) {
                hints.reflection().registerType(type,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                        MemberCategory.INVOKE_DECLARED_METHODS,
                        MemberCategory.ACCESS_DECLARED_FIELDS);
            }

            // Flyway reads migrations from the classpath at startup.
            hints.resources().registerPattern("db/migration/*.sql");

            // Prompts are versioned resources rather than string literals.
            hints.resources().registerPattern("ai/*.st");

            // The demo service's specification, served for import.
            hints.resources().registerPattern("static/*.yaml");
        }
    }
}
