package dev.vortex.report.pdf;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Makes text safe for a base-14 PDF font.
 *
 * <p>Vortex embeds no fonts, which keeps the report small, licence-free and byte-identical across
 * machines. The cost is Helvetica's WinAnsi encoding: a character outside it is not approximated,
 * it is silently dropped. In a document about missing measurements, a report that quietly loses
 * characters would be a bad joke — and worse, the loss is invisible until somebody with an accent in
 * their service name opens the file.
 *
 * <p>So every string is transliterated on the way in. The table covers what this codebase actually
 * emits — the arrows and bullets in findings, the en dashes in ranges, the mask character — plus
 * accented Latin, which is folded rather than dropped so a name stays recognisable.
 */
public final class PdfText {

    private static final Map<Character, String> SUBSTITUTIONS = new LinkedHashMap<>();

    static {
        // Punctuation Vortex emits deliberately.
        SUBSTITUTIONS.put('→', "->");     // arrow, in signal movement
        SUBSTITUTIONS.put('—', "-");      // em dash
        SUBSTITUTIONS.put('–', "-");      // en dash
        SUBSTITUTIONS.put('•', "*");      // bullet, and the secret mask
        SUBSTITUTIONS.put('·', "-");      // middle dot, in "service - workload"
        SUBSTITUTIONS.put('…', "...");
        SUBSTITUTIONS.put('‘', "'");
        SUBSTITUTIONS.put('’', "'");
        SUBSTITUTIONS.put('“', "\"");
        SUBSTITUTIONS.put('”', "\"");
        SUBSTITUTIONS.put('×', "x");
        SUBSTITUTIONS.put('≈', "~");
        SUBSTITUTIONS.put('≥', ">=");
        SUBSTITUTIONS.put('≤', "<=");
        SUBSTITUTIONS.put('′', "'");
        // Block characters, used by the sparklines the Markdown export draws. They have no Latin
        // equivalent, and a report that printed "?????" would look broken rather than restrained.
        SUBSTITUTIONS.put('▁', "_");
        SUBSTITUTIONS.put('█', "#");
    }

    private PdfText() {
    }

    /** The largest code point WinAnsi can represent directly. */
    private static final int WINANSI_LIMIT = 0xFF;

    /**
     * Renders a string using only characters Helvetica can draw.
     *
     * <p>Anything with no sensible Latin equivalent becomes {@code ?} rather than vanishing. A
     * visible substitution tells a reader something was lost; a silent one does not.
     */
    public static String winAnsi(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        String decomposed = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(decomposed.length());

        for (int i = 0; i < decomposed.length(); i++) {
            char character = decomposed.charAt(i);

            String replacement = SUBSTITUTIONS.get(character);
            if (replacement != null) {
                out.append(replacement);
                continue;
            }
            if (character == '\n' || character == '\t' || character >= ' ' && character <= '~') {
                out.append(character);
                continue;
            }
            if (character <= WINANSI_LIMIT) {
                out.append(character);
                continue;
            }
            // Try to fold an accented character down to its base letter before giving up.
            String folded = java.text.Normalizer
                    .normalize(String.valueOf(character), java.text.Normalizer.Form.NFKD)
                    .replaceAll("\\p{M}+", "");
            out.append(folded.isEmpty() || folded.charAt(0) > WINANSI_LIMIT ? "?" : folded);
        }
        return out.toString();
    }
}
