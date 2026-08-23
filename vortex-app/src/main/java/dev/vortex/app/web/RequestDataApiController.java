package dev.vortex.app.web;

import static dev.vortex.app.web.RequestDataDtos.DATASET;
import static dev.vortex.app.web.RequestDataDtos.ENVIRONMENT;
import static dev.vortex.app.web.RequestDataDtos.FIXED;
import static dev.vortex.app.web.RequestDataDtos.GENERATED;

import dev.vortex.app.web.RequestDataDtos.DatasetDto;
import dev.vortex.app.web.RequestDataDtos.GeneratorDto;
import dev.vortex.app.web.RequestDataDtos.RequestDataDto;
import dev.vortex.app.web.RequestDataDtos.SaveRequestDataRequest;
import dev.vortex.app.web.RequestDataDtos.SuggestionDto;
import dev.vortex.app.web.RequestDataDtos.UploadDatasetRequest;
import dev.vortex.app.web.RequestDataDtos.ValueSlotDto;
import dev.vortex.app.web.RequestDataDtos.ValueUpdateDto;
import dev.vortex.core.application.CatalogImportService;
import dev.vortex.core.application.ProjectService;
import dev.vortex.core.application.RequestDataSuggestions;
import dev.vortex.core.catalog.Operation;
import dev.vortex.core.catalog.OperationBinding;
import dev.vortex.core.catalog.ParameterLocation;
import dev.vortex.core.catalog.ParameterSpec;
import dev.vortex.core.data.BodyFieldPath;
import dev.vortex.core.data.Dataset;
import dev.vortex.core.data.DatasetException;
import dev.vortex.core.data.DatasetFormat;
import dev.vortex.core.data.DatasetHome;
import dev.vortex.core.data.DatasetRef;
import dev.vortex.core.data.DatasetScope;
import dev.vortex.core.data.DatasetValue;
import dev.vortex.core.data.EnvironmentValue;
import dev.vortex.core.data.FixedValue;
import dev.vortex.core.data.GeneratedValue;
import dev.vortex.core.data.Generator;
import dev.vortex.core.data.RequestData;
import dev.vortex.core.data.RequestValue;
import dev.vortex.core.data.RequestValueTarget;
import dev.vortex.core.data.ValueLifecycle;
import dev.vortex.core.port.DatasetStore;
import dev.vortex.core.project.Project;
import dev.vortex.core.project.ProjectConfiguration;
import dev.vortex.core.shared.OperationId;
import dev.vortex.core.shared.ProjectId;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Where a request's values come from, and the data they are drawn from.
 *
 * <p>Its own controller rather than more of {@code ConfigurationApiController}, which already
 * carries environments, thresholds, production observation and the local lab. Request data is a
 * coherent surface with its own vocabulary, and a seventh unrelated concern in one class is how a
 * controller becomes something nobody wants to open.
 *
 * <p>Editing is per operation, because that is how somebody thinks about it: they are looking at
 * {@code POST /applications} and deciding what it sends. There is deliberately no endpoint that
 * edits every operation's request data at once — that would be the wall of configuration this
 * feature exists to avoid.
 */
@RestController
@RequestMapping("/api/services/{id}")
class RequestDataApiController {

    /** Enough to recognise a dataset. Not enough to browse it, which is not what this page is for. */
    private static final int PREVIEW_RECORDS = 5;

    /** A dataset arrives as text in a JSON body, so it is bounded before it is parsed. */
    private static final int MAX_UPLOAD_CHARACTERS = 32 * 1024 * 1024;

    private final ProjectService projects;
    private final CatalogImportService catalog;
    private final DatasetStore datasets;
    private final Predicate<String> environmentVariableExists;

    RequestDataApiController(ProjectService projects, CatalogImportService catalog,
            DatasetStore datasets) {
        this.projects = projects;
        this.catalog = catalog;
        this.datasets = datasets;
        // Presence only. Whether a variable is set is something the interface has to be able to
        // show; what it contains is not, and this is the only question asked of the environment.
        this.environmentVariableExists = name -> System.getenv(name) != null;
    }

    // ==================================================================== request data

