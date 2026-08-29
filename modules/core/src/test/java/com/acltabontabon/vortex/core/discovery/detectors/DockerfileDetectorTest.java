package com.acltabontabon.vortex.core.discovery.detectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.acltabontabon.vortex.core.discovery.Confidence;
import com.acltabontabon.vortex.core.discovery.Finding;
import com.acltabontabon.vortex.core.discovery.FindingKind;
import com.acltabontabon.vortex.core.discovery.ProjectFile;
import com.acltabontabon.vortex.core.discovery.ProjectSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class DockerfileDetectorTest {

    private final DockerfileDetector detector = new DockerfileDetector();

    @Test
    void findsNothingWithoutADockerfile() {
        assertEquals(List.of(), detector.detect(new ProjectSnapshot("empty", List.of())));
    }

    @Test
    void aDockerfileWithAnExposedPortIsHighConfidence() {
        String dockerfile = """
                FROM eclipse-temurin:25-jre
                EXPOSE 8080
                ENTRYPOINT ["java", "-jar", "app.jar"]
                """;
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("Dockerfile", dockerfile)));

        List<Finding> findings = detector.detect(snapshot);

        assertEquals(1, findings.size());
        Finding finding = findings.get(0);
        assertEquals(FindingKind.EXECUTION_HINT_DOCKERFILE, finding.kind());
        assertEquals(Confidence.HIGH, finding.confidence());
        assertEquals("eclipse-temurin:25-jre", finding.attribute("baseImage"));
        assertEquals("8080", finding.attribute("exposedPort"));
    }

    @Test
    void aDockerfileWithoutAnExposedPortIsOnlyMediumConfidence() {
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("Dockerfile", "FROM eclipse-temurin:25-jre\n")));

        List<Finding> findings = detector.detect(snapshot);

        assertEquals(Confidence.MEDIUM, findings.get(0).confidence());
        assertEquals("", findings.get(0).attribute("exposedPort"));
    }
}
