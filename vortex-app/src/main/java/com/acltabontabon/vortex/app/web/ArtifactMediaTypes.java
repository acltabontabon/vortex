package com.acltabontabon.vortex.app.web;

import java.util.Locale;
import org.springframework.http.MediaType;

/**
 * Chooses how a stored artifact is served.
 *
 * <p>Everything in an execution directory used to be sent as {@code text/plain} and read into a
 * String on the way out. That was harmless while every artifact was text, and stops being harmless
 * the moment one of them is a PDF or a gzip: a browser shown a compressed stream labelled as text
 * displays mojibake, and the file it saves is corrupt.
 */
final class ArtifactMediaTypes {

    private static final MediaType MARKDOWN = MediaType.valueOf("text/markdown");
    private static final MediaType PDF = MediaType.APPLICATION_PDF;
    private static final MediaType GZIP = MediaType.valueOf("application/gzip");

    private ArtifactMediaTypes() {
    }

    static MediaType forName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            return MediaType.APPLICATION_JSON;
        }
        if (lower.endsWith(".md")) {
            return MARKDOWN;
        }
        if (lower.endsWith(".pdf")) {
            return PDF;
        }
        if (lower.endsWith(".gz")) {
            return GZIP;
        }
        // A generated k6 script is served as text deliberately. It is evidence to be read, and
        // labelling it as JavaScript invites a browser to treat it as something to run.
        if (lower.endsWith(".js") || lower.endsWith(".log") || lower.endsWith(".txt")) {
            return MediaType.TEXT_PLAIN;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /** Text is shown in place; anything else is offered as a download. */
    static String dispositionFor(String name) {
        MediaType type = forName(name);
        boolean inline = MediaType.TEXT_PLAIN.equals(type)
                || MediaType.APPLICATION_JSON.equals(type)
                || MARKDOWN.equals(type);
        return (inline ? "inline" : "attachment") + "; filename=\"" + name + "\"";
    }
}