    @GetMapping("/operations/{operationId}/request-data")
    RequestDataDto requestData(@PathVariable String id, @PathVariable String operationId) {
        ProjectId projectId = ProjectId.of(id);
        Operation operation = operationOf(projectId, operationId);
        OperationBinding binding = projects.configuration(projectId)
                .bindingOrDefault(operation.id());

        return new RequestDataDto(
                operation.id().value(),
                operation.label(),
                operation.method().name(),
                operation.path(),
                operation.requiresReview(),
                binding.reviewed(),
                effectiveBody(operation, binding),
                slots(operation, binding),
                datasetsOf(projectId),
                generators());
    }

    @PostMapping("/operations/{operationId}/request-data")
    ConfigurationApiController.MessageResponse saveRequestData(@PathVariable String id,
            @PathVariable String operationId, @RequestBody SaveRequestDataRequest request) {

        ProjectId projectId = ProjectId.of(id);
        Operation operation = operationOf(projectId, operationId);
        ProjectConfiguration configuration = projects.configuration(projectId);

        try {
            RequestData requestData = requestDataFrom(request, operation, configuration);
            OperationBinding binding = configuration.bindingOrDefault(operation.id())
                    .withRequestData(requestData);
            projects.saveConfiguration(projectId, configuration.withBinding(binding));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
        return new ConfigurationApiController.MessageResponse(
                "Saved. " + operation.label() + " will send these values on every run.");
    }

    // ==================================================================== datasets

    @GetMapping("/datasets")
    List<DatasetDto> datasets(@PathVariable String id) {
        return datasetsOf(ProjectId.of(id));
    }

    @PostMapping("/datasets")
    DatasetDto upload(@PathVariable String id, @RequestBody UploadDatasetRequest request) {
        DatasetHome home = homeOf(ProjectId.of(id));
        String content = request.content() == null ? "" : request.content();
        if (content.length() > MAX_UPLOAD_CHARACTERS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "That file is larger than Vortex will read. A performance test needs realistic "
                            + "data, not all of it — a few thousand rows exercise the same paths.");
        }
        try {
            // Local unless somebody says otherwise. An upload must never become a commit in a
            // repository by default; making a dataset portable is its own deliberate action.
            DatasetScope scope = DatasetScope.fromKey(request.scope());
            Dataset stored = datasets.store(home, scope, nameOf(request),
                    DatasetFormat.fromKey(request.format()),
                    content.getBytes(StandardCharsets.UTF_8));
            return describe(home, stored);
        } catch (DatasetException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problemsOf(e), e);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    @PostMapping("/datasets/{name}/promote")
    DatasetDto promote(@PathVariable String id, @PathVariable String name) {
        DatasetHome home = homeOf(ProjectId.of(id));
        try {
            return describe(home, datasets.promote(home, DatasetRef.local(name)));
        } catch (DatasetException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, problemsOf(e), e);
        }
    }

