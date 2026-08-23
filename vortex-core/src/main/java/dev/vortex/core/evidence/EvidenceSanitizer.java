package dev.vortex.core.evidence;

import dev.vortex.core.environment.SecretReferences;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Removes anything that must not leave the machine, before evidence reaches a renderer.
 *
 * <p>Placed in the domain rather than in an exporter, and called from one place on the way out of
 * assembly. The reason is deliberate: an exporter written a year from now must not be able to opt
 * out, and the HTML report and the PDF must not be able to disagree about what is safe to print.
 *
 * <p>This is belt and braces rather than the only line of defence. An {@code EffectiveTestPlan}
 * already holds secret <em>references</em> — {@code ${VORTEX_AUTH_TOKEN}} — and never resolved
 * values, because the engine resolves them at process launch and nowhere else. What this class adds
 * is that the property is <em>checked</em> on the export path instead of merely inherited, so a
 * future change that starts putting a real value into a plan is caught at the boundary where it
 * would otherwise have been published.
 *
 * <p>A reference is preserved exactly as written. {@code Authorization: ${VORTEX_AUTH_TOKEN}} is
 * information a reader needs in order to reproduce a run; masking it would lose that for nothing.
 */
public final class EvidenceSanitizer {

    /**
     * Header names whose value is always masked, whatever it looks like.
     *
     * <p>Matched on a prefix basis, so {@code X-Api-Key-V2} is covered by {@code x-api-key}.
     */
    private static final List<String> SENSITIVE_HEADERS = List.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "api-key", "apikey", "x-auth-token", "auth-token",
            "x-access-token", "access-token", "x-secret", "secret", "password", "token");

    /**
     * Shapes that are credentials wherever they appear, including in free prose.
     *
     * <p>Kept narrow on purpose. A pattern broad enough to catch every possible secret also catches
     * workload names and error messages, and a report with its findings redacted into uselessness
     * is not safer — it just stops being read.
     */
    private static final List<Pattern> CREDENTIAL_SHAPES = List.of(
            // JSON Web Token: three base64url segments separated by dots.
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\b"),
            // Vendor-prefixed keys: sk-…, ghp_…, xoxb-…, AKIA…, and the like.
            Pattern.compile("\\b(?:sk|pk|rk)-[A-Za-z0-9]{16,}\\b"),
            Pattern.compile("\\b(?:gh[pousr]|github_pat)_[A-Za-z0-9_]{16,}\\b"),
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            // Bearer credentials written out in prose or a log line.
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/=-]{16,}"));

    /** Credentials embedded in a URL's userinfo, e.g. {@code https://user:pass@host}. */
    private static final Pattern URL_USERINFO =
            Pattern.compile("(?<=://)[^/@\\s]+:[^/@\\s]+@");

    /** Engine options that are safe to publish. Anything else is dropped rather than masked. */
    private static final List<String> PUBLISHABLE_OPTION_PREFIXES = List.of(
            "discardresponsebodies", "insecureskiptlsverify", "nocookiesreset",
            "noconnectionreuse", "novusconnectionreuse", "batch", "batchperhost",
            "rps", "maxredirects", "usermagent", "useragent", "throw", "summarytrendstats",
            "summarytimeunit", "dns", "tags", "systemtags", "blockhostnames", "hosts",
            "miniterationduration", "setuptimeout", "teardowntimeout", "linger");

    /** Free text: masks anything credential-shaped and collapses control characters. */
    public String text(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String sanitised = value;
        for (Pattern shape : CREDENTIAL_SHAPES) {
            sanitised = shape.matcher(sanitised).replaceAll(SecretReferences.MASK);
        }
        return control(sanitised);
    }

    public List<String> lines(List<String> values) {
        return values == null ? List.of() : values.stream().map(this::text).toList();
    }

    /**
     * A URL with any embedded credentials removed.
     *
     * <p>The host and path are kept. Which service was under test is exactly the sort of thing a
     * report exists to record, and it is not a secret.
     */
    public String url(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return text(URL_USERINFO.matcher(value).replaceAll(""));
    }

    /**
     * Request headers, with every value that is not a plain reference masked.
     *
     * <p>Names are kept in every case. That a run sent an {@code Authorization} header is part of
     * how it was carried out; what the header contained is not.
     */
    public Map<String, String> headers(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sanitised = new LinkedHashMap<>();
        headers.forEach((name, value) -> sanitised.put(name, headerValue(name, value)));
        return Map.copyOf(sanitised);
    }

    private String headerValue(String name, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        // Deliberately delegates rather than deciding for itself. SecretReferences shows a *pure*
        // reference as written and masks anything else — so "${TOKEN}" survives but
        // "Bearer ${TOKEN}" does not, because the prefix means the value is no longer only a
        // reference. That is the conservative reading, it is the rule the rest of the product
        // already applies, and an export is the last place to start applying a laxer one.
        if (SecretReferences.containsReference(value)) {
            return SecretReferences.mask(value);
        }
        if (isSensitiveName(name)) {
            return SecretReferences.MASK;
        }
        return text(value);
    }

    /**
     * The environment variables a run's headers depend on, in the order they appear.
     *
     * <p>Masking a header value costs a reader the one thing they need in order to run the test
     * again: which variable to set. Naming the variables separately restores that without printing
     * anything that could be a credential — a name is not a secret, and a report that says
     * "set VORTEX_AUTH_TOKEN" is far more useful than one showing eight bullets and no explanation.
     */
    public List<String> secretReferences(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return List.of();
        }
        return headers.values().stream()
                .flatMap(value -> SecretReferences.referencedNames(value).stream())
                .distinct()
                .toList();
    }

    private boolean isSensitiveName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return SENSITIVE_HEADERS.stream().anyMatch(lower::contains);
    }

    /**
     * Engine options, reduced to those known to be safe to publish.
     *
     * <p>An allowlist rather than a denylist. These are arbitrary user-supplied keys, and a denylist
     * over an open set is a guess about what people will type next.
     */
    public Map<String, String> options(Map<String, String> options) {
        if (options == null || options.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sanitised = new LinkedHashMap<>();
        options.forEach((key, value) -> {
            String normalised = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
            if (PUBLISHABLE_OPTION_PREFIXES.contains(normalised)) {
                sanitised.put(key, text(value));
            } else {
                sanitised.put(key, SecretReferences.MASK);
            }
        });
        return Map.copyOf(sanitised);
    }

    /** Strips control characters, which corrupt a PDF content stream and confuse a terminal. */
    private String control(String value) {
        StringBuilder cleaned = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\t' || !Character.isISOControl(codePoint)) {
                cleaned.appendCodePoint(codePoint);
            } else {
                cleaned.append(' ');
            }
        });
        return cleaned.toString();
    }
}
