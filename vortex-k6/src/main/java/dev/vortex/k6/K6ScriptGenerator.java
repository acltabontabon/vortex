package dev.vortex.k6;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vortex.core.data.FixedValue;
import dev.vortex.core.data.RequestValue;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.PlannedOperation;
import dev.vortex.core.shared.Concurrency;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.shared.Weight;
import dev.vortex.core.threshold.Durations;
import dev.vortex.core.threshold.ErrorRateThreshold;
import dev.vortex.core.threshold.LatencyThreshold;
import dev.vortex.core.threshold.Threshold;
import dev.vortex.core.workload.OperationMix;
import dev.vortex.core.workload.RateAllocation;
import dev.vortex.core.workload.RateAllocator;
import dev.vortex.core.workload.Stage;
import dev.vortex.core.workload.WeightedOperation;
import dev.vortex.core.workload.WorkloadModel;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates a resolved Vortex plan into a k6 script.
 *
 * <h2>One Vortex workload, several k6 scenarios</h2>
 * A Vortex workload is a performance experiment; a k6 scenario is a scheduling construct. They are
 * not the same thing and this generator does not pretend otherwise: one Vortex workload becomes one
 * k6 scenario <em>per operation</em>, because that is the cleanest way to drive a mix at a divided
 * total. The rates are shares of a single total, never the total repeated, so a 60/30/10 mix at
 * 100 requests/sec generates 100 requests/sec — not 300.
 *
 * <h2>Nothing Vortex-specific in the output</h2>
 * The generated script declares no custom metrics and adds no custom tags. It does not need to.
 * Because each k6 scenario drives exactly one operation, k6's own built-in {@code scenario} tag
 * already identifies the operation on every sample, and it is enabled by default. Per-operation
 * objectives become k6 submetric thresholds; response expectations become {@code check()} calls.
 *
 * <p>That is a deliberate constraint, not an omission. An engineer who has never heard of Vortex can
 * read this file, run it with a bare {@code k6 run}, and get the same measurements — which is the
 * whole basis for claiming Vortex complements k6 rather than wrapping it. The cost is that the
 * one-workload-per-operation mapping becomes load-bearing, so the plan records which key belongs to
 * which operation rather than leaving anyone to infer it from the string.
 *
 * <h2>Injection safety</h2>
 * Every value taken from user configuration — URLs, paths, headers, payloads, operation names — is
 * emitted through JSON serialisation rather than string concatenation. An operation path containing
 * a quote or a newline becomes an escaped string literal instead of executable JavaScript.
 */
public final class K6ScriptGenerator {

    /**
     * Bumped when the generated script's shape changes in a way that affects results.
     *
     * <p>{@code 3} added request data: datasets, generated values and environment lookups. An
     * operation whose values are all fixed still compiles to byte-identical output, so the version
     * marks what the generator <em>can</em> emit rather than what every script now looks like.
     */
    public static final String TEMPLATE_VERSION = "3";

    /** The summary file the generated script writes through {@code handleSummary}. */
    public static final String SUMMARY_FILE = "k6-summary.json";

    /**
     * The tag measurements are attributed through.
     *
     * <p>k6's own system tag, not one Vortex invents. Every sample k6 emits already carries the name
     * of the workload that produced it, and Vortex arranges for one workload per operation, so the
     * attribution is free and works identically for {@code k6 run} and for Vortex.
     *
     * <p>The mapping from key back to operation lives on the plan
     * ({@code EffectiveTestPlan.operationsByScenarioKey()}) and is never reconstructed from the tag
     * string — see {@code dev.vortex.core.plan.OperationKeys}.
     */
    public static final String OPERATION_TAG = "scenario";

    private final ObjectMapper json = new ObjectMapper();
    private final RateAllocator rateAllocator = new RateAllocator();

