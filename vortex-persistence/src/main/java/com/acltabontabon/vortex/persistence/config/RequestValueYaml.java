package com.acltabontabon.vortex.persistence.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.acltabontabon.vortex.core.data.BodyFieldPath;
import com.acltabontabon.vortex.core.data.DatasetRef;
import com.acltabontabon.vortex.core.data.DatasetScope;
import com.acltabontabon.vortex.core.data.DatasetValue;
import com.acltabontabon.vortex.core.data.EnvironmentValue;
import com.acltabontabon.vortex.core.data.FixedValue;
import com.acltabontabon.vortex.core.data.GeneratedValue;
import com.acltabontabon.vortex.core.data.Generator;
import com.acltabontabon.vortex.core.data.RequestValue;
import com.acltabontabon.vortex.core.data.ValueLifecycle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * How a request value is written in {@code vortex.yaml}, and read back.
 *
 * <h2>A scalar is still a value</h2>
 *
 * <p>Every configuration written before request data existed keeps working, unchanged and
 * unmigrated, because the shape it used is still the shape of the common case:
 *
 * <pre>{@code
 * headers:
 *   X-Tenant: "acme"                        # fixed, exactly as before
 *   Authorization: "${VORTEX_AUTH_TOKEN}"   # an environment reference, exactly as before
 * }</pre>
 *
 * <p>A scalar containing {@code ${NAME}} is an environment reference and everything else is a fixed
 * literal — which is precisely the rule Vortex already applied to header values. No {@code version:}
 * bump, no migration, and no file that has to be rewritten to gain a feature it does not use.
 *
 * <h2>A source is a mapping</h2>
 *
 * <pre>{@code
 * headers:
 *   x-idempotency-key: { generated: uuid }
 *   x-session:         { generated: random-string, lifecycle: per-vu, length: 16 }
 *   x-api-key:         { environment: PARTNER_API_KEY }
 * pathValues:
 *   id:                { dataset: customers, field: customerId }
 *   accountId:         { dataset: accounts, scope: portable, field: id }
 * bodyValues:
 *   customerId:        { dataset: customers, field: customerId }
 *   amount:            { generated: random-integer, minimum: 100, maximum: 5000 }
 *   productCode:       "CREDIT_CARD"
 * }</pre>
 *
 * <p>The discriminator is the key that names the source, so a reader sees what a value <em>is</em>
 * before they see how it is configured. {@code fixed:} exists for the one case a scalar cannot
 * express: a literal that genuinely contains {@code ${...}} and must not be resolved.
 *
 * <h2>What is not written here</h2>
 *
 * <p>No dataset section, and no record counts, field lists or content hashes. Those are facts about
 * a file, discovered by reading it, and a copy of them in a configuration file is accurate until
 * somebody edits the CSV. Which datasets a service uses is derived from the values that name one;
 * where each lives is its {@link DatasetScope}, written on the value itself so that resolving it
 * never depends on a precedence rule nobody documented.
 */
final class RequestValueYaml {

    private RequestValueYaml() {
    }

    // ---------------------------------------------------------------- parsing

    /** A map of request values, keyed by parameter or header name. */
    static Map<String, RequestValue> valueMap(JsonNode node, String field) {
        Map<String, RequestValue> values = new LinkedHashMap<>();
        if (node.isMissingNode() || node.isNull()) {
            return values;
        }
        if (!node.isObject()) {
            throw new ConfigText.ConfigProblem(field, "must be a mapping of names to values",
                    "for example:\n  " + field.substring(field.lastIndexOf('.') + 1)
                            + ":\n    X-Tenant: \"acme\"");
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            values.put(entry.getKey(),
                    value(entry.getValue(), field + "." + entry.getKey()));
        }
        return values;
    }

    /** Body field bindings, whose keys are dotted field paths rather than plain names. */
    static Map<BodyFieldPath, RequestValue> bodyValueMap(JsonNode node, String field) {
        Map<BodyFieldPath, RequestValue> values = new TreeMap<>();
        if (node.isMissingNode() || node.isNull()) {
            return values;
        }
        if (!node.isObject()) {
            throw new ConfigText.ConfigProblem(field, "must be a mapping of body fields to values",
                    "for example:\n  bodyValues:\n    customerId: "
                            + "{ dataset: customers, field: customerId }");
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            BodyFieldPath path;
            try {
                path = BodyFieldPath.parse(entry.getKey());
            } catch (IllegalArgumentException e) {
                throw new ConfigText.ConfigProblem(field + "." + entry.getKey(), e.getMessage(),
                        "body fields are addressed by name, and by dots for nested objects — "
                                + "customerId, or payment.card.token");
            }
            values.put(path, value(entry.getValue(), field + "." + entry.getKey()));
        }
        return values;
    }

