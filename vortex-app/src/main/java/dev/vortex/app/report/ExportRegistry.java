package dev.vortex.app.report;

import dev.vortex.core.evidence.ExportFormat;
import dev.vortex.core.evidence.RunEvidence;
import dev.vortex.core.port.EvidenceExporter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The exporters this installation has, keyed by format.
 *
 * <p>A lookup rather than a chain of conditionals, so the web layer and the command line ask the
 * same question in the same way and neither has to know which module supplies a renderer.
 *
 * <p>{@link ExportFormat#HTML} is deliberately not here. It is rendered by the template engine from
 * the same evidence, and a registry that pretended otherwise would push callers into handling an
 * exporter that does not exist.
 */
public final class ExportRegistry {

    private final Map<ExportFormat, EvidenceExporter> exporters = new LinkedHashMap<>();

    public ExportRegistry(java.util.List<EvidenceExporter> available) {
        available.forEach(exporter -> exporters.put(exporter.format(), exporter));
    }

    public Optional<EvidenceExporter> exporterFor(ExportFormat format) {
        return Optional.ofNullable(exporters.get(format));
    }

    public boolean supports(ExportFormat format) {
        return exporters.containsKey(format);
    }

    /** The formats a user can actually be offered, in a stable order. */
    public java.util.List<ExportFormat> available() {
        return java.util.List.copyOf(exporters.keySet());
    }

    public byte[] export(ExportFormat format, RunEvidence evidence) {
        return exporterFor(format)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Vortex cannot export " + format.extension() + ". Available formats: "
                                + ExportFormat.available()))
                .export(evidence);
    }
}