    public String generate(EffectiveTestPlan plan) {
        // Fresh per script: it names the constants it emits, and those names must be stable within
        // one script and are irrelevant between two.
        K6RequestData requestData = new K6RequestData(this::toJson);

        StringBuilder script = new StringBuilder();

        script.append(header(plan));
        script.append(imports(plan));
        script.append(options(plan));
        script.append(helpers(plan));
        script.append(requestData.datasets(plan));
        script.append(requestData.generatorHelpers(plan));
        script.append(bodyFieldHelper(plan));
        script.append(bodyDocuments(plan));
        script.append(requestData.perVuValues(plan));
        script.append(responseCallbacks(plan));

        for (PlannedOperation operation : plan.operations()) {
            script.append(operationFunction(operation, plan, requestData));
        }

        script.append(summaryHandler());
        return script.toString();
    }

    private String header(EffectiveTestPlan plan) {
        StringBuilder mapping = new StringBuilder();
        for (PlannedOperation operation : plan.operations()) {
            mapping.append("//   ").append(operation.k6ScenarioKey()).append("  ->  ")
                    .append(operation.name())
                    .append(operation.arrivalRateIfPresent()
                            .map(rate -> "  (" + operation.sharePercent() + "%, "
                                    + rate.display() + "/sec)")
                            .orElse("  (" + operation.sharePercent() + "%)"))
                    .append('\n');
        }

        return """
                // Generated by Vortex — do not edit by hand.
                //
                // Workload:         %s
                // Evaluation:       %s
                // Workload model:   %s (%s)
                // Plan fingerprint: %s
                // Template version: %s
                //
                // This script is regenerated from the project's configuration every time the test
                // runs. Edit vortex.yaml, not this file. It is written into the execution's artifact
                // directory so you can see exactly what was executed — and it is plain k6, so
                // `k6 run generated-test.js` reproduces this run without Vortex involved.
                //
                // One Vortex workload, one k6 scenario per operation. k6's built-in `scenario` tag
                // therefore identifies the operation on every sample, which is why this script
                // declares no custom metrics and adds no custom tags:
                //
                %s//
                // Secrets appear here as environment lookups, never as values.

                """.formatted(
                plan.workloadName().isBlank() ? "(unnamed)" : plan.workloadName(),
                plan.testType().label(),
                plan.workloadModel().label(),
                plan.peakLevel().displayWithUnit(),
                plan.fingerprint() == null ? "(unset)" : plan.fingerprint().shortHash(),
                TEMPLATE_VERSION,
                mapping);
    }

    private String imports(EffectiveTestPlan plan) {
        boolean needsCheck = plan.operations().stream().anyMatch(op -> !op.expect().isDefault());
        return "import http from 'k6/http';\n"
                + (needsCheck ? "import { check } from 'k6';\n" : "")
                + (K6RequestData.needsSharedArray(plan) ? "import { SharedArray } from 'k6/data';\n" : "")
                + (K6RequestData.needsExecutionApi(plan) ? "import exec from 'k6/execution';\n" : "")
                + "\n";
    }

    private String options(EffectiveTestPlan plan) {
        // k6's own options key, and one of k6's own scenarios per operation. The name here is k6's,
        // not Vortex's: this map is handed to k6 verbatim, and calling it anything else produces a
        // script k6 rejects as having unknown fields.
        Map<String, Object> k6Scenarios = new LinkedHashMap<>();
        OperationMix mix = mixFrom(plan);
        TimeUnitScale scale = TimeUnitScale.forPlan(plan, mix, rateAllocator);
        for (PlannedOperation operation : plan.operations()) {
            k6Scenarios.put(operation.k6ScenarioKey(), workloadFor(plan, operation, mix, scale));
        }

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("scenarios", k6Scenarios);
        options.put("thresholds", thresholds(plan));
        options.put("summaryTrendStats",
                List.of("avg", "min", "med", "max", "p(50)", "p(90)", "p(95)", "p(99)"));
        options.put("discardResponseBodies", true);
        options.put("noConnectionReuse", false);

        return "export const options = " + toJson(options) + ";\n\n";
    }

