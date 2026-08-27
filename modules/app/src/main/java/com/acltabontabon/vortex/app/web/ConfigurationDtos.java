package com.acltabontabon.vortex.app.web;

import com.acltabontabon.vortex.app.web.WorkspaceDtos.ProductionDto;
import java.util.List;
import java.util.Map;

/**
 * Wire shapes for Configuration — what {@code ConfigurationApiController} assembles from
 * {@code understand.html}/{@code understand-sections.html}'s eight sections.
 *
 * <p>Reuses {@link ProductionDto} and {@link WorkspaceDtos.MixRowDto} verbatim for production
 * traffic, since Overview/Evidence already established that shape; everything else here is net-new,
 * since nothing before Configuration read the catalog, the environments list, the local lab, or an
 * observation source as JSON.
 */
public final class ConfigurationDtos {

    private ConfigurationDtos() {
    }

    // ---------------------------------------------------------------- environments

    /**
     * The target-detail fields ({@code image} through {@code composeService}) are {@code null} when
     * not applicable to this environment's target kind — they exist so an edit form can prefill
     * exactly what {@code EnvironmentRequest} accepts on write, the read side of the same shape.
     */
    public record EnvironmentDto(String name, String baseUrl, String type, String typeLabel,
            String dependencyMode, String dependencyModeLabel, String classification,
            String classificationLabel, String classificationCaveat, boolean hasSecretReferences,
            Map<String, String> maskedHeaders, ExecutionTargetSummaryDto target, boolean productionLike,
            String image, Integer containerPort, Integer cpuMillicores, Long memoryMebibytes,
            String readinessPath, Integer readinessExpectedStatus, Integer readinessTimeoutSeconds,
            String composeFile, String composeService) {
    }

    /**
     * What this environment tests, and how Vortex reaches or controls it — {@code kind} is one of
     * {@code EXTERNAL_ENDPOINT}/{@code DOCKER_IMAGE}/{@code DOCKER_COMPOSE}, the same vocabulary as
     * {@code ConfigurationApiController.EnvironmentRequest.targetKind} and {@code vortex.yaml}'s
     * {@code target.kind}.
     */
    public record ExecutionTargetSummaryDto(String kind, String summary, String ownershipLabel) {
    }

    public record EnvironmentTypeOptionDto(String name, String label, String description) {
    }

    /**
     * The outcome of validating a target's non-mutating availability checks — {@code
     * POST .../target/validate}. Distinct from {@link TestConnectionResponse}, which is a different,
     * earlier feature (production-observation source connectivity) reusing similar words for an
     * unrelated concern.
     */
    public record TargetValidationResponse(boolean valid, List<String> checks) {
    }

    public record DependencyModeOptionDto(String name, String label, String description) {
    }

    // ---------------------------------------------------------------- local lab

    public record LabStatusDto(boolean usable, boolean dockerAvailable, boolean daemonRunning,
            boolean composeAvailable, String version, String remedy) {
    }

    public record LabActivityDto(String operationLabel, String operationCommand,
            String composeFileDisplay, boolean succeeded, boolean failed, String resultMessage,
            List<String> output) {
    }

    public record LocalLabDto(boolean configured, String composeFileDisplay, LabStatusDto status,
            boolean running, LabActivityDto activity) {
    }

    // ---------------------------------------------------------------- production

    public record WorkloadSuggestionDto(String name, String rateDisplay, String derivation) {
    }

    public record FetchProductionResponse(boolean succeeded, String error, ProductionDto preview) {
    }

    /** Distinct from {@link FetchProductionResponse}: {@code production} here is what was actually
     *  persisted, not a preview — see {@code ConfigurationApiController#fetchAndSaveProductionObservation}. */
    public record FetchAndSaveProductionResponse(boolean succeeded, String error, ProductionDto production) {
    }

    // ---------------------------------------------------------------- observation

    public record ObservationSourceDto(String kind, String transport, String endpoint,
            String serviceIdentifier, String windowDisplay, Map<String, String> maskedHeaders) {
    }

    public record TestConnectionResponse(boolean succeeded, String message) {
    }

    public record EntityCandidateDto(String id, String name) {
    }

    /** {@code problem}/{@code remedy} are only set when {@code succeeded} is false. */
    public record EntityLookupResponse(boolean succeeded, List<EntityCandidateDto> candidates,
            String problem, String remedy) {
    }

    // ---------------------------------------------------------------- objectives

    public record ThresholdEditDto(Long p95Millis, Long p99Millis, Double errorPercent,
            List<String> describe) {
    }

    // ---------------------------------------------------------------- configuration file

    public record ConfigurationFileDto(String yaml, String path) {
    }

    // ---------------------------------------------------------------- operations / catalog

    public record OperationDto(String id, String method, String path, String summary,
            String primaryTag, String kind, boolean requiresReview, boolean reviewed) {
    }

    public record CatalogDto(boolean imported, String title, String sourceRef, int operationCount,
            int mutatingCount, List<OperationDto> operations) {
    }

    // ---------------------------------------------------------------- the aggregate read

    public record ConfigurationDto(
            String name,
            String serviceVersion,
            List<EnvironmentDto> environments,
            List<EnvironmentTypeOptionDto> environmentTypes,
            List<DependencyModeOptionDto> dependencyModes,
            LocalLabDto localLab,
            ProductionDto production,
            List<WorkloadSuggestionDto> calibrationSuggestions,
            ObservationSourceDto observationSource,
            ThresholdEditDto thresholds,
            CatalogDto catalog,
            ConfigurationFileDto file) {
    }
}
