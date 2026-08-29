package com.acltabontabon.vortex.core.application;

import com.acltabontabon.vortex.core.catalog.ServiceCatalog;
import com.acltabontabon.vortex.core.discovery.Confidence;
import com.acltabontabon.vortex.core.discovery.ConflictField;
import com.acltabontabon.vortex.core.discovery.DiscoveryConflict;
import com.acltabontabon.vortex.core.discovery.DiscoveryProposal;
import com.acltabontabon.vortex.core.discovery.EnvironmentProposal;
import com.acltabontabon.vortex.core.discovery.Finding;
import com.acltabontabon.vortex.core.discovery.FindingKind;
import com.acltabontabon.vortex.core.discovery.ProjectFile;
import com.acltabontabon.vortex.core.discovery.ProjectSnapshot;
import com.acltabontabon.vortex.core.environment.DependencyMode;
import com.acltabontabon.vortex.core.environment.EnvironmentType;
import com.acltabontabon.vortex.core.lab.LocalLabSettings;
import com.acltabontabon.vortex.core.port.ProjectDetector;
import com.acltabontabon.vortex.core.port.ServiceCatalogImporter;
import com.acltabontabon.vortex.core.project.OpenApiSource;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.target.ContainerPort;
import com.acltabontabon.vortex.core.target.DockerComposeTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a read-only snapshot of a project's own files into a reviewable proposal for that
 * project's Vortex configuration.
 *
 * <p>Detection is strictly deterministic — every finding traces to a specific file and a specific
 * piece of evidence in it. Nothing here decides what to do with a finding; that decision belongs to
 * whoever reviews the resulting {@link DiscoveryProposal} and chooses what to apply. See
 * {@code docs/adr/adr-063-project-discovery-is-synchronous-and-stateless.adoc}.
 */
public final class ProjectDiscoveryService {

    private static final Set<FindingKind> DEPENDENCY_KINDS = Set.of(
            FindingKind.DEPENDENCY_POSTGRESQL, FindingKind.DEPENDENCY_REDIS,
            FindingKind.DEPENDENCY_KAFKA, FindingKind.DEPENDENCY_WIREMOCK);

    private static final List<String> OPENAPI_FILE_NAMES = List.of(
            "openapi.yaml", "openapi.yml", "openapi.json",
            "swagger.yaml", "swagger.yml", "swagger.json",
            "api-docs.yaml", "api-docs.yml", "api-docs.json");

    private final List<ProjectDetector> detectors;
    private final CatalogImportService catalogs;

    public ProjectDiscoveryService(List<ProjectDetector> detectors, CatalogImportService catalogs) {
        this.detectors = List.copyOf(Objects.requireNonNull(detectors, "detectors"));
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
    }

    public DiscoveryProposal discover(ProjectSnapshot snapshot, ProjectConfiguration existing) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(existing, "existing");

        List<Finding> findings = new ArrayList<>();
        List<String> partialFailures = new ArrayList<>();
        for (ProjectDetector detector : detectors) {
            try {
                findings.addAll(detector.detect(snapshot));
            } catch (RuntimeException e) {
                partialFailures.add(detector.name() + " could not finish: " + e.getMessage());
            }
        }

        OpenApiResolution openApi = resolveOpenApi(snapshot);
        findings.addAll(openApi.findings());
        findings = crossCheckDependencyPorts(findings);

        EnvironmentProposal environment = resolveEnvironment(findings).orElse(null);
        LocalLabSettings localLab = resolveLocalLab(findings).orElse(null);
        String serviceName = attributeFrom(findings, FindingKind.BUILD_TOOL_MAVEN, "artifactId");
        String serviceDescription = attributeFrom(findings, FindingKind.BUILD_TOOL_MAVEN, "description");

        List<DiscoveryConflict> conflicts =
                detectConflicts(existing, openApi.source(), environment, localLab);

