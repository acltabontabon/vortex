package com.acltabontabon.vortex.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Loads prompts from versioned resources.
 *
 * <p>Prompts live in {@code src/main/resources/ai/} rather than as string literals in Java. They are
 * long, they are edited far more often than the code around them, and they are reviewed by people
 * thinking about wording rather than about control flow — all of which is much easier when they are
 * plain text files in version control.
 *
 * <p>The version travels with every stored analysis, so an interpretation from six months ago can
 * be understood in terms of the prompt that produced it.
 */
public final class PromptLibrary {

    /**
     * Bumped whenever a prompt changes in a way that could alter the shape or substance of a
     * response. Recorded against every analysis.
     */
    /** Bumped whenever a prompt's shape or substance changes, so a stored analysis can always be
     * traced back to the exact prompt text that produced it. v3: findings carry a type, evidence
     * is required on recommendations and nextTest, and nextTest states what it would distinguish.
     * v4: rule prose condensed for local-model instruction budgets — same constraints, same JSON
     * schema, but each rule states the requirement without its explanatory justification.
     * v5: dropped product-name references and downstream/rhetorical padding from rule prose — same
     * constraints, same JSON schema. */
    public static final String VERSION = "v5";

    public static final String ANALYZE_EXECUTION = "analyze-execution";
    public static final String EXPLAIN_WORKLOAD = "explain-workload";
    public static final String COMPARE_EXECUTIONS = "compare-executions";

    private PromptLibrary() {
    }

    /**
     * Loads a prompt and fills in its placeholders.
     *
     * <p>Substitution is literal and one-pass: a value is never re-scanned for further placeholders.
     * That matters because some values contain text imported from a user's API description, and a
     * substitution pass that kept looking would let that text introduce placeholders of its own.
     */
    public static String render(String name, Map<String, String> values) {
        String template = load(name);
        StringBuilder rendered = new StringBuilder(template.length() + 512);

        int cursor = 0;
        while (cursor < template.length()) {
            int open = template.indexOf('{', cursor);
            if (open < 0) {
                rendered.append(template, cursor, template.length());
                break;
            }
            int close = template.indexOf('}', open);
            if (close < 0) {
                rendered.append(template, cursor, template.length());
                break;
            }
            String key = template.substring(open + 1, close);
            if (values.containsKey(key)) {
                rendered.append(template, cursor, open).append(values.get(key));
                cursor = close + 1;
            } else {
                // Not a placeholder — the prompts contain literal JSON examples, whose braces must
                // survive untouched.
                rendered.append(template, cursor, open + 1);
                cursor = open + 1;
            }
        }
        return rendered.toString();
    }

    static String load(String name) {
        String path = "/ai/" + name + ".st";
        try (InputStream in = PromptLibrary.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing prompt resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read prompt resource " + path, e);
        }
    }
}
