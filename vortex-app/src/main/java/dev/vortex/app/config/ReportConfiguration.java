package dev.vortex.app.config;

import dev.vortex.app.report.ExportRegistry;
import dev.vortex.app.report.RunExporter;
import dev.vortex.core.application.RunEvidenceService;
import dev.vortex.core.port.ArtifactStore;
import dev.vortex.core.port.Repositories;
import dev.vortex.core.port.EvidenceExporter;
import dev.vortex.report.JsonEvidenceExporter;
import dev.vortex.report.MarkdownEvidenceExporter;
import dev.vortex.report.pdf.PdfEvidenceExporter;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the evidence exporters.
 *
 * <p>Explicit, like the rest of the composition root. The alternative — letting the container
 * discover implementations of the port — would mean which formats a build offers depends on what
 * happens to be on the classpath, and a report format quietly appearing or vanishing is not
 * something a user should discover from a menu.
 */
@Configuration(proxyBeanMethods = false)
public class ReportConfiguration {

    @Bean
    EvidenceExporter jsonEvidenceExporter() {
        return new JsonEvidenceExporter();
    }

    @Bean
    EvidenceExporter markdownEvidenceExporter() {
        return new MarkdownEvidenceExporter();
    }

    @Bean
    EvidenceExporter pdfEvidenceExporter() {
        return new PdfEvidenceExporter();
    }

    @Bean
    ExportRegistry exportRegistry(List<EvidenceExporter> exporters) {
        return new ExportRegistry(exporters);
    }

    @Bean
    RunExporter runExporter(Repositories.ExecutionRepository executions,
            Repositories.AnalysisRepository analyses, ArtifactStore artifacts,
            RunEvidenceService evidenceService, ExportRegistry exporters,
            dev.vortex.app.service.EvidenceContextFactory evidenceContext) {
        return new RunExporter(executions, analyses, artifacts, evidenceService, exporters,
                evidenceContext);
    }
}