    /**
     * How Vortex expresses a fractional arrival rate to k6.
     *
     * <p>k6 requires whole-number arrival rates: {@code rate: 40.2} is rejected outright. The
     * documented way to express a fractional rate is to scale the time unit instead —
     * {@code rate: 402, timeUnit: '10s'} is 40.2 arrivals per second, distributed evenly.
     *
     * <p>This matters constantly rather than occasionally: a 70/30 mix at 67 requests/sec produces
     * 46.9 and 20.1, and neither is a whole number. So Vortex finds the smallest time unit that makes
     * <em>every</em> rate in the plan whole. Smallest, because the executor spreads arrivals evenly
     * across the unit, and a needlessly coarse unit makes traffic lumpier than the user asked for.
     *
     * <p>Applies to arrival-rate executors only. Virtual users are whole numbers by construction.
     *
     * @param seconds the time unit, in seconds
     */
    private record TimeUnitScale(int seconds) {

        /** Candidates in increasing order of coarseness. Rates carry at most three decimals. */
        private static final int[] CANDIDATES = {1, 10, 100, 1000};

        static TimeUnitScale forPlan(EffectiveTestPlan plan, OperationMix mix,
                RateAllocator allocator) {
            if (plan.workloadModel() != WorkloadModel.OPEN) {
                return new TimeUnitScale(1);
            }

            List<BigDecimal> rates = new ArrayList<>();
            for (PlannedOperation operation : plan.operations()) {
                operation.arrivalRateIfPresent().ifPresent(rate -> rates.add(rate.value()));
            }
            for (Stage stage : plan.stages()) {
                for (var allocated : allocator.allocate((RequestsPerSecond) stage.target(), mix)
                        .allocations()) {
                    rates.add(allocated.rate().value());
                }
            }

            for (int candidate : CANDIDATES) {
                BigDecimal multiplier = BigDecimal.valueOf(candidate);
                boolean allWhole = rates.stream()
                        .allMatch(rate -> rate.multiply(multiplier).stripTrailingZeros().scale() <= 0);
                if (allWhole) {
                    return new TimeUnitScale(candidate);
                }
            }
            return new TimeUnitScale(CANDIDATES[CANDIDATES.length - 1]);
        }

        /** The k6 {@code timeUnit} literal, e.g. {@code 1s} or {@code 10s}. */
        String timeUnit() {
            return seconds + "s";
        }

        /** A per-second rate expressed in this time unit, as the whole number k6 requires. */
        long express(RequestsPerSecond rate) {
            return Math.max(1, rate.value()
                    .multiply(BigDecimal.valueOf(seconds))
                    .setScale(0, java.math.RoundingMode.HALF_UP)
                    .longValueExact());
        }
    }

    /**
     * Reconstructs the operation mix from the plan's recorded shares.
     *
     * <p>Lets the generator reuse {@code RateAllocator} for every stage of a ramp, which is what
     * keeps the per-operation rates summing to the stage total rather than drifting once they are
     * rounded to the whole numbers k6 requires. Multiplying each share by the stage total
     * independently would be simpler and would quietly not add up.
     */
    private static OperationMix mixFrom(EffectiveTestPlan plan) {
        List<WeightedOperation> entries = new ArrayList<>();
        for (PlannedOperation operation : plan.operations()) {
            int weight = operation.share()
                    .multiply(BigDecimal.valueOf(1_000_000))
                    .setScale(0, java.math.RoundingMode.HALF_UP)
                    .intValueExact();
            entries.add(new WeightedOperation(operation.operationId(), Weight.of(Math.max(1, weight))));
        }
        return OperationMix.of(entries);
    }

