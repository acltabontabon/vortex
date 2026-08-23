package dev.vortex.openapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vortex.core.catalog.CatalogSource;
import dev.vortex.core.catalog.HttpMethod;
import dev.vortex.core.catalog.Operation;
import dev.vortex.core.catalog.ParameterLocation;
import dev.vortex.core.catalog.ParameterSpec;
import dev.vortex.core.catalog.RequestBodySpec;
import dev.vortex.core.catalog.SchemaHint;
import dev.vortex.core.catalog.ServiceCatalog;
import dev.vortex.core.port.ServiceCatalogImporter;
import dev.vortex.core.shared.OperationId;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns an OpenAPI document into a Vortex service catalog.
 *
 * <p>Onboarding starts here, and it is deliberately, entirely deterministic. An OpenAPI document
 * states its paths, methods, parameters and schemas unambiguously; parsing it is ordinary software
 * work. Handing that job to a language model would introduce variability into the one part of
 * onboarding that has none, and would make a re-import capable of producing a different inventory
 * from the same file.
 *
 * <p>What the importer deliberately does not produce is any opinion about the operations it found:
 * which ones matter, how much traffic each receives, or whether its generated request data is fit to
 * send. Those are decisions, they belong in the project's configuration, and keeping them out of the
 * catalog is what lets a specification be re-imported without destroying them.
 *
 * <h2>Containment</h2>
 * Swagger's model classes never leave this class. That is what makes the parser replaceable: if it
 * proves awkward under native compilation, or a better one appears, only this file changes.
 *
 * <h2>Untrusted input</h2>
 * A specification may come from anywhere. Free text is length-capped and stripped of control
 * characters, and nothing from a document is ever given a privileged position — see
 * {@link UntrustedText} and {@code docs/02-architecture/security.adoc}.
 */
public final class OpenApiCatalogImporter implements ServiceCatalogImporter {

    private static final Logger log = LoggerFactory.getLogger(OpenApiCatalogImporter.class);

    /** A document beyond this size is rejected rather than parsed. */
    public static final int MAX_DOCUMENT_BYTES = 8 * 1024 * 1024;

    /** More operations than any real service has; a defence against a pathological document. */
    public static final int MAX_OPERATIONS = 2000;

    /** Deep enough for real payloads, shallow enough that a recursive schema cannot run away. */
    private static final int MAX_HINT_DEPTH = 6;

    /** Bounded for the same reason a dataset's column count is: a selector of thousands helps nobody. */
    private static final int MAX_HINTS = 500;

    private final SchemaSampleGenerator sampleGenerator = new SchemaSampleGenerator();
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock;

