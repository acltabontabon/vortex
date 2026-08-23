package com.acltabontabon.vortex.core.application;

import com.acltabontabon.vortex.core.catalog.Operation;
import com.acltabontabon.vortex.core.catalog.ParameterSpec;
import com.acltabontabon.vortex.core.data.DatasetRecords;
import com.acltabontabon.vortex.core.data.DatasetRef;
import com.acltabontabon.vortex.core.data.DatasetValue;
import com.acltabontabon.vortex.core.data.FixedValue;
import com.acltabontabon.vortex.core.data.RequestData;
import com.acltabontabon.vortex.core.data.RequestValue;
import com.acltabontabon.vortex.core.data.RequestValueTarget;
import com.acltabontabon.vortex.core.plan.PlannedDataset;
import com.acltabontabon.vortex.core.plan.PlannedOperation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Refuses a request Vortex can already tell will not work.
 *
 * <p>Waiting for k6 to fail is the wrong answer twice over: it costs the user a run to find out, and
 * the failure arrives as a JavaScript error about an undefined property rather than as a sentence
 * naming the column they mistyped. Everything checkable from the configuration is checked here.
 *
 * <h2>Validation is per destination</h2>
 *
 * <p>A newline is a paragraph in a body field and a request-splitting vector in a header, so a value
 * is judged against the position it is actually bound to rather than against one global rule.
 * Rejecting multi-line text at ingestion would corrupt legitimate data to defend a position it may
 * never reach; permitting it everywhere would let a dataset cell forge a header.
 *
 * <p>Only the columns that reach a header are scanned, and only for the operations that bind them.
 * Reading every cell of a realistic dataset to check a constraint that applies to one column of it
 * would be a cost paid on every run for nothing.
 *
 * <h2>What cannot be checked, and is not pretended</h2>
 *
 * <p>An environment value's content is unknown here by design — it exists only inside the engine's
 * process. Vortex checks that the variable is <em>set</em> (that is preflight's job) and leaves the
 * legality of its content to the HTTP client, which refuses an illegal header value outright.
 */
final class RequestDataValidator {

    private RequestDataValidator() {
    }

    /**
     * @param operations the resolved operations
     * @param datasets   the datasets the plan carries, by reference
     * @param rows       reads a dataset's records; called at most once per dataset, and only when
     *                   something binds one of its columns to a header
     */
    static List<String> validate(List<PlannedOperation> operations,
            Map<DatasetRef, PlannedDataset> datasets,
            Function<DatasetRef, DatasetRecords> rows) {

        List<String> problems = new ArrayList<>();
        Map<DatasetRef, DatasetRecords> read = new LinkedHashMap<>();

        for (PlannedOperation operation : operations) {
            checkBodyIsMappable(operation, problems);

            operation.requestData().byTarget().forEach((target, values) ->
                    values.forEach((name, value) ->
                            checkValue(operation, target, name, value, datasets, rows, read,
                                    problems)));
        }
        return problems;
    }

    /**
     * Fixed values checked against what the specification says it will accept.
     *
     * <p>Only enums, and only fixed values. An {@code enum} is the one part of a schema that states
     * a fact strong enough to act on: the service will reject anything else, so sending it wastes a
     * run. A {@code format} is not — it describes shape, and a value that does not match one may
     * still be exactly what the endpoint wants.
     *
     * <p>Nothing generated, dataset-supplied or environment-resolved is checked here. Their values
     * do not exist yet, and a dataset column checked against an enum would be Vortex deciding that
     * curated test data is wrong because a schema is narrower than reality — which is frequently the
     * schema's fault.
     *
     * @param operation   the catalog operation, which carries the schema
     * @param requestData the resolved values
     */
    static List<String> validateAgainstSchema(Operation operation, RequestData requestData,
            String label) {

        List<String> problems = new ArrayList<>();

        for (ParameterSpec parameter : operation.parameters()) {
            RequestValue value = valueFor(requestData, parameter);
            if (value instanceof FixedValue fixed) {
                rejectOutsideEnum(label, parameter.name(), fixed.literal(), parameter.enumValues())
                        .ifPresent(problems::add);
            }
        }

        operation.body().ifPresent(body -> body.fields().forEach(hint -> {
            RequestValue value = requestData.bodyValues().entrySet().stream()
                    .filter(entry -> entry.getKey().asText().equals(hint.field()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
            if (value instanceof FixedValue fixed) {
                rejectOutsideEnum(label, hint.field(), fixed.literal(), hint.enumValues())
                        .ifPresent(problems::add);
            }
        }));

        return problems;
    }

    private static Optional<String> rejectOutsideEnum(String label, String name, String literal,
            List<String> permitted) {
        if (permitted.isEmpty() || permitted.contains(literal)) {
            return Optional.empty();
        }
        return Optional.of(label + ": '" + name + "' is set to \"" + literal + "\", but the API "
                + "description permits only " + String.join(", ", permitted) + ". The service will "
                + "reject every request, so the run would measure its rejection path.");
    }

    private static RequestValue valueFor(RequestData requestData, ParameterSpec parameter) {
        return switch (parameter.location()) {
            case PATH -> requestData.pathValues().get(parameter.name());
            case QUERY -> requestData.queryValues().get(parameter.name());
            case HEADER -> requestData.headers().get(parameter.name());
            case COOKIE -> null;
        };
    }

    private static void checkValue(PlannedOperation operation, RequestValueTarget target,
            String name, RequestValue value, Map<DatasetRef, PlannedDataset> datasets,
            Function<DatasetRef, DatasetRecords> rows, Map<DatasetRef, DatasetRecords> read,
            List<String> problems) {

        switch (value) {
            case FixedValue fixed -> target.reject(name, fixed.literal())
                    .ifPresent(problem -> problems.add(operation.name() + ": " + problem));

            case DatasetValue dataset -> {
                PlannedDataset planned = datasets.get(dataset.dataset());
                if (planned == null) {
                    // Resolution already reported the dataset itself as unresolvable; repeating it
                    // once per bound column would bury the one line that matters.
                    return;
                }
                if (!planned.hasField(dataset.field())) {
                    problems.add(unknownField(operation, target, name, dataset, planned));
                    return;
                }
                if (target == RequestValueTarget.HEADER) {
                    checkColumnIsHeaderSafe(operation, name, dataset, rows, read, problems);
                }
            }

            // A generator's output is well-formed by construction, and an environment value's
            // content is deliberately unknowable here.
            default -> { }
        }
    }

    private static String unknownField(PlannedOperation operation, RequestValueTarget target,
            String name, DatasetValue value, PlannedDataset dataset) {
        return operation.name() + ": the " + target.label() + " '" + name + "' reads column '"
                + value.field() + "' from " + dataset.name() + ", but that dataset has no such "
                + "column. Available columns: " + dataset.describeFields() + ".";
    }

    private static void checkColumnIsHeaderSafe(PlannedOperation operation, String name,
            DatasetValue value, Function<DatasetRef, DatasetRecords> rows,
            Map<DatasetRef, DatasetRecords> read, List<String> problems) {

        DatasetRecords records =
                read.computeIfAbsent(value.dataset(), ref -> rows.apply(ref));
        if (records == null) {
            return;
        }
        int rowNumber = 0;
        for (Map<String, Object> row : records.rows()) {
            rowNumber++;
            Object cell = row.get(value.field());
            if (cell == null) {
                continue;
            }
            var rejection = RequestValueTarget.HEADER.reject(name, String.valueOf(cell));
            if (rejection.isPresent()) {
                problems.add(operation.name() + ": row " + rowNumber + " of " + value.datasetName()
                        + " would put an illegal value in the header '" + name + "'. "
                        + rejection.get()
                        + " Either correct that row, or bind this column somewhere it is allowed — "
                        + "a body field may contain anything.");
                // One row is enough to stop the run. Listing all of them turns a fixable mistake
                // into a wall of text that hides how many distinct problems there really are.
                return;
            }
        }
    }

    /**
     * Body field bindings need a document with fields.
     *
     * <p>A structural check rather than a parse: {@code vortex-core} has no JSON library, by design.
     * The authoritative check happens where the script is generated, with a real parser — this one
     * exists so the common mistake is caught before a run starts rather than as it launches.
     */
    private static void checkBodyIsMappable(PlannedOperation operation, List<String> problems) {
        if (operation.bodyValues().isEmpty()) {
            return;
        }
        String body = operation.body().trim();
        if (body.isEmpty() || (body.startsWith("{") && body.endsWith("}"))) {
            return;
        }
        problems.add(operation.name() + ": individual body fields are bound ("
                + operation.bodyValues().keySet().iterator().next()
                + "), but this operation's request body is not a JSON object. Field mapping "
                + "addresses named properties of a JSON document. Either supply a JSON object as "
                + "the body, or send the body as a single fixed value.");
    }
}
