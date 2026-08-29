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

class SpringConfigYamlDetectorTest {

    private final SpringConfigYamlDetector detector = new SpringConfigYamlDetector();

    @Test
    void ignoresFilesThatAreNotApplicationConfig() {
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("src/main/resources/other.yml", "spring:\n  datasource:\n    url: jdbc:postgresql://localhost:5432/x\n")));

        assertEquals(List.of(), detector.detect(snapshot));
    }

    @Test
    void detectsPostgresFromADatasourceUrl() {
        String yaml = "spring:\n  datasource:\n    url: jdbc:postgresql://localhost:5432/accounts\n";
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("src/main/resources/application.yml", yaml)));

        List<Finding> findings = detector.detect(snapshot);

        Finding finding = only(findings, FindingKind.DEPENDENCY_POSTGRESQL);
        assertEquals(Confidence.MEDIUM, finding.confidence());
        assertEquals("localhost", finding.attribute("host"));
        assertEquals("5432", finding.attribute("port"));
    }

    @Test
    void detectsRedisFromItsHost() {
        String yaml = "spring:\n  data:\n    redis:\n      host: redis\n";
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("src/main/resources/application.yml", yaml)));

        Finding finding = only(detector.detect(snapshot), FindingKind.DEPENDENCY_REDIS);

        assertEquals("redis", finding.attribute("host"));
        assertEquals("6379", finding.attribute("port"), "the default Redis port when none is set");
    }

    @Test
    void prometheusExposureIsHighConfidence() {
        String yaml = "management:\n  endpoints:\n    web:\n      exposure:\n        include: health,prometheus\n";
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("src/main/resources/application.yml", yaml)));

        Finding finding = only(detector.detect(snapshot), FindingKind.OBSERVABILITY_PROMETHEUS);

        assertEquals(Confidence.HIGH, finding.confidence());
    }

    @Test
    void actuatorWithoutPrometheusIsOnlyMediumConfidence() {
        String yaml = "management:\n  endpoints:\n    web:\n      exposure:\n        include: health\n";
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("src/main/resources/application.yml", yaml)));

        Finding finding = only(detector.detect(snapshot), FindingKind.OBSERVABILITY_ACTUATOR);

        assertEquals(Confidence.MEDIUM, finding.confidence());
    }

    @Test
    void aPropertiesFileIsReadTheSameWayAsYaml() {
        String properties = "spring.datasource.url=jdbc:postgresql://db:5432/accounts\n";
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout",
                List.of(new ProjectFile("src/main/resources/application.properties", properties)));

        Finding finding = only(detector.detect(snapshot), FindingKind.DEPENDENCY_POSTGRESQL);

        assertEquals("db", finding.attribute("host"));
    }

    @Test
    void aMalformedYamlFileProducesNoFindingRatherThanThrowing() {
        ProjectSnapshot snapshot = new ProjectSnapshot("checkout", List.of(
                new ProjectFile("src/main/resources/application.yml", "spring: [ unterminated")));

        assertTrue(detector.detect(snapshot).isEmpty());
    }

    private Finding only(List<Finding> findings, FindingKind kind) {
        return findings.stream().filter(f -> f.kind() == kind).findFirst()
                .orElseThrow(() -> new AssertionError("no finding of kind " + kind));
    }
}
