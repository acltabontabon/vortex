package dev.vortex.app.web;

import dev.vortex.app.report.RunExporter;
import dev.vortex.core.evidence.ExportFormat;
import dev.vortex.core.shared.ExecutionId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Hands a completed run's evidence to someone outside Vortex.
 *
 * <p>Thin on purpose: assembling evidence and choosing a renderer is {@link RunExporter}'s job, and
 * the command line calls the same thing. A controller that did it itself would be the second
 * implementation of what an export contains.
 */
@Controller
public class ExportController {

    private final RunExporter exporter;

    public ExportController(RunExporter exporter) {
        this.exporter = exporter;
    }

    @GetMapping("/runs/{id}/export.{format}")
    @ResponseBody
    public ResponseEntity<byte[]> export(@PathVariable String id, @PathVariable String format) {
        ExportFormat requested = ExportFormat.parse(format).orElse(null);
        if (requested == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            RunExporter.Exported document = exporter.export(ExecutionId.of(id), requested);

            // The filename is built in the domain and is ASCII-only and quote-free by construction,
            // so there is nothing here to escape and no RFC 5987 encoding to negotiate.
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(document.mediaType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + document.filename() + "\"")
                    .body(document.content());

        } catch (RunExporter.RefusedException e) {
            return switch (e.refusal()) {
                // Send the reader to the run itself, where the page explains what state it is in.
                case NOT_COMPLETED -> ResponseEntity.status(HttpStatus.SEE_OTHER)
                        .header(HttpHeaders.LOCATION, "/runs/" + id).build();
                case NO_SUCH_RUN, UNSUPPORTED_FORMAT -> ResponseEntity.notFound().build();
            };
        }
    }
}
