package com.acltabontabon.vortex.core.environment;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises and masks {@code ${ENV_VAR}} secret references.
 *
 * <p>Vortex's rule is that a resolved secret exists only inside the load generator's process
 * environment, for as long as that process runs. Everything Vortex persists or displays — the
 * effective plan, artifacts, reports, logs, AI prompts — carries the reference instead.
 */
public final class SecretReferences {

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");

    /** What a value that looks like a secret is replaced with when it must be displayed. */
    public static final String MASK = "••••••••";

    private SecretReferences() {
    }

    public static boolean containsReference(String value) {
        return value != null && REFERENCE.matcher(value).find();
    }

    /** The environment variable names a value depends on. */
    public static Set<String> referencedNames(String value) {
        Set<String> names = new LinkedHashSet<>();
        if (value == null) {
            return names;
        }
        Matcher matcher = REFERENCE.matcher(value);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * Renders a value for display.
     *
     * <p>A pure reference such as {@code ${TOKEN}} is shown as-is: it reveals nothing and helps the
     * user understand their own configuration. A value that is <em>not</em> a reference but sits in
     * a position where secrets are expected is masked, because it may be a literal credential that
     * should never have been written down.
     */
    public static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (REFERENCE.matcher(value).replaceAll("").isBlank()) {
            return value;
        }
        return MASK;
    }
}
