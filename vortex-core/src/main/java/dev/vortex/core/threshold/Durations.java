package dev.vortex.core.threshold;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Human-readable duration rendering shared by threshold descriptions and reports. */
public final class Durations {

    private static final Pattern DURATION_PART = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|s|m|h|d)");

    private Durations() {
    }

    /**
     * Parses the durations people write: {@code 500ms}, {@code 30s}, {@code 10m}, {@code 1h30m},
     * {@code 30d}.
     *
     * <p>Lives here, in the domain, because two places now need it — the configuration reader and
     * the command line — and a format with two parsers is a format that will eventually be accepted
     * by one and rejected by the other. Callers that need a field-scoped error message wrap the
     * exception rather than reimplementing the grammar.
     *
     * @throws IllegalArgumentException when the text is not a duration, or is not positive
     */
    public static Duration parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "must be set — for example 10m, 30s, 500ms or 30d");
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);

        Matcher matcher = DURATION_PART.matcher(text);
        long millis = 0;
        int matchedTo = 0;
        boolean matchedAnything = false;

        while (matcher.find()) {
            if (matcher.start() != matchedTo) {
                break;
            }
            matchedAnything = true;
            matchedTo = matcher.end();
            double amount = Double.parseDouble(matcher.group(1));
            millis += switch (matcher.group(2)) {
                case "ms" -> Math.round(amount);
                case "s" -> Math.round(amount * 1000);
                case "m" -> Math.round(amount * 60_000);
                case "h" -> Math.round(amount * 3_600_000);
                case "d" -> Math.round(amount * 86_400_000);
                default -> 0;
            };
        }

        if (!matchedAnything || matchedTo != text.length()) {
            throw new IllegalArgumentException(
                    "is not a duration Vortex understands: '" + raw + "'. Use a number followed by "
                            + "ms, s, m, h or d — for example 500ms, 30s, 10m, 1h30m or 30d");
        }
        if (millis <= 0) {
            throw new IllegalArgumentException(
                    "must be greater than zero but was '" + raw + "'");
        }
        return Duration.ofMillis(millis);
    }

    /** {@code 500 ms}, {@code 1.5 s}, {@code 10m}, {@code 1h 30m}, {@code 30d}. */
    public static String display(Duration duration) {
        long millis = duration.toMillis();
        if (millis < 1000) {
            return millis + " ms";
        }
        if (millis < 60_000) {
            double seconds = millis / 1000.0;
            return (seconds == Math.rint(seconds) ? String.valueOf((long) seconds) : String.valueOf(seconds)) + " s";
        }
        // Whole days as days. Only observation windows reach this length, and rendering "30d" as
        // "720h" made the configuration file disagree with what the person typed into it — a
        // round trip that changes the units is a round trip somebody has to double-check.
        if (duration.toHoursPart() == 0 && duration.toMinutesPart() == 0
                && duration.toSecondsPart() == 0 && duration.toDays() > 0) {
            return duration.toDays() + "d";
        }
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        StringBuilder text = new StringBuilder();
        if (hours > 0) {
            text.append(hours).append("h ");
        }
        if (minutes > 0) {
            text.append(minutes).append("m ");
        }
        if (seconds > 0 && hours == 0) {
            text.append(seconds).append("s");
        }
        return text.toString().trim();
    }

    /**
     * Compact machine form used in configuration files and engine options: {@code 500ms},
     * {@code 10m}, {@code 1h30m}, {@code 1s500ms}.
     *
     * <p>Millisecond precision is preserved deliberately. Latency objectives are routinely
     * sub-second — {@code p95 below 500ms} is the single most common threshold anyone writes — and
     * rendering that as {@code 0s} would silently destroy the objective the moment configuration
     * was saved. Every unit that can appear in a Vortex duration must survive the round trip.
     */
    public static String compact(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return "0s";
        }

        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        long millis = duration.toMillisPart();

        StringBuilder text = new StringBuilder();
        if (hours > 0) {
            text.append(hours).append('h');
        }
        if (minutes > 0) {
            text.append(minutes).append('m');
        }
        if (seconds > 0) {
            text.append(seconds).append('s');
        }
        if (millis > 0) {
            text.append(millis).append("ms");
        }
        return text.isEmpty() ? "0s" : text.toString();
    }
}