    public OpenApiCatalogImporter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public boolean supports(String sourceRef) {
        if (sourceRef == null) {
            return false;
        }
        String lower = sourceRef.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".json")
                || lower.contains("openapi") || lower.contains("swagger");
    }

    @Override
    public ServiceCatalog importFrom(String sourceRef, String content) {
        if (content == null || content.isBlank()) {
            throw new ImportException("The API description was empty.",
                    List.of("Vortex received no content from " + sourceRef + ".",
                            "Check that the file exists, or that the URL returns the document rather "
                                    + "than a login page."));
        }
        if (content.length() > MAX_DOCUMENT_BYTES) {
            throw new ImportException("The API description is too large to import.",
                    List.of("Vortex accepts documents up to "
                            + (MAX_DOCUMENT_BYTES / (1024 * 1024)) + " MB; this one is "
                            + (content.length() / (1024 * 1024)) + " MB.",
                            "If this is a legitimate specification, consider importing only the "
                                    + "portion describing the service you are testing."));
        }

        ParseOptions options = new ParseOptions();
        // Resolving references produces a flat, self-contained model, so nothing downstream has to
        // understand $ref. Vortex does not follow remote references: a specification that pulls in
        // arbitrary URLs at import time would turn onboarding into a request-forgery surface.
        options.setResolve(true);
        options.setResolveFully(true);
        options.setResolveRequestBody(true);

        SwaggerParseResult result;
        try {
            result = new OpenAPIV3Parser().readContents(content, null, options);
        } catch (RuntimeException e) {
            throw new ImportException(
                    "Vortex could not parse this API description.",
                    List.of("The parser reported: " + e.getMessage(),
                            "Check that the file is a valid OpenAPI 3.x document. Vortex does not "
                                    + "support Swagger 2.0; convert it first if that is what you have."));
        }

        OpenAPI api = result == null ? null : result.getOpenAPI();
        if (api == null) {
            List<String> problems = new ArrayList<>();
            problems.add("The document could not be read as OpenAPI 3.x.");
            if (result != null && result.getMessages() != null) {
                result.getMessages().stream().limit(10)
                        .forEach(message -> problems.add(UntrustedText.clean(message, 300)));
            }
            problems.add("If this is a Swagger 2.0 document, convert it to OpenAPI 3 first.");
            throw new ImportException("Vortex could not parse this API description.", problems);
        }

        List<String> warnings = new ArrayList<>();
        if (result.getMessages() != null && !result.getMessages().isEmpty()) {
            result.getMessages().stream().limit(20)
                    .forEach(message -> warnings.add(UntrustedText.clean(message, 300)));
        }

        List<Operation> operations = extractOperations(api, warnings);
        if (operations.isEmpty()) {
            throw new ImportException("No operations were found in this API description.",
                    List.of("Vortex parsed the document but it declared no paths.",
                            "Check that you imported the specification itself rather than an index "
                                    + "or a documentation page."));
        }

        String title = api.getInfo() == null ? "" : UntrustedText.summary(api.getInfo().getTitle());
        String version = api.getInfo() == null ? "" : UntrustedText.clean(api.getInfo().getVersion(), 40);

        log.info("Imported {} operations from {}", operations.size(), sourceRef);

        return new ServiceCatalog(CatalogSource.OPENAPI, sourceRef, title, version,
                Instant.now(clock), operations, warnings);
    }

    private List<Operation> extractOperations(OpenAPI api, List<String> warnings) {
        List<Operation> operations = new ArrayList<>();
        if (api.getPaths() == null) {
            return operations;
        }
        Set<String> usedIds = new LinkedHashSet<>();

        for (Map.Entry<String, PathItem> path : api.getPaths().entrySet()) {
            PathItem item = path.getValue();
            if (item == null) {
                continue;
            }
            for (Map.Entry<PathItem.HttpMethod, io.swagger.v3.oas.models.Operation> entry
                    : item.readOperationsMap().entrySet()) {

                if (operations.size() >= MAX_OPERATIONS) {
                    warnings.add("This document declares more than " + MAX_OPERATIONS
                            + " operations; the remainder were not imported.");
                    return operations;
                }

                HttpMethod method = mapMethod(entry.getKey());
                if (method == null) {
                    continue;
                }
                operations.add(toOperation(path.getKey(), method, entry.getValue(), item, usedIds));
            }
        }
        return operations;
    }

    private Operation toOperation(String path, HttpMethod method,
            io.swagger.v3.oas.models.Operation source, PathItem pathItem, Set<String> usedIds) {

        String specOperationId = source.getOperationId() == null ? ""
                : UntrustedText.clean(source.getOperationId(), 120);

        OperationId id = uniqueId(method, path, specOperationId, usedIds);

        List<ParameterSpec> parameters = new ArrayList<>();
        addParameters(pathItem.getParameters(), parameters);
        addParameters(source.getParameters(), parameters);

        RequestBodySpec body = bodyFor(source.getRequestBody());

        List<String> tags = source.getTags() == null ? List.of()
                : source.getTags().stream().map(tag -> UntrustedText.clean(tag, 60)).toList();

        String summary = source.getSummary() != null
                ? UntrustedText.summary(source.getSummary())
                : UntrustedText.summary(source.getDescription());

        // Whether this operation needs human review before it can run follows from its method —
        // see Operation.requiresReview(). It is not stored here, because the answer to "has someone
        // reviewed it?" is a decision that belongs in the project's configuration, where it survives
        // the next re-import of this document.
        return new Operation(id, specOperationId, method, path, summary, tags, parameters, body);
    }

    /**
     * Derives a stable, unique identifier for an operation.
     *
     * <p>The specification's own {@code operationId} is used when present, because it is stable
     * across re-imports and meaningful to the people who wrote it. When it is absent or duplicated —
     * both common in practice — the method and path provide a deterministic fallback, so workloads
     * and bindings built against a catalog survive re-importing the same document.
     */
    private OperationId uniqueId(HttpMethod method, String path, String specOperationId,
            Set<String> usedIds) {
        String candidate = specOperationId.isBlank()
                ? slug(method + "_" + path)
                : slug(specOperationId);

        String unique = candidate;
        int suffix = 2;
        while (!usedIds.add(unique)) {
            unique = candidate + "_" + suffix++;
        }
        return OperationId.of(unique);
    }

    private String slug(String raw) {
        StringBuilder slug = new StringBuilder();
        for (char c : raw.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                slug.append(c);
            } else if (!slug.isEmpty() && slug.charAt(slug.length() - 1) != '_') {
                slug.append('_');
            }
        }
        while (!slug.isEmpty() && slug.charAt(slug.length() - 1) == '_') {
            slug.setLength(slug.length() - 1);
        }
        if (slug.isEmpty()) {
            slug.append("operation");
        }
        if (slug.length() > 100) {
            slug.setLength(100);
        }
        return slug.toString();
    }

    private void addParameters(List<Parameter> source, List<ParameterSpec> target) {
        if (source == null) {
            return;
        }
        for (Parameter parameter : source) {
            if (parameter == null || parameter.getName() == null) {
                continue;
            }
            ParameterLocation location = mapLocation(parameter.getIn());
            if (location == null) {
                continue;
            }
            if (target.stream().anyMatch(existing -> existing.name().equals(parameter.getName())
                    && existing.location() == location)) {
                // An operation-level parameter overrides the path-level one of the same name.
                continue;
            }
            target.add(new ParameterSpec(
                    UntrustedText.clean(parameter.getName(), 80),
                    location,
                    Boolean.TRUE.equals(parameter.getRequired()) || location == ParameterLocation.PATH,
                    parameter.getSchema() == null ? null : parameter.getSchema().getType(),
                    exampleFor(parameter),
                    formatOf(parameter.getSchema()),
                    enumOf(parameter.getSchema())));
        }
    }

    private String exampleFor(Parameter parameter) {
        if (parameter.getExample() != null) {
            return UntrustedText.clean(String.valueOf(parameter.getExample()), 200);
        }
        if (parameter.getSchema() != null) {
            Object sample = sampleGenerator.sampleFor(parameter.getSchema());
            if (sample != null && !(sample instanceof Map) && !(sample instanceof List)) {
                return UntrustedText.clean(String.valueOf(sample), 200);
            }
        }
        return null;
    }

    private RequestBodySpec bodyFor(RequestBody requestBody) {
        if (requestBody == null || requestBody.getContent() == null) {
            return null;
        }

        // JSON where available; otherwise whatever the specification declares first, so a
        // form-encoded or text endpoint is still importable.
        Map.Entry<String, MediaType> chosen = requestBody.getContent().entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).contains("json"))
                .findFirst()
                .orElseGet(() -> requestBody.getContent().entrySet().stream().findFirst().orElse(null));

        if (chosen == null || chosen.getValue() == null) {
            return null;
        }

        Object sample = chosen.getValue().getExample() != null
                ? chosen.getValue().getExample()
                : sampleGenerator.sampleFor(chosen.getValue().getSchema());

        if (sample == null) {
            return null;
        }

        try {
            return RequestBodySpec.schemaGenerated(chosen.getKey(), json.writeValueAsString(sample),
                    fieldHints(chosen.getValue().getSchema()));
        } catch (JsonProcessingException e) {
            log.debug("Could not serialise a generated sample body: {}", e.getMessage());
            return null;
        }
    }

    /**
     * What the body schema says about each field's shape.
     *
     * <p>Top-level and nested object properties, addressed the way Vortex's own body field mapping
     * addresses them — by name, joined with dots. Arrays are not descended into, because the mapping
     * grammar has no way to name an element and a hint nothing can act on is noise.
     */
    private List<SchemaHint> fieldHints(Schema<?> schema) {
        List<SchemaHint> hints = new ArrayList<>();
        collectHints(schema, "", hints, 0, java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>()));
        return hints;
    }

    private void collectHints(Schema<?> schema, String prefix, List<SchemaHint> hints, int depth,
            Set<Schema<?>> seen) {

        if (schema == null || depth > MAX_HINT_DEPTH || hints.size() >= MAX_HINTS
                || !seen.add(schema)) {
            return;
        }
        try {
            Map<String, Schema> properties = schema.getProperties();
            if (properties == null) {
                return;
            }
            for (Map.Entry<String, Schema> property : properties.entrySet()) {
                if (hints.size() >= MAX_HINTS) {
                    return;
                }
                Schema<?> value = property.getValue();
                if (value == null) {
                    continue;
                }
                String name = UntrustedText.clean(property.getKey(), 80);
                if (name.isBlank() || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    // Outside the body field grammar, so nothing could be bound to it anyway.
                    continue;
                }
                String path = prefix.isEmpty() ? name : prefix + "." + name;
                if (value.getProperties() != null && !value.getProperties().isEmpty()) {
                    collectHints(value, path, hints, depth + 1, seen);
                    continue;
                }
                hints.add(new SchemaHint(path, value.getType(), formatOf(value), enumOf(value)));
            }
        } finally {
            seen.remove(schema);
        }
    }

    private String formatOf(Schema<?> schema) {
        return schema == null || schema.getFormat() == null
                ? "" : UntrustedText.clean(schema.getFormat(), 40);
    }

    private List<String> enumOf(Schema<?> schema) {
        if (schema == null || schema.getEnum() == null || schema.getEnum().isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object value : schema.getEnum()) {
            if (value != null) {
                values.add(UntrustedText.clean(String.valueOf(value), 200));
            }
        }
        return values;
    }

    private HttpMethod mapMethod(PathItem.HttpMethod method) {
        return switch (method) {
            case GET -> HttpMethod.GET;
            case PUT -> HttpMethod.PUT;
            case POST -> HttpMethod.POST;
            case DELETE -> HttpMethod.DELETE;
            case PATCH -> HttpMethod.PATCH;
            case HEAD -> HttpMethod.HEAD;
            case OPTIONS -> HttpMethod.OPTIONS;
            case TRACE -> HttpMethod.TRACE;
        };
    }

    private ParameterLocation mapLocation(String in) {
        if (in == null) {
            return null;
        }
        return switch (in.toLowerCase(Locale.ROOT)) {
            case "path" -> ParameterLocation.PATH;
            case "query" -> ParameterLocation.QUERY;
            case "header" -> ParameterLocation.HEADER;
            case "cookie" -> ParameterLocation.COOKIE;
            default -> null;
        };
    }
}