    static RequestValue value(JsonNode node, String field) {
        if (node.isMissingNode() || node.isNull()) {
            return new FixedValue("");
        }
        if (node.isObject()) {
            return sourcedValue(node, field);
        }
        if (node.isArray()) {
            throw new ConfigText.ConfigProblem(field, "must be a single value, not a list",
                    "a request carries one value per field. To vary it across requests, use a "
                            + "dataset: { dataset: <name>, field: <column> }");
        }
        // Scalars, including numbers and booleans, which YAML will have typed for us. A request
        // value is text by the time it reaches the wire, so the rendering is what matters.
        return FixedValue.of(node.asText(""));
    }

    private static RequestValue sourcedValue(JsonNode node, String field) {
        if (node.has("generated")) {
            return generated(node, field);
        }
        if (node.has("dataset")) {
            return dataset(node, field);
        }
        if (node.has("environment")) {
            return environment(node, field);
        }
        if (node.has("fixed")) {
            return new FixedValue(node.path("fixed").asText(""));
        }
        throw new ConfigText.ConfigProblem(field,
                "does not say where its value comes from",
                "name one source: 'fixed', 'generated', 'dataset' or 'environment'. For example: "
                        + "{ generated: uuid }");
    }

    private static RequestValue generated(JsonNode node, String field) {
        Generator generator;
        try {
            generator = Generator.fromKey(node.path("generated").asText(""));
        } catch (IllegalArgumentException e) {
            throw new ConfigText.ConfigProblem(field, e.getMessage(),
                    "for example: { generated: uuid }");
        }
        ValueLifecycle lifecycle;
        try {
            lifecycle = ValueLifecycle.fromKey(node.path("lifecycle").asText(""));
        } catch (IllegalArgumentException e) {
            throw new ConfigText.ConfigProblem(field + ".lifecycle", e.getMessage(),
                    "omit it for a new value on every request");
        }
        try {
            return new GeneratedValue(generator, lifecycle,
                    node.path("minimum").asLong(1L),
                    node.path("maximum").asLong(1_000_000L),
                    node.path("length").asInt(12));
        } catch (IllegalArgumentException e) {
            throw new ConfigText.ConfigProblem(field, e.getMessage(), "");
        }
    }

    private static RequestValue dataset(JsonNode node, String field) {
        String name = node.path("dataset").asText("");
        String column = node.path("field").asText("");
        DatasetScope scope;
        try {
            scope = DatasetScope.fromKey(node.path("scope").asText(""));
        } catch (IllegalArgumentException e) {
            throw new ConfigText.ConfigProblem(field + ".scope", e.getMessage(),
                    "omit it for a dataset held on this machine only");
        }
        if (column.isBlank()) {
            throw new ConfigText.ConfigProblem(field,
                    "names a dataset but not which field of it to read",
                    "for example: { dataset: " + (name.isBlank() ? "customers" : name)
                            + ", field: customerId }");
        }
        try {
            return new DatasetValue(new DatasetRef(name, scope), column);
        } catch (IllegalArgumentException e) {
            throw new ConfigText.ConfigProblem(field, e.getMessage(), "");
        }
    }

    private static RequestValue environment(JsonNode node, String field) {
        try {
            return EnvironmentValue.named(node.path("environment").asText(""));
        } catch (IllegalArgumentException e) {
            throw new ConfigText.ConfigProblem(field, e.getMessage(),
                    "name the variable Vortex should read when it starts the load generator, "
                            + "for example: { environment: API_TOKEN }");
        }
    }

    // ---------------------------------------------------------------- rendering

    /**
     * One value, as it appears after the colon.
     *
     * <p>Flow mappings rather than nested blocks: a source is three short facts about one field, and
     * spreading it over four indented lines makes a list of ten headers unreadable.
     *
     * @param quote the store's own scalar quoting, so one file has one quoting rule
     */
    static String render(RequestValue value, java.util.function.UnaryOperator<String> quote) {
        return switch (value) {
            case FixedValue fixed -> fixed.literal().contains("${")
                    // A literal that looks like a reference has to say it is not one, or reading the
                    // file back would resolve it.
                    ? "{ fixed: " + quote.apply(fixed.literal()) + " }"
                    : quote.apply(fixed.literal());

            // The template as written. A scalar containing ${NAME} reads back as this same case,
            // so the round trip needs no discriminator.
            case EnvironmentValue environment -> quote.apply(environment.template());

            case GeneratedValue generated -> renderGenerated(generated);

            case DatasetValue dataset -> "{ dataset: " + dataset.datasetName()
                    + (dataset.dataset().scope() == DatasetScope.LOCAL
                            ? "" : ", scope: " + dataset.dataset().scope().key())
                    + ", field: " + dataset.field() + " }";
        };
    }

    private static String renderGenerated(GeneratedValue generated) {
        StringBuilder out = new StringBuilder("{ generated: ")
                .append(generated.generator().key());
        if (generated.lifecycle() != ValueLifecycle.defaultLifecycle()) {
            out.append(", lifecycle: ").append(generated.lifecycle().key());
        }
        if (generated.generator().usesRange()) {
            out.append(", minimum: ").append(generated.minimum())
                    .append(", maximum: ").append(generated.maximum());
        }
        if (generated.generator().usesLength()) {
            out.append(", length: ").append(generated.length());
        }
        return out.append(" }").toString();
    }
}