        return new DiscoveryProposal(serviceName, serviceDescription, openApi.source().orElse(null),
                environment, localLab, findings, conflicts, partialFailures);
    }

    /**
     * Tries every known OpenAPI candidate name found in the snapshot, in the order the snapshot
     * already lists them — which is the deterministic priority order (shallowest path, then {@code
     * src/main/resources}, then alphabetical) the snapshot builder assembled it in. The first
     * candidate that actually parses wins; every other candidate this method tried is still reported
     * as a low-confidence finding rather than silently discarded.
     *
     * <p>Reuses {@link CatalogImportService#previewCatalog} rather than re-parsing OpenAPI here —
     * there is exactly one OpenAPI parser in this codebase, and it is not this one.
     */
    private OpenApiResolution resolveOpenApi(ProjectSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        for (ProjectFile file : snapshot.files()) {
            if (!OPENAPI_FILE_NAMES.contains(fileName(file.relativePath()).toLowerCase(Locale.ROOT))) {
                continue;
            }
            try {
                ServiceCatalog catalog = catalogs.previewCatalog(file.relativePath(), file.content());
                findings.add(new Finding(FindingKind.OPENAPI_SPEC, file.relativePath(),
                        List.of(catalog.operationCount() + " operation(s) found, "
                                + catalog.mutatingOperations().size() + " of them mutating"),
                        Confidence.HIGH,
                        Map.of("operationCount", String.valueOf(catalog.operationCount()),
                                "mutatingCount", String.valueOf(catalog.mutatingOperations().size()))));
                return new OpenApiResolution(findings,
                        Optional.of(new OpenApiSource.File(file.relativePath())));
            } catch (RuntimeException e) {
                findings.add(new Finding(FindingKind.OPENAPI_SPEC, file.relativePath(),
                        List.of("Could not be parsed: " + e.getMessage()), Confidence.LOW, Map.of()));
            }
        }
        return new OpenApiResolution(findings, Optional.empty());
    }

    private record OpenApiResolution(List<Finding> findings, Optional<OpenApiSource> source) {
    }

    /**
     * Compares a Spring-config-derived dependency port against the same dependency's Compose
     * container port. A Compose finding's confidence is downgraded on disagreement, and an evidence
     * line records what the two sources said, rather than silently trusting one over the other.
     */
    private List<Finding> crossCheckDependencyPorts(List<Finding> findings) {
        List<Finding> result = new ArrayList<>(findings.size());
        for (Finding finding : findings) {
            if (!DEPENDENCY_KINDS.contains(finding.kind()) || finding.attribute("composeService").isBlank()) {
                result.add(finding);
                continue;
            }

            String composePort = finding.attribute("containerPort");
            Optional<Finding> fromSpringConfig = findings.stream()
                    .filter(other -> other.kind() == finding.kind())
                    .filter(other -> other.attribute("composeService").isBlank())
                    .filter(other -> !other.attribute("port").isBlank())
                    .findFirst();

            if (fromSpringConfig.isEmpty() || composePort.isBlank()
                    || composePort.equals(fromSpringConfig.get().attribute("port"))) {
                result.add(finding);
                continue;
            }

            List<String> evidence = new ArrayList<>(finding.evidence());
            evidence.add(fromSpringConfig.get().sourceFile() + " configures port "
                    + fromSpringConfig.get().attribute("port")
                    + ", which does not match this Compose service's container port (" + composePort
                    + ").");
            result.add(new Finding(finding.kind(), finding.sourceFile(), evidence, Confidence.MEDIUM,
                    finding.attributes()));
        }
        return result;
    }

    /**
     * Proposes an execution target only when exactly one Compose service looks like the application
     * itself (a {@code build:} key) and its container port could be resolved. Anything more
     * ambiguous than that — no candidate, or more than one — is left as findings for a person to
     * read, never guessed.
     */
    private Optional<EnvironmentProposal> resolveEnvironment(List<Finding> findings) {
        List<Finding> candidates = findings.stream()
                .filter(f -> f.kind() == FindingKind.EXECUTION_HINT_COMPOSE_SERVICE)
                .filter(f -> !f.attribute("containerPort").isBlank())
                .toList();
        if (candidates.size() != 1) {
            return Optional.empty();
        }

        Finding candidate = candidates.get(0);
        int port;
        try {
            port = Integer.parseInt(candidate.attribute("containerPort"));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }

        DockerComposeTarget target;
        try {
            target = new DockerComposeTarget(candidate.sourceFile(),
                    candidate.attribute("serviceName"), new ContainerPort(port));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }

        boolean hasDependency = findings.stream().anyMatch(f -> DEPENDENCY_KINDS.contains(f.kind()));
        DependencyMode dependencyMode = hasDependency ? DependencyMode.REAL : DependencyMode.UNKNOWN;

        return Optional.of(new EnvironmentProposal(candidate.attribute("serviceName"),
                EnvironmentType.LOCAL_ISOLATED, target, dependencyMode));
    }

    /**
     * Proposes linking the project's own Compose file as the Local Lab file — never a generated one
     * — only when every detected dependency traces back to the same single file. Dependencies split
     * across more than one Compose file are still reported as findings; Discovery just does not
     * guess which file to link.
     */
    private Optional<LocalLabSettings> resolveLocalLab(List<Finding> findings) {
        List<String> composeFiles = findings.stream()
                .filter(f -> DEPENDENCY_KINDS.contains(f.kind()))
                .map(Finding::sourceFile)
                .distinct()
                .toList();
        if (composeFiles.size() != 1) {
            return Optional.empty();
        }
        try {
            return Optional.of(new LocalLabSettings(composeFiles.get(0)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private List<DiscoveryConflict> detectConflicts(ProjectConfiguration existing,
            Optional<OpenApiSource> discoveredOpenApi, EnvironmentProposal environment,
            LocalLabSettings localLab) {
        List<DiscoveryConflict> conflicts = new ArrayList<>();

        discoveredOpenApi.ifPresent(discovered -> existing.openApiSourceIfPresent().ifPresent(current -> {
            if (!current.describe().equals(discovered.describe())) {
                conflicts.add(new DiscoveryConflict(ConflictField.OPENAPI_SOURCE, current.describe(),
                        discovered.describe()));
            }
        }));

        if (environment != null) {
            existing.environmentByName(environment.name()).ifPresent(current -> {
                String currentSummary = current.target().summary();
                String discoveredSummary = environment.target().summary();
                if (!currentSummary.equals(discoveredSummary)) {
                    conflicts.add(new DiscoveryConflict(ConflictField.EXECUTION_TARGET, currentSummary,
                            discoveredSummary));
                }
            });
        }

        if (localLab != null) {
            existing.localLabIfPresent().ifPresent(current -> {
                if (!current.composeFile().equals(localLab.composeFile())) {
                    conflicts.add(new DiscoveryConflict(ConflictField.LOCAL_LAB, current.describe(),
                            localLab.describe()));
                }
            });
        }

        return conflicts;
    }

    private static String attributeFrom(List<Finding> findings, FindingKind kind, String attribute) {
        return findings.stream()
                .filter(f -> f.kind() == kind)
                .map(f -> f.attribute(attribute))
                .filter(value -> !value.isBlank())
                .findFirst().orElse("");
    }

    private static String fileName(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? relativePath : relativePath.substring(slash + 1);
    }
}
