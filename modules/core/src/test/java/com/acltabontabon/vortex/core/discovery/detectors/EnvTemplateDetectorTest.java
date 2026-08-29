package com.acltabontabon.vortex.core.discovery.detectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.acltabontabon.vortex.core.discovery.Confidence;
import com.acltabontabon.vortex.core.discovery.Finding;
import com.acltabontabon.vortex.core.discovery.FindingKind;
import com.acltabontabon.vortex.core.discovery.ProjectFile;
import com.acltabontabon.vortex.core.discovery.ProjectSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class EnvTemplateDetectorTest {

    private final EnvTemplateDetector detector = new EnvTemplateDetector();

    @Test
    void aRealDotEnvIsNeverInspected() {
        // A real .env is never even a candidate the snapshot builder would include, but the
        // detector itself must also refuse to treat it as a template if it somehow arrived here.
        ProjectSnapshot snapshot = new ProjectSnapshot("secrets",
                List.of(new ProjectFile(".env", "DB_PASSWORD=supersecretvalue123\n")));

        assertEquals(List.of(), detector.detect(snapshot));
    }

    @Test
    void namesAreReportedButValuesNeverAre() {
        ProjectSnapshot snapshot = new ProjectSnapshot("secrets", List.of(
                new ProjectFile(".env.example", "DB_PASSWORD=changeme\nAPI_KEY=sk-liveKeyDoNotCommit\n")));

        List<Finding> findings = detector.detect(snapshot);

        assertEquals(1, findings.size());
        Finding finding = findings.get(0);
        assertEquals(FindingKind.ENV_TEMPLATE, finding.kind());
        assertEquals(Confidence.HIGH, finding.confidence());
        assertEquals("DB_PASSWORD,API_KEY", finding.attribute("names"));

        String rendered = String.join(" ", finding.evidence());
        assertFalse(rendered.contains("changeme"), "a template value must never appear in evidence");
        assertFalse(rendered.contains("sk-liveKeyDoNotCommit"),
                "a template value must never appear in evidence, even one shaped like a real secret");
    }

    @Test
    void anEmptyTemplateProducesNoFinding() {
        ProjectSnapshot snapshot = new ProjectSnapshot("empty",
                List.of(new ProjectFile(".env.example", "# nothing declared yet\n")));

        assertEquals(List.of(), detector.detect(snapshot));
    }
}
