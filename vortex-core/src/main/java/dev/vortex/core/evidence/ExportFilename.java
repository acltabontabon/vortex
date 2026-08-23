package dev.vortex.core.evidence;

import java.text.Normalizer;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Names an exported document so that it identifies its run without being opened.
 *
 * <pre>
 * vortex-checkout-service-production-peak-20260821-100000-a1b2c3d4.pdf
 * </pre>
 *
 * <p>A file that has travelled into a ticket, a chat thread or an archive has left every system that
 * could tell you what it is. Service, workload, when, and which run — in an order that also sorts
 * usefully in a directory listing.
 *
 * <p>Sanitised here, in the domain, rather than in the controller that sets the header. Because the
 * result is ASCII-only and quote-free by construction, no caller has to think about RFC 5987
 * encoding or header injection: there is nothing left in the string that could require either. A
 * sanitiser that lives next to one of its callers is a sanitiser the next caller forgets.
 */
public final class ExportFilename {

    private static final String PREFIX = "vortex";

    /** Long enough to identify, short enough that the whole name stays readable. */
    private static final int MAX_SEGMENT = 40;

    /** Matches {@code Ids}, which already guarantees an execution id is safe in a path. */
    private static final int RUN_ID_LENGTH = 8;

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

    private ExportFilename() {
    }

    public static String of(RunEvidence evidence, ExportFormat format) {
        RunIdentity identity = evidence.identity();
        var when = identity.finishedAt() != null
                ? identity.finishedAt()
                : evidence.provenance().generatedAt();

        return String.join("-",
                PREFIX,
                segment(identity.serviceName()),
                segment(identity.workloadName()),
                TIMESTAMP.format(when),
                runId(identity))
                + "." + format.extension();
    }

    private static String runId(RunIdentity identity) {
        String cleaned = slug(identity.executionId().value());
        return cleaned.length() <= RUN_ID_LENGTH ? cleaned : cleaned.substring(0, RUN_ID_LENGTH);
    }

    /** One name component: folded to ASCII, lowercased, and never empty. */
    static String segment(String raw) {
        String slug = slug(raw);
        if (slug.isEmpty()) {
            return "unknown";
        }
        return slug.length() <= MAX_SEGMENT ? slug : trimTrailingSeparator(slug.substring(0, MAX_SEGMENT));
    }

    /**
     * Folds a name down to {@code [a-z0-9-]}.
     *
     * <p>Decomposes accents first, so {@code Prüfung} becomes {@code prufung} rather than
     * {@code pr-fung}. A German service name should still be recognisable in a filename.
     */
    private static String slug(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String folded = Normalizer.normalize(raw, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);

        StringBuilder slug = new StringBuilder(folded.length());
        for (char character : folded.toCharArray()) {
            if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')) {
                slug.append(character);
            } else if (!slug.isEmpty() && slug.charAt(slug.length() - 1) != '-') {
                slug.append('-');
            }
        }
        return trimTrailingSeparator(slug.toString());
    }

    private static String trimTrailingSeparator(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '-') {
            end--;
        }
        return value.substring(0, end);
    }
}