    private Map<String, Object> workloadFor(EffectiveTestPlan plan, PlannedOperation operation,
            OperationMix mix, TimeUnitScale scale) {

        Map<String, Object> workload = new LinkedHashMap<>();
        List<Stage> stages = plan.stages();
        boolean ramping = stages.size() > 1;
        boolean open = plan.workloadModel() == WorkloadModel.OPEN;

        if (open) {
            RequestsPerSecond rate = operation.arrivalRateIfPresent()
                    .orElseThrow(() -> new IllegalStateException(
                            "Operation " + operation.name() + " has no allocated rate in an "
                                    + "arrival-rate plan. Rates are assigned during plan resolution."));
            if (ramping) {
                workload.put("executor", "ramping-arrival-rate");
                // Not `rate` — that is the plan's peak, and k6's ramping-arrival-rate executor
                // treats startRate as the level it holds from t=0, ramping toward the first declared
                // stage over that stage's own duration. Starting at the peak made every ramp begin
                // with an unannounced burst at its highest level before "ramping down" into stage
                // one, which is what made an otherwise well-behaved breakpoint test look as if the
                // service broke immediately and then recovered.
                RequestsPerSecond startStageRate = rateAllocator
                        .allocate((RequestsPerSecond) stages.getFirst().target(), mix)
                        .forOperation(operation.operationId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Operation " + operation.name() + " is missing from its own mix"))
                        .rate();
                workload.put("startRate", scale.express(startStageRate));
                workload.put("timeUnit", scale.timeUnit());
                List<Map<String, Object>> k6Stages = new ArrayList<>();
                for (Stage stage : stages) {
                    RateAllocation allocation =
                            rateAllocator.allocate((RequestsPerSecond) stage.target(), mix);
                    RequestsPerSecond forThisOperation = allocation
                            .forOperation(operation.operationId())
                            .orElseThrow(() -> new IllegalStateException(
                                    "Operation " + operation.name() + " is missing from its own mix"))
                            .rate();
                    k6Stages.add(k6Stage(scale.express(forThisOperation), stage.duration()));
                }
                workload.put("stages", k6Stages);
            } else {
                workload.put("executor", "constant-arrival-rate");
                workload.put("rate", scale.express(rate));
                workload.put("timeUnit", scale.timeUnit());
                workload.put("duration", Durations.compact(plan.totalDuration()));
            }
            // Pre-allocation must cover the worst case: enough virtual users to sustain the peak
            // arrival rate even when each request is slow. Too few and k6 reports "insufficient VUs"
            // and quietly under-delivers the requested load, which would make the whole measurement
            // wrong in a way that is easy to miss.
            int peak = (int) Math.ceil(plan.peakLevel().asDouble() * operation.share().doubleValue());
            int preAllocated = Math.max(10, peak);
            workload.put("preAllocatedVUs", preAllocated);
            workload.put("maxVUs", Math.max(preAllocated * 4, 50));
        } else {
            // A concurrency workload always drives a single operation — see Workload's invariant —
            // so the whole virtual-user population belongs to it and there is nothing to divide.
            if (ramping) {
                workload.put("executor", "ramping-vus");
                workload.put("startVUs", ((Concurrency) plan.stages().getFirst().target()).vus());
                workload.put("stages", stages.stream()
                        .map(stage -> k6Stage(((Concurrency) stage.target()).vus(), stage.duration()))
                        .toList());
            } else {
                workload.put("executor", "constant-vus");
                workload.put("vus", ((Concurrency) plan.peakLevel()).vus());
                workload.put("duration", Durations.compact(plan.totalDuration()));
            }
        }

        workload.put("exec", operation.execFunction());
        workload.put("gracefulStop", "30s");

        // Merged last so an engineer's override wins over anything Vortex chose. Vortex does not
        // model k6's option schema — k6 validates it, and preflight surfaces a rejection before any
        // traffic is generated.
        plan.k6Options().forEach((key, value) -> workload.put(key, literal(value)));
        return workload;
    }

    /**
     * Renders an override value as the JSON type it looks like.
     *
     * <p>k6 options are a mixture: {@code maxVUs} is a number, {@code gracefulStop} a duration
     * string, {@code startTime} a string. Emitting everything as a string would make numeric options
     * fail, so a value that parses as a number becomes one and everything else stays a string.
     */
    private static Object literal(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(trimmed);
        }
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private Map<String, Object> k6Stage(long target, java.time.Duration duration) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("target", target);
        entry.put("duration", Durations.compact(duration));
        return entry;
    }

