package com.acltabontabon.vortex.dynatrace.query;

import java.util.List;
import java.util.Map;

/**
 * Reads the account-specific {@code organization} value out of the real {@code execute_dql} tool's
 * JSON-schema {@code inputSchema}, instead of guessing it.
 *
 * <p>Dynatrace's MCP server advertises {@code organization} as an enum of the account(s) the caller's
 * credential can query. A single-account setup enumerates exactly one value, which this class picks
 * automatically; zero or several values are refused rather than guessed, since silently querying the
 * wrong tenant would attribute one organization's traffic to another.
 */
public final class DqlToolSchema {

    private DqlToolSchema() {
    }

    /** Either the one organization value to use, the several it could be (pick one under Settings),
     *  or why none could be chosen at all. */
    public sealed interface Resolution permits Resolved, Ambiguous, Failed {
    }

    public record Resolved(String organization) implements Resolution {
    }

    /** More than one organization is advertised and none — or an out-of-date one — is configured. */
    public record Ambiguous(List<String> options) implements Resolution {
        public Ambiguous {
            options = List.copyOf(options);
        }
    }

    public record Failed(String detail) implements Resolution {
    }

    /**
     * @param configuredOrganization the value saved under Settings → Dynatrace → Organization, or
     *                                blank if none has been picked yet
     */
    public static Resolution resolveOrganization(Map<String, Object> inputSchema, String configuredOrganization) {
        Object propertiesValue = inputSchema.get("properties");
        if (!(propertiesValue instanceof Map<?, ?> properties)) {
            return new Failed("execute_dql's input schema has no 'properties' — cannot find 'organization'.");
        }
        Object organizationValue = properties.get("organization");
        if (!(organizationValue instanceof Map<?, ?> organizationSchema)) {
            return new Failed("execute_dql's input schema has no 'organization' property.");
        }
        Object enumValue = organizationSchema.get("enum");
        if (!(enumValue instanceof List<?> rawValues) || rawValues.isEmpty()) {
            return new Failed(
                    "execute_dql's 'organization' property has no enumerated values to choose from.");
        }
        List<String> values = rawValues.stream().map(String::valueOf).toList();
        if (values.size() == 1) {
            return new Resolved(values.get(0));
        }
        String configured = configuredOrganization == null ? "" : configuredOrganization.trim();
        if (configured.isEmpty()) {
            return new Ambiguous(values);
        }
        if (values.contains(configured)) {
            return new Resolved(configured);
        }
        return new Failed("the configured organization '" + configured + "' is not one of the "
                + values.size() + " this account currently has access to (" + values + "). "
                + "Pick again under Settings → Dynatrace → Organization.");
    }
}
