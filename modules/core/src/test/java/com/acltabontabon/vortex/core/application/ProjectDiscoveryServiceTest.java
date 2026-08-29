package com.acltabontabon.vortex.core.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acltabontabon.vortex.core.catalog.ServiceCatalog;
import com.acltabontabon.vortex.core.discovery.Confidence;
import com.acltabontabon.vortex.core.discovery.ConflictField;
import com.acltabontabon.vortex.core.discovery.DiscoveryProposal;
import com.acltabontabon.vortex.core.discovery.Finding;
import com.acltabontabon.vortex.core.discovery.FindingKind;
import com.acltabontabon.vortex.core.discovery.ProjectFile;
import com.acltabontabon.vortex.core.discovery.ProjectSnapshot;
import com.acltabontabon.vortex.core.environment.DependencyMode;
import com.acltabontabon.vortex.core.environment.Environment;
import com.acltabontabon.vortex.core.environment.EnvironmentCapabilities;
import com.acltabontabon.vortex.core.environment.EnvironmentType;
import com.acltabontabon.vortex.core.environment.TargetUrl;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.lab.LocalLabSettings;
import com.acltabontabon.vortex.core.port.ProjectDetector;
import com.acltabontabon.vortex.core.port.Repositories.ServiceCatalogRepository;
import com.acltabontabon.vortex.core.port.ServiceCatalogImporter;
import com.acltabontabon.vortex.core.project.OpenApiSource;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.shared.EnvironmentId;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.target.ExternalEndpointTarget;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProjectDiscoveryServiceTest {

    @Test
    void aDetectorFailureBecomesAPartialFailureRatherThanCrashingTheScan() {
        ProjectDetector working = new FakeDetector("Working",
                List.of(highFinding(FindingKind.BUILD_TOOL_MAVEN, "pom.xml")));
        ProjectDetector broken = new ThrowingDetector("Broken");
        ProjectDiscoveryService service = serviceWith(List.of(working, broken));

        DiscoveryProposal proposal = service.discover(emptySnapshot(), ProjectConfiguration.empty());

        assertEquals(1, proposal.findings().size());
        assertTrue(proposal.partialFailures().stream().anyMatch(line -> line.contains("Broken")));
    }

    @Test
    void proposesAnEnvironmentOnlyForExactlyOneUnambiguousComposeCandidate() {
        Finding candidate = new Finding(FindingKind.EXECUTION_HINT_COMPOSE_SERVICE, "compose.yaml",
                List.of("build: app"), Confidence.HIGH,
                Map.of("serviceName", "app", "containerPort", "8080"));
        ProjectDiscoveryService service = serviceWith(List.of(new FakeDetector("Compose", List.of(candidate))));

        DiscoveryProposal proposal = service.discover(emptySnapshot(), ProjectConfiguration.empty());

        assertTrue(proposal.proposedEnvironmentIfPresent().isPresent());
        assertEquals("app", proposal.proposedEnvironment().name());
    }

    @Test
    void refusesToGuessBetweenTwoAmbiguousComposeCandidates() {
        Finding first = new Finding(FindingKind.EXECUTION_HINT_COMPOSE_SERVICE, "compose.yaml",
                List.of(), Confidence.HIGH, Map.of("serviceName", "app", "containerPort", "8080"));
        Finding second = new Finding(FindingKind.EXECUTION_HINT_COMPOSE_SERVICE, "infra/compose.yaml",
                List.of(), Confidence.HIGH, Map.of("serviceName", "worker", "containerPort", "9090"));
        ProjectDiscoveryService service =
                serviceWith(List.of(new FakeDetector("Compose", List.of(first, second))));

        DiscoveryProposal proposal = service.discover(emptySnapshot(), ProjectConfiguration.empty());

        assertFalse(proposal.proposedEnvironmentIfPresent().isPresent());
    }

    @Test
    void proposesLocalLabOnlyWhenEveryDependencyTracesToOneComposeFile() {
        Finding postgres = new Finding(FindingKind.DEPENDENCY_POSTGRESQL, "compose.yaml", List.of(),
                Confidence.HIGH, Map.of("composeService", "postgres"));
        Finding redis = new Finding(FindingKind.DEPENDENCY_REDIS, "compose.yaml", List.of(),
                Confidence.HIGH, Map.of("composeService", "redis"));
        ProjectDiscoveryService service =
                serviceWith(List.of(new FakeDetector("Compose", List.of(postgres, redis))));

        DiscoveryProposal proposal = service.discover(emptySnapshot(), ProjectConfiguration.empty());

        assertEquals(Optional.of(new LocalLabSettings("compose.yaml")),
                proposal.proposedLocalLabIfPresent());
    }

    @Test
    void doesNotProposeALocalLabWhenDependenciesSpanMoreThanOneFile() {
        Finding postgres = new Finding(FindingKind.DEPENDENCY_POSTGRESQL, "compose.yaml", List.of(),
                Confidence.HIGH, Map.of("composeService", "postgres"));
        Finding redis = new Finding(FindingKind.DEPENDENCY_REDIS, "infra/compose.yaml", List.of(),
                Confidence.HIGH, Map.of("composeService", "redis"));
        ProjectDiscoveryService service =
                serviceWith(List.of(new FakeDetector("Compose", List.of(postgres, redis))));

        DiscoveryProposal proposal = service.discover(emptySnapshot(), ProjectConfiguration.empty());

        assertTrue(proposal.proposedLocalLabIfPresent().isEmpty());
    }

    @Test
    void downgradesConfidenceWhenSpringConfigAndComposeDisagreeOnAPort() {
        Finding fromCompose = new Finding(FindingKind.DEPENDENCY_POSTGRESQL, "compose.yaml",
                List.of("image: postgres:17"), Confidence.HIGH,
                Map.of("composeService", "postgres", "containerPort", "5433"));
        Finding fromSpringConfig = new Finding(FindingKind.DEPENDENCY_POSTGRESQL, "application.yml",
                List.of("spring.datasource.url=jdbc:postgresql://localhost:5432/accounts"),
                Confidence.MEDIUM, Map.of("port", "5432"));
        ProjectDiscoveryService service = serviceWith(
                List.of(new FakeDetector("Sources", List.of(fromCompose, fromSpringConfig))));

        DiscoveryProposal proposal = service.discover(emptySnapshot(), ProjectConfiguration.empty());

        Finding downgraded = proposal.findings().stream()
                .filter(f -> !f.attribute("composeService").isBlank())
                .findFirst().orElseThrow();
        assertEquals(Confidence.MEDIUM, downgraded.confidence());
        assertTrue(downgraded.evidence().stream().anyMatch(line -> line.contains("does not match")));
    }

    @Test
    void anOpenApiCandidateThatParsesBecomesAHighConfidenceFindingAndAProposedSource() {
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("openapi.yaml", "irrelevant, the fake importer ignores it")));
        ProjectDiscoveryService service = serviceWith(List.of(), fakeCatalogs("openapi.yaml"));

        DiscoveryProposal proposal = service.discover(snapshot, ProjectConfiguration.empty());

        Finding finding = proposal.findings().stream()
                .filter(f -> f.kind() == FindingKind.OPENAPI_SPEC).findFirst().orElseThrow();
        assertEquals(Confidence.HIGH, finding.confidence());
        assertEquals(Optional.of(new OpenApiSource.File("openapi.yaml")),
                proposal.proposedOpenApiSourceIfPresent());
    }

    @Test
    void anOpenApiCandidateThatFailsToParseIsReportedButDoesNotBecomeAProposal() {
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("openapi.yaml", "not an openapi document")));
        ProjectDiscoveryService service = serviceWith(List.of(), fakeCatalogs("nothing-matches"));

        DiscoveryProposal proposal = service.discover(snapshot, ProjectConfiguration.empty());

        Finding finding = proposal.findings().stream()
                .filter(f -> f.kind() == FindingKind.OPENAPI_SPEC).findFirst().orElseThrow();
        assertEquals(Confidence.LOW, finding.confidence());
        assertTrue(proposal.proposedOpenApiSourceIfPresent().isEmpty());
    }

    @Test
    void flagsAConflictWhenTheDiscoveredOpenApiSourceDisagreesWithTheSavedOne() {
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("openapi.yaml", "ignored by the fake importer")));
        ProjectDiscoveryService service = serviceWith(List.of(), fakeCatalogs("openapi.yaml"));
        ProjectConfiguration existing = ProjectConfiguration.empty()
                .withOpenApiSource(new OpenApiSource.File("docs/openapi.yaml"));

        DiscoveryProposal proposal = service.discover(snapshot, existing);

        assertEquals(1, proposal.conflicts().size());
        assertEquals(ConflictField.OPENAPI_SOURCE, proposal.conflicts().get(0).field());
    }

    @Test
    void noConflictIsRaisedWhenNothingIsAlreadySaved() {
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("openapi.yaml", "ignored by the fake importer")));
        ProjectDiscoveryService service = serviceWith(List.of(), fakeCatalogs("openapi.yaml"));

        DiscoveryProposal proposal = service.discover(snapshot, ProjectConfiguration.empty());

        assertEquals(List.of(), proposal.conflicts());
    }

    @Test
    void flagsAConflictWhenTheDiscoveredExecutionTargetDisagreesWithTheSavedOne() {
        Finding candidate = new Finding(FindingKind.EXECUTION_HINT_COMPOSE_SERVICE, "compose.yaml",
                List.of(), Confidence.HIGH, Map.of("serviceName", "app", "containerPort", "8080"));
        ProjectDiscoveryService service = serviceWith(List.of(new FakeDetector("Compose", List.of(candidate))));

        Environment existingEnvironment = new Environment(EnvironmentId.generate(), "app",
                EnvironmentType.LOCAL_ISOLATED, new ExternalEndpointTarget(TargetUrl.of("http://localhost:8080")),
                EnvironmentCapabilities.localIsolated(), DependencyMode.MOCKED, Map.of());
        ProjectConfiguration existing =
                ProjectConfiguration.empty().withEnvironments(List.of(existingEnvironment));

        DiscoveryProposal proposal = service.discover(emptySnapshot(), existing);

        assertEquals(1, proposal.conflicts().size());
        assertEquals(ConflictField.EXECUTION_TARGET, proposal.conflicts().get(0).field());
    }

    // ---------------------------------------------------------------- helpers

    private ProjectSnapshot emptySnapshot() {
        return new ProjectSnapshot("checkout", List.of());
    }

    private Finding highFinding(FindingKind kind, String sourceFile) {
        return new Finding(kind, sourceFile, List.of("evidence"), Confidence.HIGH, Map.of());
    }

    private ProjectDiscoveryService serviceWith(List<ProjectDetector> detectors) {
        return serviceWith(detectors, fakeCatalogs("nothing-matches-anything"));
    }

    private ProjectDiscoveryService serviceWith(List<ProjectDetector> detectors,
            CatalogImportService catalogs) {
        return new ProjectDiscoveryService(detectors, catalogs);
    }

    private CatalogImportService fakeCatalogs(String supportedRef) {
        return new CatalogImportService(List.of(new FakeImporter(supportedRef)), new NoopRepository());
    }

    private record FakeDetector(String name, List<Finding> findings) implements ProjectDetector {
        @Override
        public List<Finding> detect(ProjectSnapshot snapshot) {
            return findings;
        }
    }

    private record ThrowingDetector(String name) implements ProjectDetector {
        @Override
        public List<Finding> detect(ProjectSnapshot snapshot) {
            throw new RuntimeException("could not read the file");
        }
    }

    private record FakeImporter(String supportedRef) implements ServiceCatalogImporter {
        @Override
        public boolean supports(String sourceRef) {
            return supportedRef.equals(sourceRef);
        }

        @Override
        public ServiceCatalog importFrom(String sourceRef, String content) {
            return Fixtures.catalog();
        }
    }

    private static final class NoopRepository implements ServiceCatalogRepository {
        @Override
        public void save(ProjectId projectId, ServiceCatalog catalog) {
        }

        @Override
        public Optional<ServiceCatalog> findByProject(ProjectId projectId) {
            return Optional.empty();
        }
    }
}