    /**
     * Vortex evaluates thresholds itself, deterministically, from the measurements. These are
     * declared to k6 anyway so that the engine's own exit code reflects the same objectives — which
     * is what lets {@code vortex run --headless} and a bare {@code k6 run} agree.
     *
     * <p>Operation-scoped objectives become k6 submetric thresholds, filtered on the built-in
     * {@code scenario} tag. That is k6's own idiom for exactly this, so the objective is visible in
     * the standard summary rather than only inside Vortex.
     */
    private Map<String, Object> thresholds(EffectiveTestPlan plan) {
        Map<String, List<String>> byMetric = new LinkedHashMap<>();

        for (Threshold threshold : plan.thresholds().thresholds()) {
            String suffix = threshold.scope().operationIfPresent()
                    .flatMap(plan::operation)
                    .map(operation -> "{" + OPERATION_TAG + ":" + operation.k6ScenarioKey() + "}")
                    .orElse("");

            switch (threshold) {
                case LatencyThreshold latency -> byMetric
                        .computeIfAbsent("http_req_duration" + suffix, _ -> new ArrayList<>())
                        .add("p(" + trimPercentile(latency.percentile().asPercent()) + ")<"
                                + latency.maximum().toMillis());
                case ErrorRateThreshold errors -> byMetric
                        .computeIfAbsent("http_req_failed" + suffix, _ -> new ArrayList<>())
                        .add("rate<" + errors.maximum().asFraction());
            }
        }

        return new LinkedHashMap<>(byMetric);
    }

    private String trimPercentile(double percent) {
        return percent == Math.rint(percent)
                ? String.valueOf((long) percent)
                : String.valueOf(percent);
    }

    /**
     * Teaches k6 which responses count as failures, per operation.
     *
     * <p>k6's {@code http_req_failed} defaults to "anything 400 or above", which is a reasonable
     * default and frequently wrong. A lookup of generated test data that legitimately 404s would push
     * the error rate to 100% and fail a run that told you nothing about the service — while a service
     * genuinely answering 404 to everything would look identical.
     *
     * <p>{@code http.expectedStatuses} is k6's own mechanism for saying otherwise, so an operation's
     * declared expectation governs the error rate rather than merely producing a check beside it.
     * Operations that declare nothing keep k6's default.
     */
    private String responseCallbacks(EffectiveTestPlan plan) {
        StringBuilder callbacks = new StringBuilder();
        for (PlannedOperation operation : plan.operations()) {
            if (operation.expect().isDefault()) {
                continue;
            }
            callbacks.append("const ").append(expectedName(operation))
                    .append(" = http.expectedStatuses(")
                    .append(operation.expect().statuses().stream().map(String::valueOf)
                            .reduce((a, b) -> a + ", " + b).orElse(""))
                    .append(");\n");
        }
        return callbacks.isEmpty() ? "" : callbacks + "\n";
    }

    private static String expectedName(PlannedOperation operation) {
        return "expected_" + operation.k6ScenarioKey();
    }

    private String helpers(EffectiveTestPlan plan) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");

        StringBuilder secretHeaders = new StringBuilder();
        for (Map.Entry<String, String> header : plan.headers().entrySet()) {
            var references = dev.vortex.core.environment.SecretReferences
                    .referencedNames(header.getValue());
            if (references.isEmpty()) {
                headers.put(header.getKey(), header.getValue());
            } else {
                // Resolved from the process environment at run time. The value never appears in
                // this file, in the effective plan, in artifacts, in logs or in reports.
                String expression = toJson(header.getValue());
                for (String name : references) {
                    expression = expression.replace("${" + name + "}", "' + (__ENV." + name + " || '') + '");
                }
                secretHeaders.append("baseHeaders[").append(toJson(header.getKey())).append("] = ")
                        .append(expression.replace("\"", "'")).append(";\n");
            }
        }

