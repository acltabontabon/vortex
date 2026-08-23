package dev.vortex.core.port;

import dev.vortex.core.evidence.ExportFormat;
import dev.vortex.core.evidence.RunEvidence;

/**
 * Renders a completed run's evidence into a portable document.
 *
 * <p>A port rather than an interface in the rendering module, because the web layer and the command
 * line both dispatch on a format without knowing which module supplies the renderer — and because
 * what an export <em>is</em> belongs to the domain even though no domain code can produce one.
 *
 * <p>The single argument is deliberate. An exporter sees a {@link RunEvidence} and nothing else: not
 * the execution, not the plan, not a repository. Evidence is sanitised on the way out of assembly,
 * so an exporter that reached past it would be reaching around the only gate between a plan and a
 * published document. An implementation that finds itself needing something absent from
 * {@code RunEvidence} is asking for something that has not been through the sanitiser.
 *
 * <p>Returns bytes rather than writing to a stream. A report is bounded by design — a run with a
 * hundred operations still produces a document measured in hundreds of kilobytes — and rendering
 * fully before anything is sent means a failure halfway through produces a clean error rather than a
 * truncated file the reader cannot tell is truncated.
 */
public interface EvidenceExporter {

    ExportFormat format();

    /**
     * Renders the evidence.
     *
     * @throws EvidenceExportException when the document cannot be produced
     */
    byte[] export(RunEvidence evidence);

    /** Thrown when rendering fails, so a caller can report it without unwrapping an I/O error. */
    class EvidenceExportException extends RuntimeException {

        public EvidenceExportException(String message, Throwable cause) {
            super(message, cause);
        }

        public EvidenceExportException(String message) {
            super(message);
        }
    }
}
