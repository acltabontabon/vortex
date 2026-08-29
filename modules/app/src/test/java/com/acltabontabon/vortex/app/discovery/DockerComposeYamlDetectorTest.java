package com.acltabontabon.vortex.app.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acltabontabon.vortex.core.discovery.Confidence;
import com.acltabontabon.vortex.core.discovery.Finding;
import com.acltabontabon.vortex.core.discovery.FindingKind;
import com.acltabontabon.vortex.core.discovery.ProjectFile;
import com.acltabontabon.vortex.core.discovery.ProjectSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class DockerComposeYamlDetectorTest {

    private final DockerComposeYamlDetector detector = new DockerComposeYamlDetector();

    @Test
    void ignoresFilesThatAreNotCompose() {
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("values.yaml", "services:\n  postgres:\n    image: postgres:17\n")));

        assertEquals(List.of(), detector.detect(snapshot));
    }

    @Test
    void detectsKnownDependencyImagesWithTheirResolvedContainerPort() {
        String compose = """
                services:
                  postgres:
                    image: postgres:17
                    ports:
                      - "5433:5432"
                  redis:
                    image: redis:8
                """;
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("compose.yaml", compose)));

        List<Finding> findings = detector.detect(snapshot);

        Finding postgres = only(findings, FindingKind.DEPENDENCY_POSTGRESQL);
        assertEquals(Confidence.HIGH, postgres.confidence());
        assertEquals("postgres:17", postgres.attribute("image"));
        assertEquals("5432", postgres.attribute("containerPort"));

        Finding redis = only(findings, FindingKind.DEPENDENCY_REDIS);
        assertEquals("", redis.attribute("containerPort"), "no ports: entry means nothing to resolve");
    }

    @Test
    void anUnrecognisedImageProducesNoDependencyFinding() {
        String compose = "services:\n  cache:\n    image: memcached:1.6\n";
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("compose.yaml", compose)));

        assertEquals(List.of(), detector.detect(snapshot));
    }

    @Test
    void aServiceWithABuildKeyIsAnExecutionHintWithItsContainerPort() {
        String compose = """
                services:
                  app:
                    build: .
                    ports:
                      - "9090:8080"
                """;
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("compose.yaml", compose)));

        Finding finding = only(detector.detect(snapshot), FindingKind.EXECUTION_HINT_COMPOSE_SERVICE);

        assertEquals(Confidence.HIGH, finding.confidence());
        assertEquals("app", finding.attribute("serviceName"));
        assertEquals("8080", finding.attribute("containerPort"));
    }

    @Test
    void aBuildServiceWithNoResolvablePortIsOnlyMediumConfidence() {
        String compose = "services:\n  app:\n    build: .\n";
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("compose.yaml", compose)));

        Finding finding = only(detector.detect(snapshot), FindingKind.EXECUTION_HINT_COMPOSE_SERVICE);

        assertEquals(Confidence.MEDIUM, finding.confidence());
        assertEquals("", finding.attribute("containerPort"));
    }

    @Test
    void aMalformedComposeFileProducesALowConfidenceFindingRatherThanThrowing() {
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("compose.yaml", "services:\n  app:\n    ports: [\"8080\"")));

        List<Finding> findings = detector.detect(snapshot);

        assertEquals(1, findings.size());
        assertEquals(Confidence.LOW, findings.get(0).confidence());
    }

    @Test
    void aFileWithNoServicesKeyProducesNoFindings() {
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("compose.yaml", "version: '3'\n")));

        assertTrue(detector.detect(snapshot).isEmpty());
    }

    private Finding only(List<Finding> findings, FindingKind kind) {
        return findings.stream().filter(f -> f.kind() == kind).findFirst()
                .orElseThrow(() -> new AssertionError("no finding of kind " + kind));
    }
}
