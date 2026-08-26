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

    /** Either the one organization value to use, or why none could be chosen automatically. */
    public sealed interface Resolution permits Resolved, Failed {
    }

    public record Resolved(String organization) implements Resolution {
    }

    public record Failed(String detail) implements Resolution {
    }

    public static Resolution resolveOrganization(Map<String, Object> inputSchema) {
        Object propertiesValue = inputSchema.get("properties");
        if (!(propertiesValue instanceof Map<?, ?> properties)) {
            return new Failed("execute_dql's input schema has no 'properties' — cannot find 'organization'.");
        }
        Object organizationValue = properties.get("organization");
        if (!(organizationValue instanceof Map<?, ?> organizationSchema)) {
            return new Failed("execute_dql's input schema has no 'organization' property.");
        }
        Object enumValue = organizationSchema.get("enum");
        if (!(enumValue instanceof List<?> values) || values.isEmpty()) {
            return new Failed(
                    "execute_dql's 'organization' property has no enumerated values to choose from.");
        }
        if (values.size() > 1) {
            return new Failed("execute_dql's 'organization' property advertises " + values.size()
                    + " possible values (" + values + ") — Vortex cannot choose one automatically. "
                    + "Multi-organization Dynatrace accounts are not supported yet.");
        }
        return new Resolved(String.valueOf(values.get(0)));
    }
}
