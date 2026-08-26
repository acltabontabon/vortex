package com.acltabontabon.vortex.dynatrace.normalize;

import com.acltabontabon.vortex.core.metrics.TimeWindow;
import com.acltabontabon.vortex.dynatrace.DynatraceTelemetryResult;
import com.acltabontabon.vortex.dynatrace.query.DynatraceQueryDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns an MCP tool's raw JSON into evidence Vortex trusts, or rejects it with a reason.
 *
 * <p>Five stages, each able to end the pipeline: the result must be structured data (not prose), it
 * must contain numeric samples under the field this query expects, any unit the response volunteers
 * must agree with what the query expects, any timestamps it volunteers must overlap the requested
 * window, and any entity identifier it volunteers must match the one asked about. A stage that finds
 * nothing to check (no unit field, no timestamps, no entity echo) passes through rather than failing
 * closed — some tool responses legitimately answer with a bare number and nothing else.
 *
 * <p>Field matching is defensive by design: the exact JSON envelope an MCP {@code execute_dql} tool
 * wraps its rows in is not contractually documented the way Dynatrace's REST Metrics API v2 is, so
 * this walks the whole tree for a key matching one of {@link DynatraceQueryDefinition#valueFields()}
 * rather than assuming a fixed shape.
 */
public final class TelemetryNormalizer {

    public sealed interface Outcome permits Normalized, Rejected {
    }

    public record Normalized(NormalizedTelemetry telemetry) implements Outcome {
        public Normalized {
            Objects.requireNonNull(telemetry, "telemetry");
        }
    }

    public record Rejected(NormalizationFailure reason) implements Outcome {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    public Outcome normalize(DynatraceQueryDefinition definition, DynatraceTelemetryResult raw,
            TimeWindow requestedWindow, String requestedEntityId) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(requestedWindow, "requestedWindow");

        if (!raw.wasStructured()) {
            return new Rejected(new NormalizationFailure.SchemaInvalid(
                    "the tool returned text instead of structured data for " + definition.id()
                            + "; Vortex will not guess a number out of prose. What it returned: "
                            + snippet(raw.payload())));
        }

        List<Double> samples = new ArrayList<>();
        String unit = collect(raw.payload(), definition.valueFields(), samples);

        if (samples.isEmpty()) {
            return new Rejected(new NormalizationFailure.SchemaInvalid(
                    "no numeric value was found for '" + String.join("' or '", definition.valueFields())
                            + "' in the response to " + definition.id()));
        }

        if (!unit.isBlank() && !unitAgrees(definition.expectedUnit(), unit)) {
            return new Rejected(new NormalizationFailure.UnitUnrecognized(
                    "the response reported unit '" + unit + "' but " + definition.id()
                            + " expects " + definition.expectedUnit()));
        }

        TimeWindow reported = findWindow(raw.payload());
        if (reported != null && !overlaps(reported, requestedWindow)) {
            return new Rejected(new NormalizationFailure.WindowMismatch(
                    "the response covers " + reported.start() + " to " + reported.end()
                            + ", which does not overlap the requested window "
                            + requestedWindow.start() + " to " + requestedWindow.end()));
        }

        String reportedEntity = findEntity(raw.payload());
        if (requestedEntityId != null && !requestedEntityId.isBlank()
                && reportedEntity != null && !reportedEntity.isBlank()
                && !reportedEntity.equalsIgnoreCase(requestedEntityId)) {
            return new Rejected(new NormalizationFailure.EntityMismatch(
                    "the response is attributed to '" + reportedEntity + "' but Vortex asked about '"
                            + requestedEntityId + "'"));
        }

        boolean allZeroOrNegative = samples.stream().allMatch(v -> v == null || v <= 0);
        if (allZeroOrNegative) {
            return new Rejected(new NormalizationFailure.EmptyResult(
                    "every sample for " + definition.id() + " was zero or negative over the requested "
                            + "window — Dynatrace has no traffic to report, not a fetch failure"));
        }

        return new Normalized(new NormalizedTelemetry(definition.id(), List.copyOf(samples), unit));
    }

    /** A bounded preview of what an unstructured response actually said, so a person debugging this
     *  rejection sees what Dynatrace returned instead of guessing — never the full text, which could
     *  be arbitrarily long prose. */
    private static final int SNIPPET_LIMIT = 300;

    private String snippet(JsonNode payload) {
        String text = (payload.isTextual() ? payload.asText() : payload.toString()).strip();
        if (text.isEmpty()) {
            return "(empty response)";
        }
        return text.length() > SNIPPET_LIMIT ? text.substring(0, SNIPPET_LIMIT) + "…" : text;
    }

    // ------------------------------------------------------------------ walking

    private String collect(JsonNode node, List<String> fieldNames, List<Double> samples) {
        String[] unit = {""};
        walk(node, fieldNames, samples, unit);
        return unit[0];
    }

    private void walk(JsonNode node, List<String> fieldNames, List<Double> samples, String[] unitOut) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (fieldNames.contains(key)) {
                    addNumeric(value, samples);
                }
                if ("unit".equals(key) && value.isTextual() && unitOut[0].isBlank()) {
                    unitOut[0] = value.asText("");
                }
                walk(value, fieldNames, samples, unitOut);
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                walk(element, fieldNames, samples, unitOut);
            }
        }
    }

    private void addNumeric(JsonNode value, List<Double> samples) {
        if (value.isNumber()) {
            samples.add(value.asDouble());
        } else if (value.isArray()) {
            for (JsonNode element : value) {
                if (element.isNumber()) {
                    samples.add(element.asDouble());
                } else if (element.isTextual()) {
                    parseDouble(element.asText()).ifPresent(samples::add);
                }
            }
        } else if (value.isTextual()) {
            parseDouble(value.asText()).ifPresent(samples::add);
        }
    }

    private java.util.Optional<Double> parseDouble(String text) {
        try {
            return java.util.Optional.of(Double.parseDouble(text));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    private boolean unitAgrees(String expected, String reported) {
        String normalizedExpected = expected.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
        String normalizedReported = reported.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
        return normalizedExpected.contains(normalizedReported) || normalizedReported.contains(normalizedExpected);
    }

    private TimeWindow findWindow(JsonNode node) {
        Instant start = findInstant(node, "start", "from");
        Instant end = findInstant(node, "end", "to");
        if (start == null || end == null || end.isBefore(start)) {
            return null;
        }
        return new TimeWindow(start, end);
    }

    private Instant findInstant(JsonNode node, String... fieldNames) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                for (String name : fieldNames) {
                    if (name.equals(entry.getKey())) {
                        Instant parsed = parseInstant(entry.getValue());
                        if (parsed != null) {
                            return parsed;
                        }
                    }
                }
            }
            fields = node.fields();
            while (fields.hasNext()) {
                Instant found = findInstant(fields.next().getValue(), fieldNames);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                Instant found = findInstant(element, fieldNames);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Instant parseInstant(JsonNode value) {
        try {
            if (value.isTextual()) {
                return Instant.parse(value.asText());
            }
            if (value.isNumber()) {
                long raw = value.asLong();
                // Millisecond epoch values are ~13 digits today; second epoch values are ~10.
                return raw > 100_000_000_000L ? Instant.ofEpochMilli(raw) : Instant.ofEpochSecond(raw);
            }
        } catch (DateTimeParseException | ArithmeticException e) {
            return null;
        }
        return null;
    }

    private boolean overlaps(TimeWindow a, TimeWindow b) {
        return !a.end().isBefore(b.start()) && !b.end().isBefore(a.start());
    }

    private String findEntity(JsonNode node) {
        return findText(node, "dt.entity.service", "entity", "entityId", "serviceId");
    }

    private String findText(JsonNode node, String... fieldNames) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                for (String name : fieldNames) {
                    if (name.equals(entry.getKey()) && entry.getValue().isTextual()) {
                        return entry.getValue().asText();
                    }
                }
            }
            fields = node.fields();
            while (fields.hasNext()) {
                String found = findText(fields.next().getValue(), fieldNames);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) {
                String found = findText(element, fieldNames);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
