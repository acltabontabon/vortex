package com.acltabontabon.vortex.core.discovery.detectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acltabontabon.vortex.core.discovery.Confidence;
import com.acltabontabon.vortex.core.discovery.Finding;
import com.acltabontabon.vortex.core.discovery.FindingKind;
import com.acltabontabon.vortex.core.discovery.ProjectFile;
import com.acltabontabon.vortex.core.discovery.ProjectSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class MavenPomDetectorTest {

    private final MavenPomDetector detector = new MavenPomDetector();

    @Test
    void findsNothingWithoutAPom() {
        ProjectSnapshot snapshot = new ProjectSnapshot("empty", List.of());

        assertEquals(List.of(), detector.detect(snapshot));
    }

    @Test
    void detectsMavenAndSpringBootFromDependencies() {
        String pom = """
                <project>
                  <artifactId>checkout-service</artifactId>
                  <description>Handles checkout.</description>
                  <parent>
                    <artifactId>spring-boot-starter-parent</artifactId>
                  </parent>
                  <dependencies>
                    <dependency>
                      <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                    <dependency>
                      <artifactId>spring-boot-starter-data-jpa</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """;
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("pom.xml", pom)));

        List<Finding> findings = detector.detect(snapshot);

        Finding maven = findingOfKind(findings, FindingKind.BUILD_TOOL_MAVEN);
        assertEquals(Confidence.HIGH, maven.confidence());
        assertEquals("checkout-service", maven.attribute("artifactId"));
        assertEquals("Handles checkout.", maven.attribute("description"));

        Finding springBoot = findingOfKind(findings, FindingKind.FRAMEWORK_SPRING_BOOT);
        assertEquals(Confidence.HIGH, springBoot.confidence());
        assertTrue(springBoot.evidence().stream().anyMatch(line -> line.contains("spring-boot-starter-web")));
    }

    @Test
    void aNonSpringProjectProducesNoSpringBootFinding() {
        String pom = """
                <project>
                  <artifactId>plain-java</artifactId>
                </project>
                """;
        ProjectSnapshot snapshot = new ProjectSnapshot("plain",
                List.of(new ProjectFile("pom.xml", pom)));

        List<Finding> findings = detector.detect(snapshot);

        assertEquals(1, findings.size());
        assertEquals(FindingKind.BUILD_TOOL_MAVEN, findings.get(0).kind());
    }

    @Test
    void anUnparseablePomProducesALowConfidenceFindingRatherThanThrowing() {
        ProjectSnapshot snapshot = new ProjectSnapshot("broken",
                List.of(new ProjectFile("pom.xml", "<project><unterminated>")));

        List<Finding> findings = detector.detect(snapshot);

        assertEquals(1, findings.size());
        assertEquals(Confidence.LOW, findings.get(0).confidence());
    }

    private Finding findingOfKind(List<Finding> findings, FindingKind kind) {
        return findings.stream().filter(f -> f.kind() == kind).findFirst()
                .orElseThrow(() -> new AssertionError("no finding of kind " + kind));
    }
}
