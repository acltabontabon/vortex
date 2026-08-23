package dev.vortex.core.workload;

import java.util.Locale;

/**
 * Validation for workload names.
 *
 * <p>Names appear on the command line ({@code vortex run production-peak}), as configuration keys
 * and in artifact paths, so they are restricted to a conservative character set.
 */
final class WorkloadNames {

    static final int MAX_LENGTH = 40;

    private WorkloadNames() {
    }

    static String require(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("workload name must not be blank");
        }
        String trimmed = name.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "workload name must be at most " + MAX_LENGTH + " characters but was: " + name);
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '-' || c == '_')) {
                throw new IllegalArgumentException(
                        "workload name may only contain letters, digits, '-' and '_' but was: " + name);
            }
        }
        return trimmed;
    }
}
