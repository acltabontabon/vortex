package dev.vortex.app.web;

import dev.vortex.app.web.WorkspaceDtos.ProductionDto;
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

    public record EnvironmentDto(String name, String baseUrl, String type, String typeLabel,
            String dependencyMode, String dependencyModeLabel, String classification,
            String classificationLabel, String classificationCaveat, boolean hasSecretReferences,
            Map<String, String> maskedHeaders) {
    }

    public record EnvironmentTypeOptionDto(String name, String label, String description) {
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

    // ---------------------------------------------------------------- observation

    public record ObservationSourceDto(String kind, String endpoint, String serviceIdentifier,
            String windowDisplay, Map<String, String> maskedHeaders) {
    }

    public record TestConnectionResponse(boolean succeeded, String message) {
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