        return """
                const BASE_URL = %s;

                const baseHeaders = %s;
                %s
                const params = { headers: baseHeaders };

                """.formatted(toJson(plan.effectiveTarget().value()), toJson(headers), secretHeaders);
    }

    private String operationFunction(PlannedOperation operation, EffectiveTestPlan plan,
            K6RequestData requestData) {
        StringBuilder body = new StringBuilder();

        body.append("// ").append(operation.name()).append(" — ")
                .append(operation.sharePercent()).append("% of traffic")
                .append(operation.arrivalRateIfPresent().map(r -> ", " + r.displayWithUnit()).orElse(""))
                .append("\nexport function ").append(operation.execFunction()).append("() {\n");

        body.append(requestData.preamble(operation, plan));
        body.append(requestFor(operation, requestData));
        body.append("}\n\n");
        return body.toString();
    }

    private String requestFor(PlannedOperation operation, K6RequestData requestData) {
        String url = urlFor(operation, requestData);

        List<String> overrides = new ArrayList<>();
        if (!operation.headers().isEmpty()) {
            overrides.add("headers: Object.assign({}, baseHeaders, "
                    + headerObject(operation, requestData) + ")");
        }
        if (!operation.expect().isDefault()) {
            overrides.add("responseCallback: " + expectedName(operation));
        }
        String requestParams = overrides.isEmpty()
                ? "params"
                : "Object.assign({}, params, { " + String.join(", ", overrides) + " })";

        // Only bind the response when something reads it. An unused const is harmless to k6 and
        // untidy to a person, and this file is meant to be read.
        boolean checked = !operation.expect().isDefault();

        StringBuilder request = new StringBuilder();
        request.append(checked ? "  const response = http." : "  http.")
                .append(operation.method().name().toLowerCase(java.util.Locale.ROOT))
                .append("(").append(url);

        if (operation.hasBody() || !operation.bodyValues().isEmpty()) {
            request.append(", ").append(bodyExpression(operation, requestData));
        } else if (operation.method().isMutating()) {
            request.append(", null");
        }
        request.append(", ").append(requestParams).append(");\n");

        if (!operation.expect().isDefault()) {
            // Alongside the response callback above, not instead of it. The callback decides what
            // counts as a failure; the check gives that decision a name in k6's own summary, so a
            // reader sees "status is 200 or 201: 3 failures" rather than only a moved error rate.
            request.append("  check(response, { ")
                    .append(toJson(operation.expect().describe()))
                    .append(": (r) => ")
                    .append(operation.expect().statuses().stream()
                            .map(status -> "r.status === " + status)
                            .reduce((a, b) -> a + " || " + b)
                            .orElse("r.status > 0"))
                    .append(" });\n");
        }

        return request.toString();
    }

    /**
     * The URL, assembled.
     *
     * <p>An operation whose path and query are entirely fixed produces the literal it always did:
     * {@code BASE_URL + "/orders/ord-1"}. One with a value that does not exist until the request is
     * issued produces a concatenation instead, with every dynamic part percent-encoded — a customer
     * id containing a slash must not become a different path.
     */
    private String urlFor(PlannedOperation operation, K6RequestData requestData) {
        return "BASE_URL + " + pathExpression(operation, requestData)
                + queryString(operation, requestData);
    }

    private String pathExpression(PlannedOperation operation, K6RequestData requestData) {
        if (operation.hasFixedPath()) {
            return toJson(operation.resolvedPath());
        }
        // Split the template on its placeholders and rebuild it, so the literal segments stay
        // literals and only the values become expressions.
        StringBuilder expression = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        String template = operation.pathTemplate();
        int index = 0;
        while (index < template.length()) {
            int open = template.indexOf('{', index);
            int close = open < 0 ? -1 : template.indexOf('}', open);
            if (open < 0 || close < 0) {
                literal.append(template, index, template.length());
                break;
            }
            literal.append(template, index, open);
            String name = template.substring(open + 1, close);
            var value = operation.pathValues().get(name);
            if (value == null) {
                // No value for this placeholder: leave it as written rather than silently dropping
                // it, so the request fails visibly against a path that still shows the gap.
                literal.append(template, open, close + 1);
            } else {
                appendLiteral(expression, literal);
                expression.append(expression.isEmpty() ? "" : " + ")
                        .append("encodeURIComponent(")
                        .append(requestData.expressionFor(operation, value, "path:" + name))
                        .append(")");
            }
            index = close + 1;
        }
        appendLiteral(expression, literal);
        return expression.isEmpty() ? toJson("") : expression.toString();
    }

    private void appendLiteral(StringBuilder expression, StringBuilder literal) {
        if (literal.isEmpty()) {
            return;
        }
        expression.append(expression.isEmpty() ? "" : " + ").append(toJson(literal.toString()));
        literal.setLength(0);
    }

    private String queryString(PlannedOperation operation, K6RequestData requestData) {
        if (operation.queryValues().isEmpty()) {
            return "";
        }
        StringBuilder query = new StringBuilder(" + '?'");
        boolean first = true;
        for (Map.Entry<String, RequestValue> entry : operation.queryValues().entrySet()) {
            String separator = first ? "''" : toJson("&");
            first = false;
            if (entry.getValue() instanceof FixedValue fixed) {
                query.append(" + ").append(separator)
                        .append(" + ").append(toJson(urlEncode(entry.getKey()) + "="
                                + urlEncode(fixed.literal())));
            } else {
                query.append(" + ").append(separator)
                        .append(" + ").append(toJson(urlEncode(entry.getKey()) + "="))
                        .append(" + encodeURIComponent(")
                        .append(requestData.expressionFor(operation, entry.getValue(),
                                "query:" + entry.getKey()))
                        .append(")");
            }
        }
        return query.toString();
    }

    /**
     * The per-operation header overrides.
     *
     * <p>All-fixed headers emit the object literal they always did. A dynamic one emits an
     * expression beside the literals, which is why this is built by hand rather than serialised.
     */
    private String headerObject(PlannedOperation operation, K6RequestData requestData) {
        boolean allFixed = operation.headers().values().stream()
                .noneMatch(RequestValue::isDynamic);
        if (allFixed) {
            Map<String, String> literals = new LinkedHashMap<>();
            operation.headers().forEach((name, value) ->
                    literals.put(name, ((FixedValue) value).literal()));
            return toJson(literals);
        }
        List<String> entries = new ArrayList<>();
        operation.headers().forEach((name, value) -> entries.add(toJson(name) + ": "
                + requestData.expressionFor(operation, value, "header:" + name)));
        return "{ " + String.join(", ", entries) + " }";
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Base request documents, for operations that bind individual fields.
     *
     * <p>Held as a JSON <em>string</em> at module scope and parsed per execution, which is how each
     * request gets its own copy to bind into. Cloning by re-parsing is a line of code rather than a
     * deep-copy helper, and it is obviously correct in a way a hand-written clone is not.
     *
     * <p>Operations that send a fixed body get nothing here: their payload is still emitted as a
     * literal at the point of use, exactly as before.
     */
    private String bodyDocuments(EffectiveTestPlan plan) {
        StringBuilder out = new StringBuilder();
        for (PlannedOperation operation : plan.operations()) {
            if (operation.bodyValues().isEmpty()) {
                continue;
            }
            out.append("const ").append(bodyDocumentName(operation)).append(" = ")
                    .append(toJson(baseDocumentOf(operation))).append(";\n");
        }
        return out.isEmpty() ? "" : out.append('\n').toString();
    }

    private static String bodyDocumentName(PlannedOperation operation) {
        return "body_" + operation.k6ScenarioKey();
    }

    /**
     * The document field bindings are applied over.
     *
     * <p>An operation with bound fields and no body of its own starts from an empty object — binding
     * {@code customerId} onto nothing should produce {@code {"customerId": ...}}, not fail.
     *
     * @throws IllegalStateException when the body is not a JSON object. Preflight rejects this
     *         first; reaching here means a plan was built that cannot be executed, and emitting a
     *         script that corrupts somebody's payload would be the worse outcome.
     */
    private String baseDocumentOf(PlannedOperation operation) {
        String body = operation.body();
        if (body.isBlank()) {
            return "{}";
        }
        try {
            if (!json.readTree(body).isObject()) {
                throw new IllegalStateException(bodyFieldsNeedAnObject(operation, null));
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(bodyFieldsNeedAnObject(operation, e), e);
        }
        return body;
    }

    private String bodyFieldsNeedAnObject(PlannedOperation operation, Exception cause) {
        return operation.name() + " binds individual body fields, but its request body is not a JSON "
                + "object" + (cause == null ? "" : " (" + cause.getMessage() + ")")
                + ". Field mapping addresses named properties of a JSON document; a body that is not "
                + "one has no fields to map. Either supply a JSON object as the body, or send the "
                + "body as a single fixed value.";
    }

    /**
     * The request payload.
     *
     * <p>A fixed body is the literal it always was. A body with bound fields is parsed from its base
     * document, has its fields set, and is serialised — the value is bound as JSON, never spliced
     * into a string, so a dataset cell containing a quote is a quote in a string and not the end of
     * one.
     */
    private String bodyExpression(PlannedOperation operation, K6RequestData requestData) {
        if (operation.bodyValues().isEmpty()) {
            return toJson(operation.body());
        }
        return "JSON.stringify(" + boundBodyExpression(operation, requestData) + ")";
    }

    private String boundBodyExpression(PlannedOperation operation, K6RequestData requestData) {
        StringBuilder expression = new StringBuilder("setFields(JSON.parse(")
                .append(bodyDocumentName(operation)).append("), [");
        List<String> assignments = new ArrayList<>();
        operation.bodyValues().forEach((path, value) -> assignments.add(
                "[" + toJson(path.asText()) + ", "
                        + requestData.expressionFor(operation, value, "body:" + path.asText())
                        + "]"));
        return expression.append(String.join(", ", assignments)).append("])").toString();
    }

    /**
     * The one helper the body path needs: bind a value to a dotted field.
     *
     * <p>Emitted only when something uses it. Deliberately does not walk arrays, apply filters or
     * evaluate expressions — the configuration grammar it serves has none of those, and a helper
     * more capable than the thing that calls it is an invitation.
     */
    private String bodyFieldHelper(EffectiveTestPlan plan) {
        boolean needed = plan.operations().stream()
                .anyMatch(operation -> !operation.bodyValues().isEmpty());
        if (!needed) {
            return "";
        }
        return """
                // Binds values to named fields of the request document, creating intermediate
                // objects where the path goes deeper than the document does.
                function setFields(document, bindings) {
                  for (const [path, value] of bindings) {
                    const parts = path.split('.');
                    let node = document;
                    for (let i = 0; i < parts.length - 1; i++) {
                      if (typeof node[parts[i]] !== 'object' || node[parts[i]] === null) {
                        node[parts[i]] = {};
                      }
                      node = node[parts[i]];
                    }
                    node[parts[parts.length - 1]] = value;
                  }
                  return document;
                }

                """;
    }

    private String summaryHandler() {
        return """
                // Vortex reads this file rather than scraping the console output, so results survive
                // whatever the terminal does with them. Running this script with plain k6 simply
                // writes the same file beside it.
                export function handleSummary(data) {
                  return { %s: JSON.stringify(data) };
                }
                """.formatted(toJson(SUMMARY_FILE));
    }

    /**
     * Serialises a value as a JSON literal.
     *
     * <p>The single most important method in this class. Every value that originates from user
     * configuration passes through here, so a path, header or payload containing quotes, newlines
     * or script fragments becomes an escaped literal rather than code.
     */
    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise a value into the generated script", e);
        }
    }
}
