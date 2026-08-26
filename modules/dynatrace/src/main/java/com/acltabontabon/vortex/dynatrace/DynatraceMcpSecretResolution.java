package com.acltabontabon.vortex.dynatrace;

import com.acltabontabon.vortex.core.environment.SecretReferences;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves {@code ${NAME}} header references from the environment, at the moment a connection is
 * opened and nowhere earlier.
 *
 * <p>Small and deliberately duplicated rather than shared with {@code app}'s {@code ObservationHttp}
 * (which does the identical thing for the REST Dynatrace adapter): that class is package-private
 * inside {@code app}'s adapter package, and promoting it into {@code vortex-core} so both modules
 * could see it would put HTTP/IO concerns into the one module required to stay framework-free. Twenty
 * lines duplicated once is cheaper than that boundary.
 */
final class DynatraceMcpSecretResolution {

    private DynatraceMcpSecretResolution() {
    }

    /** Every header with its {@code ${NAME}} references resolved from the environment. */
    static Map<String, String> resolve(Map<String, String> headers) {
        Map<String, String> resolved = new LinkedHashMap<>();
        headers.forEach((name, value) -> resolved.put(name, resolveValue(value)));
        return resolved;
    }

    private static String resolveValue(String value) {
        String resolved = value;
        for (String name : SecretReferences.referencedNames(value)) {
            String fromEnvironment = System.getenv(name);
            if (fromEnvironment != null) {
                resolved = resolved.replace("${" + name + "}", fromEnvironment);
            }
        }
        return resolved;
    }

    /** The first referenced environment variable that is not set, or null if all are. */
    static String missingSecret(Map<String, String> headers) {
        for (String value : headers.values()) {
            for (String name : SecretReferences.referencedNames(value)) {
                if (System.getenv(name) == null) {
                    return name;
                }
            }
        }
        return null;
    }
}