    @DeleteMapping("/datasets/{name}")
    ConfigurationApiController.MessageResponse delete(@PathVariable String id, @PathVariable String name,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "local")
            String scope) {

        DatasetHome home = homeOf(ProjectId.of(id));
        DatasetRef ref = new DatasetRef(name, DatasetScope.fromKey(scope));
        datasets.delete(home, ref);
        return new ConfigurationApiController.MessageResponse(
                "Deleted " + name + ". Any request value that read it will need a new source.");
    }

    // ==================================================================== assembling slots

    /**
     * Every value this operation can carry, whether or not it is configured.
     *
     * <p>Built from the specification first, so a user sees the parameters the endpoint actually
     * declares rather than an empty page they have to guess at. Values a person added that the
     * specification does not mention are kept and listed after — a header the API description
     * forgot is still a header the service needs.
     */
    private List<ValueSlotDto> slots(Operation operation, OperationBinding binding) {
        Map<String, SuggestionDto> suggestions = suggestionsFor(operation);
        List<ValueSlotDto> slots = new ArrayList<>();

        for (ParameterSpec parameter : operation.parameters()) {
            RequestValueTarget target = targetOf(parameter.location());
            if (target == null) {
                continue;
            }
            Map<String, RequestValue> configured = valuesFor(binding, target);
            slots.add(slot(target, parameter.name(), parameter.required(),
                    configured.get(parameter.name()), suggestions));
        }

        addExtras(slots, RequestValueTarget.HEADER, binding.headers(), suggestions);
        addExtras(slots, RequestValueTarget.PATH, binding.pathValues(), suggestions);
        addExtras(slots, RequestValueTarget.QUERY, binding.queryValues(), suggestions);

        // Body fields come from the schema where there is one, and from what somebody bound where
        // there is not.
        Map<String, RequestValue> bodyValues = new TreeMap<>();
        binding.bodyValues().forEach((path, value) -> bodyValues.put(path.asText(), value));
        operation.body().ifPresent(body -> body.fields().forEach(hint -> {
            if (!bodyValues.containsKey(hint.field())) {
                bodyValues.put(hint.field(), null);
            }
        }));
        bodyValues.forEach((field, value) ->
                slots.add(slot(RequestValueTarget.BODY_FIELD, field, false, value, suggestions)));

        return List.copyOf(slots);
    }

    private void addExtras(List<ValueSlotDto> slots, RequestValueTarget target,
            Map<String, RequestValue> configured, Map<String, SuggestionDto> suggestions) {
        configured.forEach((name, value) -> {
            boolean alreadyListed = slots.stream()
                    .anyMatch(slot -> slot.target().equals(target.name()) && slot.name().equals(name));
            if (!alreadyListed) {
                slots.add(slot(target, name, false, value, suggestions));
            }
        });
    }

    private ValueSlotDto slot(RequestValueTarget target, String name, boolean required,
            RequestValue value, Map<String, SuggestionDto> suggestions) {

        SuggestionDto suggestion = suggestions.get(target.name() + ":" + name);

        return switch (value) {
            case FixedValue fixed -> new ValueSlotDto(target.name(), name, required, FIXED,
                    fixed.literal(), null, null, null, null, null, null, null, null, null, false,
                    suggestion);

            case GeneratedValue generated -> new ValueSlotDto(target.name(), name, required,
                    GENERATED, null, generated.generator().key(), generated.lifecycle().key(),
                    generated.generator().usesRange() ? generated.minimum() : null,
                    generated.generator().usesRange() ? generated.maximum() : null,
                    generated.generator().usesLength() ? generated.length() : null,
                    null, null, null, null, false, suggestion);

            case DatasetValue dataset -> new ValueSlotDto(target.name(), name, required, DATASET,
                    null, null, null, null, null, null, dataset.datasetName(),
                    dataset.dataset().scope().key(), dataset.field(), null, false, suggestion);

            case EnvironmentValue environment -> {
                String variable = environment.referencedNames().stream().findFirst().orElse("");
                yield new ValueSlotDto(target.name(), name, required, ENVIRONMENT, null, null, null,
                        null, null, null, null, null, null, variable,
                        environmentVariableExists.test(variable), suggestion);
            }

            case null -> new ValueSlotDto(target.name(), name, required, "", null, null, null, null,
                    null, null, null, null, null, null, false, suggestion);
        };
    }

    private Map<String, SuggestionDto> suggestionsFor(Operation operation) {
        Map<String, SuggestionDto> byKey = new LinkedHashMap<>();
        for (RequestDataSuggestions suggestion : RequestDataSuggestions.forOperation(operation)) {
            byKey.put(suggestion.target().name() + ":" + suggestion.field(), new SuggestionDto(
                    suggestion.isConstrainedChoice() ? FIXED : GENERATED,
                    suggestion.generatorIfPresent().map(Generator::key).orElse(null),
                    suggestion.choices(),
                    suggestion.reason()));
        }
        return byKey;
    }

    // ==================================================================== reading edits back

    private RequestData requestDataFrom(SaveRequestDataRequest request, Operation operation,
            ProjectConfiguration configuration) {

        Map<String, RequestValue> headers = new LinkedHashMap<>();
        Map<String, RequestValue> pathValues = new LinkedHashMap<>();
        Map<String, RequestValue> queryValues = new LinkedHashMap<>();
        Map<BodyFieldPath, RequestValue> bodyValues = new TreeMap<>();

        for (ValueUpdateDto slot : request.values() == null
                ? List.<ValueUpdateDto>of() : request.values()) {
            RequestValue value = valueFrom(slot);
            if (value == null) {
                // A slot with no source is not configured, which is different from configured as
                // empty — the specification's own default still applies to it.
                continue;
            }
            switch (RequestValueTarget.valueOf(slot.target())) {
                case HEADER -> headers.put(slot.name(), value);
                case PATH -> pathValues.put(slot.name(), value);
                case QUERY -> queryValues.put(slot.name(), value);
                case BODY_FIELD -> bodyValues.put(BodyFieldPath.parse(slot.name()), value);
            }
        }

        String body = request.body() == null ? "" : request.body();
        // A body identical to the one the specification generates is not a decision worth recording:
        // storing it would mean re-importing the document could no longer update it.
        if (body.equals(operation.body().map(spec -> spec.payload()).orElse(""))) {
            body = "";
        }
        return new RequestData(pathValues, queryValues, headers, body, bodyValues);
    }

    private RequestValue valueFrom(ValueUpdateDto slot) {
        String source = slot.source() == null ? "" : slot.source();
        return switch (source) {
            case FIXED -> FixedValue.of(slot.literal());

            case GENERATED -> new GeneratedValue(
                    Generator.fromKey(slot.generator()),
                    ValueLifecycle.fromKey(slot.lifecycle()),
                    slot.minimum() == null ? 1L : slot.minimum(),
                    slot.maximum() == null ? 1_000_000L : slot.maximum(),
                    slot.length() == null ? 12 : slot.length());

            case DATASET -> new DatasetValue(
                    new DatasetRef(slot.dataset(), DatasetScope.fromKey(slot.datasetScope())),
                    slot.field());

            case ENVIRONMENT -> EnvironmentValue.named(slot.environmentVariable());

            default -> null;
        };
    }

    // ==================================================================== datasets

    private List<DatasetDto> datasetsOf(ProjectId projectId) {
        DatasetHome home = homeOf(projectId);
        List<DatasetDto> described = new ArrayList<>();
        for (Dataset dataset : datasets.list(home)) {
            described.add(describe(home, dataset));
        }
        return List.copyOf(described);
    }

    private DatasetDto describe(DatasetHome home, Dataset dataset) {
        List<Map<String, Object>> preview = List.of();
        String problem = "";
        try {
            preview = datasets.read(home, dataset.ref()).rows().stream()
                    .limit(PREVIEW_RECORDS)
                    .toList();
        } catch (DatasetException e) {
            // A dataset that no longer parses still exists, and the interface has to be able to say
            // so — with the reason, rather than by omitting it and leaving a value pointing at
            // nothing visible.
            problem = problemsOf(e);
        }
        return new DatasetDto(
                dataset.name(),
                dataset.scope().key(),
                dataset.format().key(),
                dataset.recordCount(),
                dataset.fields(),
                dataset.location(),
                preview,
                dataset.scope() == DatasetScope.LOCAL
                        ? datasets.promotionTarget(home, dataset.ref()) : "",
                problem);
    }

    // ==================================================================== helpers

    private List<GeneratorDto> generators() {
        List<GeneratorDto> described = new ArrayList<>();
        for (Generator generator : Generator.values()) {
            described.add(new GeneratorDto(generator.key(), generator.label(), generator.meaning(),
                    generator.usesRange(), generator.usesLength()));
        }
        return List.copyOf(described);
    }

    private Operation operationOf(ProjectId projectId, String operationId) {
        return catalog.catalog(projectId)
                .flatMap(found -> found.find(OperationId.of(operationId)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "This service's imported API description has no operation called '"
                                + operationId + "'. Re-import the specification if it has changed."));
    }

    private String effectiveBody(Operation operation, OperationBinding binding) {
        return binding.bodyIfPresent()
                .orElseGet(() -> operation.body().map(spec -> spec.payload()).orElse(""));
    }

    private Map<String, RequestValue> valuesFor(OperationBinding binding,
            RequestValueTarget target) {
        return switch (target) {
            case HEADER -> binding.headers();
            case PATH -> binding.pathValues();
            case QUERY -> binding.queryValues();
            case BODY_FIELD -> Map.of();
        };
    }

    private RequestValueTarget targetOf(ParameterLocation location) {
        return switch (location) {
            case PATH -> RequestValueTarget.PATH;
            case QUERY -> RequestValueTarget.QUERY;
            case HEADER -> RequestValueTarget.HEADER;
            case COOKIE -> null;
        };
    }

    private DatasetHome homeOf(ProjectId projectId) {
        Project project = projects.find(projectId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No service with id " + projectId.value()));
        return DatasetHome.of(project.id(), project.workspacePath());
    }

    private String nameOf(UploadDatasetRequest request) {
        String name = request.name() == null || request.name().isBlank()
                ? "" : request.name().trim();
        return name.isBlank() ? "dataset" : name;
    }

    private String problemsOf(DatasetException e) {
        if (e.problems().isEmpty()) {
            return e.getMessage();
        }
        return e.problems().stream().map(problem -> problem.describe())
                .reduce((a, b) -> a + " " + b).orElse(e.getMessage());
    }
}
