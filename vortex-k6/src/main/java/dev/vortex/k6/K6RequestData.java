package dev.vortex.k6;

import dev.vortex.core.data.BodyFieldPath;
import dev.vortex.core.data.DatasetValue;
import dev.vortex.core.data.EnvironmentValue;
import dev.vortex.core.data.FixedValue;
import dev.vortex.core.data.GeneratedValue;
import dev.vortex.core.data.Generator;
import dev.vortex.core.data.RequestValue;
import dev.vortex.core.data.ValueLifecycle;
import dev.vortex.core.environment.SecretReferences;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.PlannedDataset;
import dev.vortex.core.plan.PlannedOperation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Compiles a plan's request data into JavaScript expressions.
 *
 * <p>The user declared what a request needs; this decides how k6 supplies it. Everything k6-specific
 * about that decision lives here — {@code SharedArray}, {@code __ENV}, iteration counters — so that
 * nothing above this class has to know the engine exists, and so that the whole of "how Vortex talks
 * to k6 about data" is one file somebody can read.
 *
 * <h2>The simple case stays simple</h2>
 *
 * <p>An operation whose values are all fixed compiles to exactly the script it always did: a literal
 * URL, a literal body, no preamble, no helpers. Nothing here runs for it. That is not an
 * optimisation — the generated script is an artifact this product asks people to read and trust, and
 * making a request that sends three constants pay for the machinery of one that does not would be a
 * cost charged to the wrong user.
 *
 * <h2>Values are bound once, then used</h2>
 *
 * <p>Every non-fixed value becomes a {@code const} at the top of the operation's function. That is
 * what {@link ValueLifecycle#PER_OPERATION_EXECUTION} means, made visible: a reader can see that the
 * idempotency key in the header and the one in the body are the same key, because they are the same
 * constant. Per-VU values are bound at module scope instead, which is k6's init context and runs
 * once per virtual user.
 *
 * <h2>Injection safety</h2>
 *
 * <p>Names and literals are emitted through the generator's JSON serialiser, never concatenated.
 * Dataset values are read from a file at run time and so never appear in the script at all; where
 * one lands in a URL it is wrapped in {@code encodeURIComponent}, and where one lands in a body it is
 * bound as a JSON value rather than spliced into a string.
 */
final class K6RequestData {

    /** k6's per-scenario iteration counter: unique across every virtual user in the scenario. */
    private static final String ITERATION = "exec.scenario.iterationInTest";

    private final Function<Object, String> toJson;

    /**
     * Bindings, computed once per operation.
     *
     * <p>Three passes emit from them — module scope, the function preamble, and the points of use —
     * and all three must agree on every name. Recomputing would happen to agree, because the maps
     * iterate in a fixed order; caching means it cannot quietly stop agreeing.
     */
    private final Map<String, Bindings> bindingsByOperation = new LinkedHashMap<>();

    K6RequestData(Function<Object, String> toJson) {
        this.toJson = toJson;
    }

    // ------------------------------------------------------------------ module-level emission

    /** Whether anything in this plan needs k6's execution API — datasets or a sequence. */
    static boolean needsExecutionApi(EffectiveTestPlan plan) {
        if (!plan.datasets().isEmpty()) {
            return true;
        }
        return plan.operations().stream()
                .flatMap(operation -> operation.requestData().allValues().stream())
                .anyMatch(value -> value instanceof GeneratedValue generated
                        && generated.generator() == Generator.SEQUENCE);
    }

    static boolean needsSharedArray(EffectiveTestPlan plan) {
        return !plan.datasets().isEmpty();
    }

    /**
     * The datasets, bound at module scope.
     *
     * <p>{@code SharedArray} is k6's own answer to this: the file is parsed once for the whole test
     * rather than once per virtual user, and the rows are shared rather than copied. A thousand VUs
     * over a 5,000-row dataset is one copy, not a thousand.
     *
     * <p>Vortex stages a JSON copy beside the script, so the script needs no CSV parser and no
     * remote import whatever the source format was.
     */
    String datasets(EffectiveTestPlan plan) {
        if (plan.datasets().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (PlannedDataset dataset : plan.datasets()) {
            out.append("// ").append(dataset.name()).append(" — ").append(dataset.recordCount())
                    .append(" record").append(dataset.recordCount() == 1 ? "" : "s").append(", ")
                    .append(dataset.describeFields()).append('\n');
            out.append("const ").append(dataset.scriptBinding()).append(" = new SharedArray(")
                    .append(toJson.apply(dataset.name())).append(", () => JSON.parse(open(")
                    .append(toJson.apply("./" + dataset.stagedFile())).append(")));\n");
        }
        return out.append('\n').toString();
    }

    /** The generator helpers this plan actually uses, and only those. */
    String generatorHelpers(EffectiveTestPlan plan) {
        Set<Generator> used = new LinkedHashSet<>();
        for (PlannedOperation operation : plan.operations()) {
            for (RequestValue value : operation.requestData().allValues()) {
                if (value instanceof GeneratedValue generated) {
                    collect(generated.generator(), used);
                }
            }
        }
        if (used.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Generator generator : Generator.values()) {
            if (used.contains(generator)) {
                out.append(K6Generators.helperFor(generator));
            }
        }
        return out.isEmpty() ? "" : out.append('\n').toString();
    }

    private void collect(Generator generator, Set<Generator> used) {
        for (Generator dependency : K6Generators.dependenciesOf(generator)) {
            collect(dependency, used);
        }
        used.add(generator);
    }

    /**
     * Per-VU values, bound at module scope.
     *
     * <p>k6 runs a script's init context once per virtual user, so a module-level constant is
     * per-VU by construction rather than by a mechanism Vortex has to maintain.
     */
    String perVuValues(EffectiveTestPlan plan) {
        StringBuilder out = new StringBuilder();
        for (PlannedOperation operation : plan.operations()) {
            Bindings bindings = bindingsFor(operation);
            for (Binding binding : bindings.all()) {
                if (binding.perVu()) {
                    out.append("const ").append(binding.name()).append(" = ")
                            .append(binding.expression()).append(";\n");
                }
            }
        }
        return out.isEmpty() ? "" : out.append('\n').toString();
    }

    // ------------------------------------------------------------------ per-operation emission

    /**
     * The value bindings an operation's function opens with: its dataset row, then its values.
     *
     * <p>The row first, and once: every value this execution reads from a given dataset comes from
     * the same row, so a customer id and the mobile number beside it belong to the same customer.
     */
    String preamble(PlannedOperation operation, EffectiveTestPlan plan) {
        if (!operation.hasDynamicRequestData()) {
            return "";
        }
        StringBuilder out = new StringBuilder();

        for (PlannedDataset dataset : datasetsUsedBy(operation, plan)) {
            String binding = dataset.scriptBinding();
            out.append("  const ").append(rowBinding(dataset)).append(" = ").append(binding)
                    .append('[').append(ITERATION).append(" % ").append(binding)
                    .append(".length];\n");
        }
        for (Binding binding : bindingsFor(operation).all()) {
            if (!binding.perVu()) {
                out.append("  const ").append(binding.name()).append(" = ")
                        .append(binding.expression()).append(";\n");
            }
        }
        return out.toString();
    }

    /**
     * The datasets this operation reads, resolved against the plan.
     *
     * <p>Throws rather than skipping. Preflight rejects an unresolvable dataset long before this
     * runs, so reaching here means something upstream is wrong — and quietly omitting the row
     * binding would produce a script that references an undefined variable on its first request,
     * which is a much worse way to find out.
     */
    private List<PlannedDataset> datasetsUsedBy(PlannedOperation operation, EffectiveTestPlan plan) {
        List<PlannedDataset> used = new ArrayList<>();
        for (var ref : operation.referencedDatasets()) {
            used.add(plan.dataset(ref).orElseThrow(() -> new IllegalStateException(
                    "operation " + operation.name() + " reads dataset '" + ref.name()
                            + "' (" + ref.scope().key() + "), which this plan does not carry. A "
                            + "dataset is resolved and staged when the plan is built; a plan missing "
                            + "one cannot be executed.")));
        }
        return used;
    }

    private static String rowBinding(PlannedDataset dataset) {
        return "row_" + dataset.scriptBinding().substring("dataset_".length());
    }

    // ------------------------------------------------------------------ value expressions

    /**
     * One value as a JavaScript expression, in the context of an operation's bindings.
     *
     * <p>A fixed value is a literal; everything else is the constant the preamble bound it to.
     */
    String expressionFor(PlannedOperation operation, RequestValue value, String key) {
        if (value instanceof FixedValue fixed) {
            return toJson.apply(fixed.literal());
        }
        return bindingsFor(operation).nameOf(key, value);
    }

    /** A record of every non-fixed value in one operation, named and expressed. */
    private Bindings bindingsFor(PlannedOperation operation) {
        return bindingsByOperation.computeIfAbsent(operation.k6ScenarioKey(), key -> {
            Bindings bindings = new Bindings();
            operation.pathValues().forEach((name, value) ->
                    bindings.add("path", name, value, operation));
            operation.queryValues().forEach((name, value) ->
                    bindings.add("query", name, value, operation));
            operation.headers().forEach((name, value) ->
                    bindings.add("header", name, value, operation));
            operation.bodyValues().forEach((path, value) ->
                    bindings.add("body", path.asText(), value, operation));
            return bindings;
        });
    }

    private String rawExpression(RequestValue value, PlannedOperation operation) {
        return switch (value) {
            case FixedValue fixed -> toJson.apply(fixed.literal());

            case EnvironmentValue environment -> environmentExpression(environment);

            case GeneratedValue generated -> switch (generated.generator()) {
                case UUID -> "vortexUuid()";
                case TIMESTAMP -> "vortexTimestamp()";
                case DATE -> "vortexDate()";
                case RANDOM_INTEGER -> "vortexInt(" + generated.minimum() + ", "
                        + generated.maximum() + ")";
                case RANDOM_STRING -> "vortexString(" + generated.length() + ")";
                case EMAIL -> "vortexEmail()";
                case PHONE -> "vortexPhone()";
                case SEQUENCE -> ITERATION + " + 1";
            };

            case DatasetValue dataset -> datasetExpression(dataset);
        };
    }

    private String datasetExpression(DatasetValue value) {
        String row = "row_" + value.datasetName().replaceAll("[^A-Za-z0-9_]", "_");
        return row + "[" + toJson.apply(value.field()) + "]";
    }

    /**
     * An environment reference, as a lookup rather than a value.
     *
     * <p>{@code Bearer ${TOKEN}} becomes {@code 'Bearer ' + (__ENV.TOKEN || '') + ''}. The
     * substitution is done on the escaped JSON literal so that the surrounding text — which came
     * from configuration — is still a string literal and not code. The {@code || ''} is deliberate:
     * an unset variable produces an empty string and a clean 401, not the text {@code undefined}
     * arriving at somebody's authorisation header.
     */
    private String environmentExpression(EnvironmentValue value) {
        String expression = toJson.apply(value.template());
        for (String name : SecretReferences.referencedNames(value.template())) {
            expression = expression.replace("${" + name + "}",
                    "' + (__ENV." + name + " || '') + '");
        }
        return expression.replace("\"", "'");
    }

    // ------------------------------------------------------------------ bindings

    /** One named constant in the generated script. */
    private record Binding(String name, String expression, boolean perVu) {
    }

    /**
     * The constants for one operation, named for where they are carried.
     *
     * <p>Named rather than numbered because this file is meant to be read: {@code v_header_x_request_id}
     * says what it is at the point of use, and {@code v3} does not. Collisions — two positions whose
     * names sanitise to the same identifier — get a numeric suffix rather than silently sharing a
     * value.
     */
    private final class Bindings {

        private final Map<String, Binding> byKey = new LinkedHashMap<>();
        private final Set<String> usedNames = new LinkedHashSet<>();

        void add(String target, String name, RequestValue value, PlannedOperation operation) {
            if (!value.isDynamic()) {
                return;
            }
            String key = target + ":" + name;
            String identifier = uniqueName("v_" + target + "_" + sanitise(name));
            boolean perVu = value instanceof GeneratedValue generated
                    && generated.lifecycle() == ValueLifecycle.PER_VU;
            if (perVu) {
                // Distinguished across operations, because module scope is shared and two operations
                // may both bind a per-VU value called `token`.
                identifier = uniqueName("vu_" + sanitise(operation.k6ScenarioKey()) + "_"
                        + target + "_" + sanitise(name));
            }
            byKey.put(key, new Binding(identifier, rawExpression(value, operation), perVu));
        }

        String nameOf(String key, RequestValue value) {
            Binding binding = byKey.get(key);
            if (binding == null) {
                throw new IllegalStateException(
                        "no binding was emitted for " + key + ". Every non-fixed request value must "
                                + "be bound before it is used, or the script would evaluate it twice "
                                + "and send two different values for one field.");
            }
            return binding.name();
        }

        List<Binding> all() {
            return List.copyOf(byKey.values());
        }

        private String uniqueName(String candidate) {
            String base = candidate.replaceAll("_+", "_");
            String name = base;
            int suffix = 2;
            while (!usedNames.add(name)) {
                name = base + "_" + suffix++;
            }
            return name;
        }
    }

    private static String sanitise(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9]", "_").toLowerCase(Locale.ROOT);
        return cleaned.isBlank() ? "value" : cleaned;
    }
}
