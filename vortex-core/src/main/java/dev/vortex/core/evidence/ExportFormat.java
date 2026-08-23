package dev.vortex.core.evidence;

import java.util.Locale;
import java.util.Optional;

/**
 * The forms a run's evidence can be handed to someone else in.
 *
 * <p>Four, and deliberately not extensible by configuration. Each exists because it answers a
 * different question about who is reading and where: a pipeline parses JSON, a merge request takes
 * Markdown, a release review takes a PDF, and a colleague with a browser takes the HTML page. A
 * fifth format needs a reason, not a plugin point.
 *
 * <p>{@code HTML} has no {@code EvidenceExporter}: it is rendered by the template engine in the web
 * adapter from the same {@link RunEvidence}. It appears here so the UI and the CLI can talk about
 * every format uniformly.
 */
public enum ExportFormat {

    JSON("json", "application/json", "Machine-readable evidence",
            "For pipelines, comparison and archival."),
    MARKDOWN("md", "text/markdown", "Markdown summary",
            "For merge requests, tickets and incident reviews."),
    PDF("pdf", "application/pdf", "PDF report",
            "For release reviews and anyone without Vortex."),
    HTML("html", "text/html", "Printable report",
            "The full report in the browser.");

    private final String extension;
    private final String mediaType;
    private final String label;
    private final String purpose;

    ExportFormat(String extension, String mediaType, String label, String purpose) {
        this.extension = extension;
        this.mediaType = mediaType;
        this.label = label;
        this.purpose = purpose;
    }

    public String extension() {
        return extension;
    }

    public String mediaType() {
        return mediaType;
    }

    public String label() {
        return label;
    }

    public String purpose() {
        return purpose;
    }

    /** Whether a document of this format is produced by an {@code EvidenceExporter}. */
    public boolean isExported() {
        return this != HTML;
    }

    /**
     * Parses a format named on the command line or in a URL.
     *
     * <p>Accepts the extension and the constant name, case-insensitively, plus {@code markdown} for
     * {@code md}. Returns empty rather than throwing, so the caller can report the valid set — an
     * unknown format is a user mistake with an obvious remedy, not an exception.
     */
    public static Optional<ExportFormat> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalised = raw.trim().toLowerCase(Locale.ROOT);
        for (ExportFormat format : values()) {
            if (format.extension.equals(normalised)
                    || format.name().toLowerCase(Locale.ROOT).equals(normalised)) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    /** The formats a user may ask for, for a usage message. */
    public static String available() {
        return String.join(", ", JSON.extension, MARKDOWN.extension, PDF.extension);
    }
}
