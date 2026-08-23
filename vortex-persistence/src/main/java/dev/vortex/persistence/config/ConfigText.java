package dev.vortex.persistence.config;

import dev.vortex.core.shared.ErrorRate;
import dev.vortex.core.threshold.Durations;
import java.time.Duration;
import java.util.Locale;

/**
 * Reads the small, human-friendly value formats {@code vortex.yaml} accepts.
 *
 * <p>Configuration is written by people, so it takes {@code 10m}, {@code 500ms} and {@code 1%}
 * rather than millisecond counts and fractions. Every failure here produces a message naming the
 * field, what was found and what was expected, because a validation error that says only
 * "invalid duration" makes the user hunt for the problem Vortex has already located.
 */
public final class ConfigText {

    private ConfigText() {
    }

    /**
     * Parses a duration such as {@code 500ms}, {@code 30s}, {@code 10m} or {@code 1h30m}.
     *
     * @throws ConfigProblem when the text is not a duration Vortex understands
     */
    public static Duration duration(String field, String raw) {
        try {
            return Durations.parse(raw);
        } catch (IllegalArgumentException e) {
            // The grammar lives in the domain; the field-scoped phrasing lives here, because only
            // the configuration reader knows which field the user was editing.
            throw new ConfigProblem(field, e.getMessage(), "for example 10m, 30s, 500ms or 30d");
        }
    }

    /**
     * Parses an error-rate limit, accepting either a percentage ({@code 1%}) or a fraction
     * ({@code 0.01}).
     *
     * <p>Both forms appear in the wild and the ambiguity is genuinely dangerous: read {@code 1} as a
     * fraction and a 1% objective silently becomes "100% errors are acceptable". Vortex therefore
     * requires the percent sign when a percentage is meant, and treats a bare number above 1 as an
     * error rather than guessing.
     */
    public static ErrorRate errorRate(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ConfigProblem(field, "must be set", "for example 1% or 0.01");
        }
        String text = raw.trim();
        try {
            if (text.endsWith("%")) {
                double percent = Double.parseDouble(text.substring(0, text.length() - 1).trim());
                if (percent < 0 || percent > 100) {
                    throw new ConfigProblem(field, "must be between 0% and 100% but was " + text, "");
                }
                return ErrorRate.ofPercent(percent);
            }
            double fraction = Double.parseDouble(text);
            if (fraction > 1) {
                throw new ConfigProblem(field,
                        "was " + text + ", which Vortex will not guess at",
                        "write 1% for one percent, or 0.01 for the same value as a fraction. A bare "
                                + "number above 1 is rejected because reading it as a fraction would "
                                + "silently accept every request failing.");
            }
            if (fraction < 0) {
                throw new ConfigProblem(field, "must not be negative but was " + text, "");
            }
            return ErrorRate.ofFraction(fraction);
        } catch (NumberFormatException e) {
            throw new ConfigProblem(field, "is not a number: '" + raw + "'",
                    "for example 1% or 0.01");
        }
    }

    /** Parses a positive rate in requests per second. */
    public static double rate(String field, Object raw) {
        if (raw == null) {
            throw new ConfigProblem(field, "must be set",
                    "the number of requests per second, for example 120");
        }
        double value;
        try {
            value = raw instanceof Number number ? number.doubleValue()
                    : Double.parseDouble(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new ConfigProblem(field, "is not a number: '" + raw + "'",
                    "the number of requests per second, for example 120");
        }
        if (value <= 0) {
            throw new ConfigProblem(field, "must be greater than 0 but was " + raw,
                    "a workload that generates no traffic cannot tell you anything");
        }
        return value;
    }

    /** A validation failure phrased so the user can act on it. */
    public static class ConfigProblem extends RuntimeException {

        private final String field;

        public ConfigProblem(String field, String problem, String guidance) {
            super(field + " " + problem + (guidance == null || guidance.isBlank() ? "" : " — " + guidance));
            this.field = field;
        }

        public String field() {
            return field;
        }
    }
}
