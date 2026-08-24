package com.acltabontabon.vortex.persistence.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.acltabontabon.vortex.core.capacity.ObservationSource;
import com.acltabontabon.vortex.core.capacity.OperationMixCoverage;
import com.acltabontabon.vortex.core.capacity.ProductionObservation;
import com.acltabontabon.vortex.core.catalog.ExpectedResponse;
import com.acltabontabon.vortex.core.catalog.OperationBinding;
import com.acltabontabon.vortex.core.data.BodyFieldPath;
import com.acltabontabon.vortex.core.data.RequestData;
import com.acltabontabon.vortex.core.data.RequestValue;
import com.acltabontabon.vortex.core.environment.DependencyMode;
import com.acltabontabon.vortex.core.environment.Environment;
import com.acltabontabon.vortex.core.environment.EnvironmentCapabilities;
import com.acltabontabon.vortex.core.environment.EnvironmentType;
import com.acltabontabon.vortex.core.environment.TargetUrl;
import com.acltabontabon.vortex.core.lab.LocalLabSettings;
import com.acltabontabon.vortex.core.metrics.ObservationProvenance;
import com.acltabontabon.vortex.core.target.ContainerPort;
import com.acltabontabon.vortex.core.target.CpuAllocation;
import com.acltabontabon.vortex.core.target.DockerComposeTarget;
import com.acltabontabon.vortex.core.target.DockerImageTarget;
import com.acltabontabon.vortex.core.target.ExecutionTarget;
import com.acltabontabon.vortex.core.target.ExternalEndpointTarget;
import com.acltabontabon.vortex.core.target.ImageReference;
import com.acltabontabon.vortex.core.target.MemoryAllocation;
import com.acltabontabon.vortex.core.target.ReadinessCheck;
import com.acltabontabon.vortex.core.target.ResourceEnvelopeRequest;
import com.acltabontabon.vortex.core.port.ConfigurationStore;
import com.acltabontabon.vortex.core.project.OpenApiSource;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.workload.Workload;
import com.acltabontabon.vortex.core.workload.TestType;
import com.acltabontabon.vortex.core.workload.WorkloadSource;
import com.acltabontabon.vortex.core.shared.Concurrency;
import com.acltabontabon.vortex.core.shared.EnvironmentId;
import com.acltabontabon.vortex.core.shared.OperationId;
import com.acltabontabon.vortex.core.shared.Percentile;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.shared.WorkloadId;
import com.acltabontabon.vortex.core.threshold.Durations;
import com.acltabontabon.vortex.core.threshold.ErrorRateThreshold;
import com.acltabontabon.vortex.core.threshold.LatencyThreshold;
import com.acltabontabon.vortex.core.threshold.Threshold;
import com.acltabontabon.vortex.core.threshold.ThresholdScope;
import com.acltabontabon.vortex.core.threshold.ThresholdSet;
import com.acltabontabon.vortex.core.workload.ConstantArrivalRateShape;
import com.acltabontabon.vortex.core.workload.ConstantConcurrencyShape;
import com.acltabontabon.vortex.core.workload.Observation;
import com.acltabontabon.vortex.core.workload.OperationMix;
import com.acltabontabon.vortex.core.workload.RampingArrivalRateShape;
import com.acltabontabon.vortex.core.workload.RampingConcurrencyShape;
import com.acltabontabon.vortex.core.workload.Stage;
import com.acltabontabon.vortex.core.workload.WeightedOperation;
import com.acltabontabon.vortex.core.workload.LoadShape;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and writes {@code vortex.yaml}, the portable definition of what to test.
 *
 * <p>This file is the source of truth for test intent, and it is meant to live in version control
 * next to the service it describes. That is not a stylistic preference: a performance definition
 * that exists only inside one person's installation of a UI cannot be reviewed in a pull request,
 * cannot be run from a pipeline, and cannot be trusted to still describe what was measured last
 * month.
 *
 * <h2>Written by hand, on purpose</h2>
 * Output is rendered rather than serialised, so the file carries comments explaining what each
 * section means and — importantly — the units. A configuration file is often the first place an
 * engineer reads about a tool, and {@code rate: 20} without "requests per second" beside it is
 * exactly the ambiguity this product exists to remove.
 *
 * <h2>What is never written here</h2>
 * Resolved secrets, allocated per-operation rates, k6 scenario keys, runner selection and safety
 * decisions. Those are products of resolving this file against an environment and a policy at run
 * time, and they belong to the effective plan for one execution — not to the user's intent.
 */
public final class YamlConfigurationStore implements ConfigurationStore {

    public static final String DIRECTORY = ".vortex";
    public static final String FILE_NAME = "vortex.yaml";

    /**
     * Read-tolerance for a file authored elsewhere with the alternate YAML extension. Vortex always
     * writes {@link #FILE_NAME}; this is only ever a fallback for {@link #load(String)}.
     */
    public static final String FILE_NAME_ALT = "vortex.yml";

    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @Override
    public LoadResult load(String workspacePath) {
        Path file = fileIn(workspacePath, FILE_NAME);
        if (!Files.isRegularFile(file)) {
            Path alternate = fileIn(workspacePath, FILE_NAME_ALT);
            if (!Files.isRegularFile(alternate)) {
                return LoadResult.missing(file.toString());
            }
            file = alternate;
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8), file.toString());
        } catch (IOException e) {
            return LoadResult.invalid(
                    List.of("Vortex could not read " + file + ": " + e.getMessage()),
                    file.toString());
        }
    }

    @Override
    public void save(String workspacePath, ProjectConfiguration configuration) {
        Path file = fileIn(workspacePath, FILE_NAME);
        try {
            Files.createDirectories(file.getParent());
            writeAtomically(file, render(configuration));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Vortex could not write " + file + ". Check that the project directory exists "
                            + "and is writable.", e);
        }
    }

    /**
     * Writes to a sibling temporary file, then renames it into place, so a crash mid-write leaves
     * either the old {@code vortex.yaml} intact or the new one complete — never a truncated file with
     * no signal that it never finished.
     */
    private static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    @Override
    public LoadResult parse(String content, String sourceLabel) {
        if (content == null || content.isBlank()) {
            return LoadResult.invalid(List.of(sourceLabel + " is empty."), sourceLabel);
        }

        JsonNode root;
        try {
            root = yaml.readTree(content);
        } catch (Exception e) {
            return LoadResult.invalid(
                    List.of("This file is not valid YAML: " + firstLine(e.getMessage())),
                    sourceLabel);
        }
        if (root == null || !root.isObject()) {
            return LoadResult.invalid(
                    List.of("The top level of " + sourceLabel + " must be a mapping of sections "
                            + "such as 'operations', 'workloads' and 'thresholds'."),
                    sourceLabel);
        }

        List<String> problems = new ArrayList<>();

        int version = root.path("version").asInt(ProjectConfiguration.CURRENT_VERSION);
        List<String> legacy = legacyProblems(root, version);
        if (!legacy.isEmpty()) {
            return LoadResult.invalid(legacy, sourceLabel);
        }
        if (version > ProjectConfiguration.CURRENT_VERSION) {
            problems.add("This file declares version " + version + ", but this build of Vortex "
                    + "understands up to version " + ProjectConfiguration.CURRENT_VERSION
                    + ". Upgrade Vortex, or edit the version field if you know the file is compatible.");
        }

        List<OperationBinding> bindings =
                collect(problems, () -> operations(root.path("operations")), List.of());
        List<Environment> environments =
                collect(problems, () -> environments(root.path("environments")), List.of());
        ThresholdSet thresholds = collect(problems,
                () -> thresholds(root.path("thresholds"), "thresholds"), ThresholdSet.empty());
        List<Workload> workloads =
                collect(problems, () -> workloads(root.path("workloads")), List.of());
        ProductionObservation production =
                collect(problems, () -> production(root.path("production")), null);
        ObservationSource observationSource =
                collect(problems, () -> observationSource(root.path("observation")), null);
        LocalLabSettings localLab =
                collect(problems, () -> localLab(root.path("lab")), null);
        OpenApiSource openApiSource =
                collect(problems, () -> openApiSource(root.path("service").path("openapi")), null);

        if (!problems.isEmpty()) {
            return LoadResult.invalid(problems, sourceLabel);
        }

        try {
            return LoadResult.loaded(new ProjectConfiguration(
                    ProjectConfiguration.CURRENT_VERSION,
                    root.path("service").path("name").asText(""),
                    root.path("service").path("description").asText(""),
                    root.path("service").path("version").asText(""),
                    bindings, environments, workloads, thresholds, production, observationSource,
                    localLab, openApiSource), sourceLabel);
        } catch (IllegalArgumentException e) {
            return LoadResult.invalid(List.of(e.getMessage()), sourceLabel);
        }
    }

    /**
     * Reads where this service's API description lives.
     *
     * <p>A file and a URL are different claims — see {@link OpenApiSource} — so a node naming both,
     * or naming neither while still being present, is refused rather than guessed at.
     */
    private OpenApiSource openApiSource(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        boolean hasFile = node.hasNonNull("file");
        boolean hasUrl = node.hasNonNull("url");
        if (hasFile == hasUrl) {
            throw new ConfigText.ConfigProblem("service.openapi",
                    hasFile ? "must name only one of 'file' or 'url', not both"
                            : "must name either 'file' or 'url'",
                    "for example:\n  service:\n    openapi:\n      file: openapi/checkout.yaml\n"
                            + "or:\n  service:\n    openapi:\n      url: https://example.com/openapi.yaml");
        }
        try {
            return hasFile
                    ? new OpenApiSource.File(node.path("file").asText(""))
                    : new OpenApiSource.Url(node.path("url").asText(""));
        } catch (IllegalArgumentException e) {
            throw new ConfigText.ConfigProblem(
                    "service.openapi." + (hasFile ? "file" : "url"), e.getMessage(), "");
        }
    }

    /**
     * Detects a configuration written for a vocabulary Vortex no longer speaks, and explains the edit.
     *
     * <p>Refused rather than migrated. Two earlier shapes exist in the wild: one described journeys
     * and a project-wide traffic mix, the other called a workload a {@code scenario}. The first
     * cannot be migrated safely — a multi-step journey would force Vortex to choose between
     * discarding the sequence and reinterpreting an ordered flow as a concurrent mix, and those mean
     * different things. The second could in principle be renamed automatically, but a file that
     * still says {@code scenario} is a file whose author is thinking in the old model, and quietly
     * rewriting it would leave them reading one vocabulary in the UI and another in their editor.
     * Both are rejected with the edit spelled out.
     */
    private List<String> legacyProblems(JsonNode root, int version) {
        if (root.has("journeys") || root.has("traffic")) {
            return List.of(
                    "This file describes journeys and a project-wide traffic mix. Vortex models "
                            + "operations and workloads directly against one service. See "
                            + "docs/adr/adr-024-service-level-workload-modelling.adoc.",
                    "Replace 'journeys:' and 'traffic:' with an operation mix on each workload. A "
                            + "journey of one step becomes that operation; a journey of several steps "
                            + "was an ordered batch, and you need to decide whether those operations "
                            + "should now run as a concurrent mix (usually yes, for service capacity) "
                            + "or as separate workloads.",
                    "Rates are now requests per second rather than journey arrivals per second. If a "
                            + "journey issued three requests, its old rate of 40 corresponds to "
                            + "roughly 120 requests per second.",
                    "Set 'version: " + ProjectConfiguration.CURRENT_VERSION
                            + "' once the file has been converted.");
        }
        if (root.has("scenarios")) {
            return List.of(
                    "This file uses 'scenarios:'. Vortex calls these workloads: a workload is a "
                            + "reusable traffic condition applied to the whole service, and calling "
                            + "it a scenario invited confusion both with a business flow and with "
                            + "k6's own 'scenarios' block.",
                    "Rename 'scenarios:' to 'workloads:'.",
                    "Inside each workload, rename the 'workload:' block to 'shape:' — it describes "
                            + "how traffic is produced: "
                            + "shape: { model: arrival-rate, rate: 120, duration: 30m }.",
                    "Set 'version: " + ProjectConfiguration.CURRENT_VERSION
                            + "' once the file has been converted.");
        }
        return List.of();
    }

    private <T> T collect(List<String> problems, java.util.function.Supplier<T> section, T fallback) {
        try {
            return section.get();
        } catch (ConfigText.ConfigProblem | IllegalArgumentException e) {
            problems.add(e.getMessage());
            return fallback;
        }
    }

    // ---------------------------------------------------------------- parsing

    private List<OperationBinding> operations(JsonNode node) {
        List<OperationBinding> bindings = new ArrayList<>();
        if (node.isMissingNode() || node.isNull()) {
            return bindings;
        }
        if (!node.isObject()) {
            throw new ConfigText.ConfigProblem("operations",
                    "must be a mapping of operation identifiers",
                    "for example:\n  operations:\n    createOrder:\n      reviewed: true");
        }

        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String name = entry.getKey();
            JsonNode binding = entry.getValue();
            String field = "operations." + name;

            OperationId operationId;
            try {
                operationId = OperationId.of(name);
            } catch (IllegalArgumentException e) {
                throw new ConfigText.ConfigProblem(field, e.getMessage(),
                        "operation keys must match the identifiers in your imported API description");
            }

            bindings.add(new OperationBinding(
                    operationId,
                    new RequestData(
                            RequestValueYaml.valueMap(binding.path("pathValues"),
                                    field + ".pathValues"),
                            RequestValueYaml.valueMap(binding.path("queryValues"),
                                    field + ".queryValues"),
                            RequestValueYaml.valueMap(binding.path("headers"), field + ".headers"),
                            binding.path("body").asText(""),
                            RequestValueYaml.bodyValueMap(binding.path("bodyValues"),
                                    field + ".bodyValues")),
                    expectation(binding.path("expect"), field + ".expect"),
                    binding.path("reviewed").asBoolean(false)));
        }
        return bindings;
    }

    private ExpectedResponse expectation(JsonNode node, String field) {
        if (node.isMissingNode() || node.isNull()) {
            return ExpectedResponse.DEFAULT;
        }
        JsonNode statuses = node.path("status");
        if (statuses.isMissingNode() || statuses.isNull()) {
            return ExpectedResponse.DEFAULT;
        }
        List<Integer> values = new ArrayList<>();
        if (statuses.isArray()) {
            statuses.forEach(status -> values.add(status.asInt()));
        } else {
            values.add(statuses.asInt());
        }
        try {
            return new ExpectedResponse(values);
        } catch (IllegalArgumentException e) {
            throw new ConfigText.ConfigProblem(field + ".status", e.getMessage(),
                    "for example: expect: { status: [200, 201] }");
        }
    }

    private List<Environment> environments(JsonNode node) {
        List<Environment> environments = new ArrayList<>();
        if (node.isMissingNode() || node.isNull()) {
            return environments;
        }

        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String name = entry.getKey();
            JsonNode environment = entry.getValue();
            String baseUrlField = "environments." + name + ".baseUrl";
            String targetField = "environments." + name + ".target";

            String baseUrl = environment.path("baseUrl").asText("");
            ExecutionTarget target = target(environment.path("target"), baseUrl, baseUrlField, targetField);

            EnvironmentType type = enumValue(EnvironmentType.class,
                    environment.path("type").asText("LOCAL_ISOLATED"),
                    "environments." + name + ".type");
            DependencyMode dependencies = enumValue(DependencyMode.class,
                    environment.path("dependencies").asText("UNKNOWN"),
                    "environments." + name + ".dependencies");

            JsonNode capabilities = environment.path("capabilities");
            EnvironmentCapabilities environmentCapabilities = new EnvironmentCapabilities(
                    capabilities.path("usesMockDependencies").asBoolean(
                            type == EnvironmentType.LOCAL_ISOLATED),
                    capabilities.path("usesLocalStack").asBoolean(false),
                    capabilities.path("productionLikeInfrastructure").asBoolean(false),
                    capabilities.path("sharedEnvironment").asBoolean(
                            type != EnvironmentType.LOCAL_ISOLATED),
                    capabilities.path("distributedExecutionAllowed").asBoolean(false));

            try {
                environments.add(new Environment(EnvironmentId.of(name), name, type,
                        target, environmentCapabilities,
                        dependencies, stringMap(environment.path("headers"))));
            } catch (IllegalArgumentException e) {
                throw new ConfigText.ConfigProblem("environments." + name, e.getMessage(), "");
            }
        }
        return environments;
    }

    /**
     * Reads what this environment tests, and how Vortex reaches or controls it.
     *
     * <p>Absent {@code target:} is the ordinary case — every file written before Docker/Compose
     * targets existed has no such block, and reads exactly as it always did: an external endpoint at
     * {@code baseUrl}. An explicit {@code kind: EXTERNAL_ENDPOINT} reads identically, for a file that
     * spells it out. {@code baseUrl} is required only for that case — a Vortex-managed or attached
     * container has no meaningful pre-run address to demand one for.
     */
    private ExecutionTarget target(JsonNode node, String baseUrl, String baseUrlField, String targetField) {
        if (node.isMissingNode() || node.isNull()) {
            return externalEndpointTarget(baseUrl, baseUrlField);
        }
        if (!node.isObject()) {
            throw new ConfigText.ConfigProblem(targetField, "must be a mapping",
                    "for example:\n  target:\n    kind: DOCKER_IMAGE\n"
                            + "    image: \"payment-service:1.4.2\"\n    containerPort: 8080");
        }
        TargetKind kind = enumValue(TargetKind.class,
                node.path("kind").asText(TargetKind.EXTERNAL_ENDPOINT.name()), targetField + ".kind");
        return switch (kind) {
            case EXTERNAL_ENDPOINT -> externalEndpointTarget(baseUrl, baseUrlField);
            case DOCKER_IMAGE -> dockerImageTarget(node, targetField);
            case DOCKER_COMPOSE -> dockerComposeTarget(node, targetField);
        };
    }

    private ExternalEndpointTarget externalEndpointTarget(String baseUrl, String field) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ConfigText.ConfigProblem(field, "must be set", "for example http://localhost:8080");
        }
        try {
            return new ExternalEndpointTarget(TargetUrl.of(baseUrl));
        } catch (IllegalArgumentException e) {
            throw new ConfigText.ConfigProblem(field, e.getMessage(), "");
        }
    }

    private DockerImageTarget dockerImageTarget(JsonNode node, String field) {
        String image = node.path("image").asText("").trim();
        if (image.isEmpty()) {
            throw new ConfigText.ConfigProblem(field + ".image", "must be set",
                    "the image Vortex should run, e.g. \"payment-service:1.4.2\"");
        }
        if (!node.hasNonNull("containerPort")) {
            throw new ConfigText.ConfigProblem(field + ".containerPort", "must be set",
                    "the port the container listens on, e.g. 8080");
        }
        ResourceEnvelopeRequest resources = resourceEnvelope(node, field);
        ReadinessCheck readiness = readinessCheck(node, field);
        try {
            return new DockerImageTarget(new ImageReference(image),
                    new ContainerPort(node.path("containerPort").asInt()), resources, readiness);
        } catch (IllegalArgumentException e) {
            throw new ConfigText.ConfigProblem(field, e.getMessage(), "");
        }
    }

    private ResourceEnvelopeRequest resourceEnvelope(JsonNode node, String field) {
        CpuAllocation cpu = null;
        if (node.hasNonNull("cpuMillicores")) {
            try {
                cpu = CpuAllocation.ofMillicores(node.path("cpuMillicores").asInt());
            } catch (IllegalArgumentException e) {
                throw new ConfigText.ConfigProblem(field + ".cpuMillicores", e.getMessage(), "");
            }
        }
        MemoryAllocation memory = null;
        if (node.hasNonNull("memoryMebibytes")) {
            try {
                memory = MemoryAllocation.ofMebibytes(node.path("memoryMebibytes").asLong());
            } catch (IllegalArgumentException e) {
                throw new ConfigText.ConfigProblem(field + ".memoryMebibytes", e.getMessage(), "");
            }
        }
        return new ResourceEnvelopeRequest(cpu, memory);
    }

    /** All three readiness fields, or none — a partial readiness block cannot be checked. */
    private ReadinessCheck readinessCheck(JsonNode node, String field) {
        boolean hasPath = node.hasNonNull("readinessPath");
        boolean hasStatus = node.hasNonNull("readinessExpectedStatus");
        boolean hasTimeout = node.hasNonNull("readinessTimeoutSeconds");
        if (!hasPath && !hasStatus && !hasTimeout) {
            return null;
        }
        if (!(hasPath && hasStatus && hasTimeout)) {
            throw new ConfigText.ConfigProblem(field,
                    "a readiness check needs readinessPath, readinessExpectedStatus and "
                            + "readinessTimeoutSeconds together, not just some of them",
                    "set all three, or none to fall back to a plain TCP connect once the port opens");
        }
        try {
            return new ReadinessCheck(node.path("readinessPath").asText(),
                    node.path("readinessExpectedStatus").asInt(),
                    Duration.ofSeconds(node.path("readinessTimeoutSeconds").asInt()));
        } catch (IllegalArgumentException e) {
            throw new ConfigText.ConfigProblem(field, e.getMessage(), "");
        }
    }

    private DockerComposeTarget dockerComposeTarget(JsonNode node, String field) {
        String composeFile = node.path("composeFile").asText("").trim();
        if (composeFile.isEmpty()) {
            throw new ConfigText.ConfigProblem(field + ".composeFile", "must be set",
                    "the Compose file this repository already owns, relative to it, e.g. compose.yaml");
        }
        String service = node.path("service").asText("").trim();
        if (service.isEmpty()) {
            throw new ConfigText.ConfigProblem(field + ".service", "must be set",
                    "the service name inside that Compose file");
        }
        if (!node.hasNonNull("containerPort")) {
            throw new ConfigText.ConfigProblem(field + ".containerPort", "must be set",
                    "the port that service listens on inside its container, e.g. 8080");
        }
        try {
            return new DockerComposeTarget(composeFile, service,
                    new ContainerPort(node.path("containerPort").asInt()));
        } catch (IllegalArgumentException e) {
            throw new ConfigText.ConfigProblem(field, e.getMessage(), "");
        }
    }

    /** Wire vocabulary for {@code target.kind} — identical to {@code EnvironmentRequest.targetKind}
     *  in {@code ConfigurationApiController}, so there is one mental model, not two. */
    private enum TargetKind {
        EXTERNAL_ENDPOINT, DOCKER_IMAGE, DOCKER_COMPOSE
    }

    private List<Workload> workloads(JsonNode node) {
        List<Workload> workloads = new ArrayList<>();
        if (node.isMissingNode() || node.isNull()) {
            return workloads;
        }
        if (!node.isObject()) {
            throw new ConfigText.ConfigProblem("workloads", "must be a mapping of workload names",
                    "for example:\n  workloads:\n    production-peak:\n      type: STRESS");
        }

        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            String name = entry.getKey();
            JsonNode workloadNode = entry.getValue();
            String field = "workloads." + name;

            TestType type = enumValue(TestType.class,
                    workloadNode.path("type").asText(TestType.AVERAGE_LOAD.name()), field + ".type");
            OperationMix mix = operationMix(workloadNode.path("operations"), field + ".operations");
            LoadShape shape = loadShape(workloadNode.path("shape"), field + ".shape");

            try {
                workloads.add(new Workload(
                        WorkloadId.of(name),
                        name,
                        workloadNode.path("description").asText(""),
                        workloadNode.path("objective").asText(""),
                        type,
                        mix,
                        shape,
                        thresholds(workloadNode.path("thresholds"), field + ".thresholds"),
                        source(workloadNode.path("source"), field + ".source"),
                        stringMap(workloadNode.path("k6"))));
            } catch (IllegalArgumentException e) {
                throw new ConfigText.ConfigProblem(field, e.getMessage(), "");
            }
        }
        return workloads;
    }

    private OperationMix operationMix(JsonNode node, String field) {
        if (!node.isObject() || node.isEmpty()) {
            throw new ConfigText.ConfigProblem(field,
                    "must name at least one operation and its share",
                    "a single operation is a complete performance target — for example:\n"
                            + "  operations: { createOrder: 100 }\n"
                            + "weights are relative, so 15/25/55/5 and 3/5/11/1 mean the same thing");
        }

        List<WeightedOperation> entries = new ArrayList<>();
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            int weight = entry.getValue().asInt(0);
            if (weight <= 0) {
                throw new ConfigText.ConfigProblem(field + "." + entry.getKey(),
                        "must be greater than 0 but was " + entry.getValue().asText(),
                        "an operation with no share is not part of the workload — remove it instead");
            }
            try {
                entries.add(WeightedOperation.of(OperationId.of(entry.getKey()), weight));
            } catch (IllegalArgumentException e) {
                throw new ConfigText.ConfigProblem(field + "." + entry.getKey(), e.getMessage(),
                        "operation keys must match the identifiers in your imported API description");
            }
        }
        return OperationMix.of(entries);
    }

    private LoadShape loadShape(JsonNode node, String field) {
        if (!node.isObject()) {
            throw new ConfigText.ConfigProblem(field, "must be set",
                    "for example:\n  workload: { model: arrival-rate, rate: 120, duration: 30m }");
        }

        String model = node.path("model").asText("arrival-rate").trim().toLowerCase(Locale.ROOT);
        boolean open = switch (model) {
            case "arrival-rate", "arrival_rate", "arrivalrate", "open" -> true;
            case "concurrency", "vus", "closed" -> false;
            default -> throw new ConfigText.ConfigProblem(field + ".model",
                    "has an unrecognised value: '" + node.path("model").asText() + "'",
                    "use 'arrival-rate' to offer a fixed number of requests per second regardless of "
                            + "how the service responds, or 'concurrency' to hold a fixed number of "
                            + "virtual users each waiting for its previous request");
        };

        JsonNode stages = node.path("stages");
        boolean ramping = stages.isArray() && !stages.isEmpty();

        if (open) {
            if (!ramping) {
                return new ConstantArrivalRateShape(
                        RequestsPerSecond.of(ConfigText.rate(field + ".rate",
                                node.hasNonNull("rate") ? node.path("rate").asDouble() : null)),
                        ConfigText.duration(field + ".duration", node.path("duration").asText()));
            }
            List<Stage> parsed = new ArrayList<>();
            int index = 0;
            for (JsonNode stage : stages) {
                String stageField = field + ".stages[" + index++ + "]";
                parsed.add(new Stage(
                        RequestsPerSecond.of(ConfigText.rate(stageField + ".target",
                                stage.hasNonNull("target") ? stage.path("target").asDouble() : null)),
                        ConfigText.duration(stageField + ".duration", stage.path("duration").asText())));
            }
            double startRate = node.hasNonNull("startRate")
                    ? ConfigText.rate(field + ".startRate", node.path("startRate").asDouble())
                    : parsed.getFirst().target().asDouble();
            return new RampingArrivalRateShape(RequestsPerSecond.of(startRate), parsed);
        }

        if (!ramping) {
            return new ConstantConcurrencyShape(
                    concurrency(node, "vus", field + ".vus"),
                    ConfigText.duration(field + ".duration", node.path("duration").asText()));
        }
        List<Stage> parsed = new ArrayList<>();
        int index = 0;
        for (JsonNode stage : stages) {
            String stageField = field + ".stages[" + index++ + "]";
            parsed.add(new Stage(
                    concurrency(stage, "target", stageField + ".target"),
                    ConfigText.duration(stageField + ".duration", stage.path("duration").asText())));
        }
        Concurrency startVus = node.hasNonNull("startVUs")
                ? concurrency(node, "startVUs", field + ".startVUs")
                : (Concurrency) parsed.getFirst().target();
        return new RampingConcurrencyShape(startVus, parsed);
    }

    private Concurrency concurrency(JsonNode node, String property, String field) {
        if (!node.hasNonNull(property)) {
            throw new ConfigText.ConfigProblem(field, "must be set",
                    "the number of concurrent virtual users, for example 50");
        }
        try {
            return Concurrency.of(node.path(property).asInt(0));
        } catch (IllegalArgumentException e) {
            throw new ConfigText.ConfigProblem(field, e.getMessage(), "");
        }
    }

    private WorkloadSource source(JsonNode node, String field) {
        if (!node.isObject()) {
            return WorkloadSource.manual();
        }
        WorkloadSource.SourceKind kind = enumValue(WorkloadSource.SourceKind.class,
                node.path("kind").asText(WorkloadSource.SourceKind.MANUAL.name()), field + ".kind");
        return new WorkloadSource(kind, node.path("detail").asText(""),
                observation(node.path("observed"), field + ".observed"),
                node.path("derivation").asText(""));
    }

    /**
     * Reads when an observation was taken.
     *
     * <p>Accepts a window ({@code from}/{@code to}) or a single reading ({@code at}), because those
     * are different claims and the file should be able to make either. Absent stays absent rather
     * than becoming a placeholder date.
     */
    private Observation observation(JsonNode node, String field) {
        if (!node.isObject()) {
            return Observation.unknown();
        }
        Instant at = instant(node, "at", field);
        if (at != null) {
            return Observation.at(at);
        }
        Instant from = instant(node, "from", field);
        Instant to = instant(node, "to", field);
        if (from == null && to == null) {
            return Observation.unknown();
        }
        if (from == null) {
            throw new ConfigText.ConfigProblem(field,
                    "has an end but no beginning, which is not a window",
                    "give both: from: 2026-08-18T20:00:00Z / to: 2026-08-18T21:00:00Z");
        }
        if (to == null) {
            return Observation.at(from);
        }
        try {
            return Observation.over(from, to);
        } catch (IllegalArgumentException e) {
            throw new ConfigText.ConfigProblem(field, e.getMessage(), "");
        }
    }

    private Instant instant(JsonNode node, String property, String field) {
        if (!node.hasNonNull(property)) {
            return null;
        }
        String raw = node.path(property).asText();
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            throw new ConfigText.ConfigProblem(field + "." + property,
                    "is not an ISO-8601 instant: '" + raw + "'",
                    "for example 2026-08-01T09:00:00Z");
        }
    }

    private ThresholdSet thresholds(JsonNode node, String field) {
        if (node.isMissingNode() || node.isNull()) {
            return ThresholdSet.empty();
        }
        List<Threshold> thresholds = new ArrayList<>(
                scopedThresholds(node, ThresholdScope.OVERALL, field));

        JsonNode perOperation = node.path("perOperation");
        if (perOperation.isObject()) {
            for (Map.Entry<String, JsonNode> entry : perOperation.properties()) {
                OperationId operationId;
                try {
                    operationId = OperationId.of(entry.getKey());
                } catch (IllegalArgumentException e) {
                    throw new ConfigText.ConfigProblem(
                            field + ".perOperation." + entry.getKey(), e.getMessage(), "");
                }
                thresholds.addAll(scopedThresholds(entry.getValue(), ThresholdScope.of(operationId),
                        field + ".perOperation." + entry.getKey()));
            }
        }

        return new ThresholdSet(thresholds);
    }

    private List<Threshold> scopedThresholds(JsonNode node, ThresholdScope scope, String field) {
        List<Threshold> thresholds = new ArrayList<>();

        JsonNode latency = node.path("latency");
        if (latency.isObject()) {
            for (Map.Entry<String, JsonNode> entry : latency.properties()) {
                String key = entry.getKey().trim().toLowerCase(Locale.ROOT);
                double percent;
                if (!key.startsWith("p")) {
                    throw new ConfigText.ConfigProblem(field + ".latency." + entry.getKey(),
                            "is not a percentile", "use p50, p95, p99 or similar");
                }
                try {
                    percent = Double.parseDouble(key.substring(1));
                } catch (NumberFormatException e) {
                    throw new ConfigText.ConfigProblem(field + ".latency." + entry.getKey(),
                            "is not a percentile", "use p50, p95, p99 or similar");
                }
                thresholds.add(new LatencyThreshold(scope, Percentile.of(percent),
                        ConfigText.duration(field + ".latency." + entry.getKey(),
                                entry.getValue().asText())));
            }
        }

        JsonNode errorRate = node.path("errorRate");
        if (errorRate.isObject() && errorRate.hasNonNull("maximum")) {
            thresholds.add(new ErrorRateThreshold(scope,
                    ConfigText.errorRate(field + ".errorRate.maximum",
                            errorRate.path("maximum").asText())));
        }
        return thresholds;
    }

    private ProductionObservation production(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.hasNonNull("observedPeak")) {
            throw new ConfigText.ConfigProblem("production.observedPeak", "must be set",
                    "the highest request rate your service actually receives — this is what makes a "
                            + "workload production-informed rather than invented");
        }
        OperationMix observedMix = node.path("observedMix").isObject()
                ? operationMix(node.path("observedMix"), "production.observedMix")
                : null;

        // observedP95 was the original spelling. It is still read, because files written by an
        // earlier build are committed in real repositories, but it is no longer written: on the same
        // page as a p95 latency objective, a bare "p95" is read as a latency by everyone.
        String p95Field = node.hasNonNull("observedP95Rate") ? "observedP95Rate"
                : node.hasNonNull("observedP95") ? "observedP95" : null;

        return new ProductionObservation(
                node.hasNonNull("observedAverage")
                        ? RequestsPerSecond.of(ConfigText.rate("production.observedAverage",
                        node.path("observedAverage").asDouble())) : null,
                p95Field == null ? null
                        : RequestsPerSecond.of(ConfigText.rate("production." + p95Field,
                        node.path(p95Field).asDouble())),
                RequestsPerSecond.of(ConfigText.rate("production.observedPeak",
                        node.path("observedPeak").asDouble())),
                observedMix,
                mixCoverage(node.path("mixCoverage")),
                node.hasNonNull("sampleResolution")
                        ? ConfigText.duration("production.sampleResolution",
                        node.path("sampleResolution").asText()) : null,
                node.path("source").asText(""),
                observation(node.path("observed"), "production.observed"),
                provenance(node.path("provenance")),
                node.path("note").asText(""));
    }

    /**
     * Reads how much of production the recorded mix accounts for.
     *
     * <p>Absent stays absent. A mix with no coverage recorded beside it is a mix nobody measured the
     * completeness of, which is a different claim from one measured at 100%.
     */
    private OperationMixCoverage mixCoverage(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        if (!node.hasNonNull("observedRequests") || !node.hasNonNull("matchedRequests")) {
            throw new ConfigText.ConfigProblem("production.mixCoverage",
                    "needs both observedRequests and matchedRequests",
                    "a coverage figure without both numbers cannot be checked");
        }
        try {
            return new OperationMixCoverage(node.path("observedRequests").asLong(),
                    node.path("matchedRequests").asLong());
        } catch (IllegalArgumentException e) {
            throw new ConfigText.ConfigProblem("production.mixCoverage", e.getMessage(), "");
        }
    }

    /** Reads how a fetched observation was obtained, so the reader can go and check it. */
    private ObservationProvenance provenance(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        return new ObservationProvenance(
                node.path("provider").asText(""),
                node.path("query").asText(""),
                node.path("entity").asText(""),
                node.path("url").asText(""));
    }

    /**
     * Reads which monitoring system can be asked about production traffic.
     *
     * <p>Every rejection names the field and says what a good value looks like, because the person
     * editing this section is looking at their own Prometheus in another window and needs to know
     * which of the two disagrees with Vortex.
     */
    private ObservationSource observationSource(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String rawKind = node.path("source").asText("").trim();
        if (rawKind.isEmpty()) {
            throw new ConfigText.ConfigProblem("observation.source", "must be set",
                    "prometheus or dynatrace");
        }
        ObservationSource.Kind kind =
                enumValue(ObservationSource.Kind.class, rawKind, "observation.source");

        // Prometheus is asked about a label value, Dynatrace about an entity. Same field in the
        // domain, different words in the file, because using the wrong one is a mistake worth
        // catching here rather than in a 404 from the vendor.
        String identifier = kind == ObservationSource.Kind.DYNATRACE
                ? node.path("entity").asText(node.path("service").asText(""))
                : node.path("service").asText("");
        if (identifier.isBlank()) {
            throw new ConfigText.ConfigProblem(
                    "observation." + (kind == ObservationSource.Kind.DYNATRACE ? "entity" : "service"),
                    "must be set",
                    kind == ObservationSource.Kind.DYNATRACE
                            ? "the Dynatrace entity id, e.g. SERVICE-1A2B3C4D5E6F7890"
                            : "the value of the service label in Prometheus, e.g. checkout-service");
        }

        if (!node.hasNonNull("window")) {
            throw new ConfigText.ConfigProblem("observation.window", "must be set",
                    "how far back to look, e.g. 30d");
        }

        try {
            return new ObservationSource(kind, node.path("endpoint").asText(""), identifier,
                    ConfigText.duration("observation.window", node.path("window").asText()),
                    stringMap(node.path("headers")),
                    stringMap(node.path("labels")));
        } catch (IllegalArgumentException e) {
            throw new ConfigText.ConfigProblem("observation", e.getMessage(), "");
        }
    }

    /**
     * Reads which Compose file describes this service's local dependencies.
     *
     * <p>A missing section is the ordinary case, not an omission: a service with no dependencies
     * needs no lab at all.
     */
    private LocalLabSettings localLab(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String composeFile = node.path("compose").asText("").trim();
        if (composeFile.isEmpty()) {
            throw new ConfigText.ConfigProblem("lab.compose", "must be set",
                    "the Compose file this repository already owns, relative to it, e.g. compose.yaml");
        }
        try {
            return new LocalLabSettings(composeFile);
        } catch (IllegalArgumentException e) {
            throw new ConfigText.ConfigProblem("lab.compose", e.getMessage(), "");
        }
    }

    private Map<String, String> stringMap(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node.isObject()) {
            node.properties().forEach(
                    entry -> values.put(entry.getKey(), entry.getValue().asText("")));
        }
        return values;
    }

    private <E extends Enum<E>> E enumValue(Class<E> type, String raw, String field) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (E candidate : type.getEnumConstants()) {
            if (candidate.name().equals(value)) {
                return candidate;
            }
        }
        throw new ConfigText.ConfigProblem(field, "has an unrecognised value: '" + raw + "'",
                "valid values are " + java.util.Arrays.stream(type.getEnumConstants())
                        .map(Enum::name).toList());
    }

    private String firstLine(String message) {
        if (message == null) {
            return "unknown error";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    private Path fileIn(String workspacePath, String fileName) {
        Path base = Paths.get(workspacePath == null || workspacePath.isBlank() ? "." : workspacePath);
        return base.resolve(DIRECTORY).resolve(fileName);
    }

    // ---------------------------------------------------------------- rendering

    /**
     * Renders configuration as commented YAML.
     *
     * <p>Rendered by hand rather than serialised so the file can explain itself. Units in particular
     * are stated everywhere they appear, because "20" is meaningless and "20 requests per second" is
     * not — and because the one place a reader will look for what a number means is right beside it.
     */
    @Override
    public String render(ProjectConfiguration configuration) {
        StringBuilder out = new StringBuilder();

        out.append("""
                # Vortex performance definition.
                #
                # This file is the portable, reviewable description of what to test. Commit it
                # alongside the service it describes: a performance definition that exists only
                # inside one installation of a tool cannot be reviewed, shared, or run from CI.
                #
                # The model is: one service under test, its operations, and workloads that apply a
                # workload to some mix of them.
                #
                #   SUT        what service?        this file's project
                #   Operation  what interaction?    one request against it
                #   Workload   what workload?       how much load, in what shape, for how long
                #   Threshold  what is acceptable?  the objectives a run is judged against

                """);

        out.append("version: ").append(configuration.version()).append("\n\n");

        renderService(configuration, out);
        renderOperations(configuration, out);
        renderEnvironments(configuration, out);
        renderThresholds(configuration.thresholds(), "", out, """
                # The objectives every run is judged against unless a workload overrides them.
                # Without these a test produces measurements but no verdict, and a run with no
                # verdict is a demonstration rather than evidence.
                """);
        renderWorkloads(configuration, out);
        renderObservationSource(configuration, out);
        renderLocalLab(configuration, out);
        renderProduction(configuration, out);

        return out.toString();
    }

    private void renderService(ProjectConfiguration configuration, StringBuilder out) {
        if (configuration.serviceName().isBlank() && configuration.serviceDescription().isBlank()
                && configuration.serviceVersion().isBlank()
                && configuration.openApiSourceIfPresent().isEmpty()) {
            return;
        }
        out.append("""
                # The system under test: one deployable service. Its dependencies may well affect
                # the numbers below, but they are not additional systems under test — what a result
                # can claim about them is decided by the environment's dependency mode.
                service:
                """);
        if (!configuration.serviceName().isBlank()) {
            out.append("  name: ").append(quote(configuration.serviceName())).append('\n');
        }
        if (!configuration.serviceDescription().isBlank()) {
            out.append("  description: ").append(quote(configuration.serviceDescription())).append('\n');
        }
        if (!configuration.serviceVersion().isBlank()) {
            out.append("""
                      # Which release of the service these results describe. A run that cannot name
                      # its build can still be read, but it cannot be compared with the next one —
                      # so a pipeline usually overrides this per run instead:
                      #
                      #     vortex run peak . --service-version "$GIT_SHA"
                      #
                      # The command line wins over this value. Deliberately not part of experiment
                      # identity: two runs of the same experiment against different releases are the
                      # comparison, not an incomparable pair.
                    """);
            out.append("  version: ").append(quote(configuration.serviceVersion())).append('\n');
        }
        configuration.openApiSourceIfPresent().ifPresent(source -> {
            out.append("""
                      # Where this service's API description lives, so re-importing operations never
                      # needs anyone to re-type an address. A file is resolved relative to this
                      # repository at import time, so it still means the same thing after a clone.
                    """);
            out.append("  openapi:\n");
            switch (source) {
                case OpenApiSource.File file ->
                        out.append("    file: ").append(quote(file.relativePath())).append('\n');
                case OpenApiSource.Url url ->
                        out.append("    url: ").append(quote(url.url())).append('\n');
            }
        });
        out.append('\n');
    }

    private void renderOperations(ProjectConfiguration configuration, StringBuilder out) {
        List<OperationBinding> bindings = configuration.operationBindings().stream()
                .filter(binding -> !binding.isEmpty())
                .toList();
        if (bindings.isEmpty()) {
            return;
        }
        out.append("""
                # How to issue each operation: the request data to send, what a good answer looks
                # like, and whether a person has approved it. Operations themselves come from your
                # imported API description and are not repeated here, so re-importing never discards
                # these decisions.
                #
                # A value is either written out, or it says where it comes from:
                #
                #   X-Tenant: "acme"                                    a fixed value
                #   Authorization: "Bearer ${API_TOKEN}"                read from the environment
                #   X-Request-Id: { generated: uuid }                   Vortex produces it
                #   id: { dataset: customers, field: customerId }       a field of the current row
                #
                # Generated values are new for every request unless you say otherwise with
                # 'lifecycle: per-vu'. Dataset rows are walked in order and wrap at the end, and
                # every value one request reads from a dataset comes from the same row — so a
                # customer id and the mobile number beside it belong to the same customer.
                #
                # A dataset is 'scope: local' (held on this machine) unless it says 'scope: portable',
                # which means it is committed alongside this file and travels with the service.
                #
                # An operation that changes data must be reviewed before Vortex will execute it.
                # Schema-valid is not business-valid, and creating records at a hundred a second is
                # not something that should become possible by accident.
                operations:
                """);
        for (OperationBinding binding : bindings) {
            out.append("  ").append(binding.operationId().value()).append(":\n");
            renderValueMap("pathValues", binding.pathValues(), out);
            renderValueMap("queryValues", binding.queryValues(), out);
            renderValueMap("headers", binding.headers(), out);
            if (!binding.body().isBlank()) {
                out.append("    body: ").append(quote(binding.body())).append('\n');
            }
            renderBodyValueMap(binding.bodyValues(), out);
            if (!binding.expect().isDefault()) {
                out.append("    expect:\n      status: [")
                        .append(binding.expect().statuses().stream().map(String::valueOf)
                                .reduce((a, b) -> a + ", " + b).orElse(""))
                        .append("]\n");
            }
            if (binding.reviewed()) {
                out.append("    reviewed: true\n");
            }
        }
        out.append('\n');
    }

    private void renderInlineMap(String indent, String name, Map<String, String> values, StringBuilder out) {
        if (values.isEmpty()) {
            return;
        }
        out.append(indent).append(name).append(":\n");
        values.forEach((key, value) ->
                out.append(indent).append("  ").append(key).append(": ").append(quote(value)).append('\n'));
    }

    private void renderValueMap(String name, Map<String, RequestValue> values, StringBuilder out) {
        if (values.isEmpty()) {
            return;
        }
        out.append("    ").append(name).append(":\n");
        values.forEach((key, value) -> out.append("      ").append(key).append(": ")
                .append(RequestValueYaml.render(value, this::quote)).append('\n'));
    }

    private void renderBodyValueMap(Map<BodyFieldPath, RequestValue> values, StringBuilder out) {
        if (values.isEmpty()) {
            return;
        }
        out.append("    bodyValues:\n");
        values.forEach((path, value) -> out.append("      ").append(path.asText()).append(": ")
                .append(RequestValueYaml.render(value, this::quote)).append('\n'));
    }

    private void renderEnvironments(ProjectConfiguration configuration, StringBuilder out) {
        if (configuration.environments().isEmpty()) {
            return;
        }
        out.append("""
                # Where tests can run, and — just as importantly — what each place actually is.
                # Vortex uses this to decide what a result can legitimately claim: a run against
                # simulated dependencies cannot establish production capacity, however fast it is.
                #
                # Header values may reference environment variables as ${NAME}. Vortex stores the
                # reference and resolves it only when launching the load generator, so a secret
                # never reaches this file, the execution history, the reports or the AI prompts.
                environments:
                """);
        for (Environment environment : configuration.environments()) {
            out.append("  ").append(environment.name()).append(":\n");
            out.append("    type: ").append(environment.type().name()).append("\n");
            // Only an ExternalEndpointTarget has a genuine pre-run address — a Docker/Compose target
            // writes no baseUrl at all rather than a manufactured placeholder value.
            if (environment.target() instanceof ExternalEndpointTarget endpoint) {
                out.append("    baseUrl: ").append(quote(endpoint.endpoint().value())).append('\n');
            }
            out.append("    dependencies: ").append(environment.dependencyMode().name()).append('\n');
            renderTarget(environment.target(), out);

            EnvironmentCapabilities capabilities = environment.capabilities();
            out.append("    capabilities:\n");
            out.append("      usesMockDependencies: ").append(capabilities.usesMockDependencies()).append('\n');
            out.append("      usesLocalStack: ").append(capabilities.usesLocalStack()).append('\n');
            out.append("      productionLikeInfrastructure: ")
                    .append(capabilities.productionLikeInfrastructure()).append('\n');
            out.append("      sharedEnvironment: ").append(capabilities.sharedEnvironment()).append('\n');
            out.append("      distributedExecutionAllowed: ")
                    .append(capabilities.distributedExecutionAllowed()).append('\n');

            renderInlineMap("    ", "headers", environment.headers(), out);
        }
        out.append('\n');
    }

    /**
     * Writes the {@code target:} sub-block for a Vortex-managed or attached target.
     *
     * <p>An {@link ExternalEndpointTarget} writes nothing here — its address is already the
     * {@code baseUrl} above, and every file written before Docker/Compose targets existed keeps
     * rendering exactly as it always did.
     */
    private void renderTarget(ExecutionTarget target, StringBuilder out) {
        switch (target) {
            case ExternalEndpointTarget ignored -> {
                // nothing to write — see the method comment
            }
            case DockerImageTarget image -> {
                out.append("    target:\n");
                out.append("      kind: ").append(TargetKind.DOCKER_IMAGE.name()).append('\n');
                out.append("      image: ").append(quote(image.image().value())).append('\n');
                out.append("      containerPort: ").append(image.containerPort().value()).append('\n');
                image.resources().cpuIfPresent().ifPresent(cpu ->
                        out.append("      cpuMillicores: ").append(cpu.millicores()).append('\n'));
                image.resources().memoryIfPresent().ifPresent(memory ->
                        out.append("      memoryMebibytes: ")
                                .append(memory.bytes() / (1024L * 1024L)).append('\n'));
                image.readinessCheckIfPresent().ifPresent(readiness -> {
                    out.append("      readinessPath: ").append(quote(readiness.path())).append('\n');
                    out.append("      readinessExpectedStatus: ")
                            .append(readiness.expectedStatus()).append('\n');
                    out.append("      readinessTimeoutSeconds: ")
                            .append(readiness.timeout().toSeconds()).append('\n');
                });
            }
            case DockerComposeTarget compose -> {
                out.append("    target:\n");
                out.append("      kind: ").append(TargetKind.DOCKER_COMPOSE.name()).append('\n');
                out.append("      composeFile: ").append(quote(compose.composeFile())).append('\n');
                out.append("      service: ").append(quote(compose.serviceName())).append('\n');
                out.append("      containerPort: ").append(compose.containerPort().value()).append('\n');
            }
        }
    }

    private void renderWorkloads(ProjectConfiguration configuration, StringBuilder out) {
        if (configuration.workloads().isEmpty()) {
            return;
        }
        out.append("""
                # The workloads Vortex knows how to apply to this service.
                #
                # A workload is what the service should experience, not a business flow: an operation
                # mix describes aggregate traffic composition — many callers hitting several
                # endpoints at once — rather than one caller doing things in order. A single
                # operation is a complete, valid workload.
                #
                # Weights are relative, so 15/25/55/5 and 3/5/11/1 mean the same thing. The workload
                # supplies one TOTAL rate and Vortex divides it according to the weights; it never
                # runs each operation at the full total.
                workloads:
                """);
        for (Workload workload : configuration.workloads()) {
            out.append("  ").append(workload.name()).append(":\n");
            out.append("    type: ").append(workload.type().name()).append("    # ")
                    .append(workload.type().question()).append('\n');
            if (!workload.description().isBlank()) {
                out.append("    description: ").append(quote(workload.description())).append('\n');
            }
            if (workload.hasCustomObjective()) {
                out.append("    objective: ").append(quote(workload.objective())).append('\n');
            }
            renderShape(workload.shape(), out);
            out.append("    operations:\n");
            workload.operations().entries().forEach(entry ->
                    out.append("      ").append(entry.operationId().value()).append(": ")
                            .append(entry.weight().value()).append('\n'));
            if (workload.source().kind() != WorkloadSource.SourceKind.MANUAL) {
                out.append("    source:\n");
                out.append("      kind: ").append(workload.source().kind().name()).append('\n');
                if (!workload.source().detail().isBlank()) {
                    out.append("      detail: ").append(quote(workload.source().detail())).append('\n');
                }
                renderObservation(workload.source().observation(), "      ", out);
                if (!workload.source().derivation().isBlank()) {
                    out.append("      derivation: ")
                            .append(quote(workload.source().derivation())).append('\n');
                }
            }
            renderThresholds(workload.thresholds(), "    ", out, "");
            if (!workload.k6Options().isEmpty()) {
                out.append("    # Merged into the generated k6 scenarios verbatim. Vortex does not\n");
                out.append("    # validate these; k6 does, and preflight reports what it says.\n");
                renderInlineMap("    ", "k6", workload.k6Options(), out);
            }
        }
        out.append('\n');
    }

    private void renderShape(LoadShape workload, StringBuilder out) {
        out.append("    shape:\n");
        switch (workload) {
            case ConstantArrivalRateShape constant -> {
                out.append("      model: arrival-rate\n");
                out.append("      rate: ").append(constant.rate().display())
                        .append("    # requests per second, total across every operation\n");
                out.append("      duration: ").append(Durations.compact(constant.duration())).append('\n');
            }
            case RampingArrivalRateShape ramping -> {
                out.append("      model: arrival-rate\n");
                out.append("      startRate: ").append(ramping.startRate().display())
                        .append("    # requests per second\n");
                out.append("      stages:\n");
                for (Stage stage : ramping.rampStages()) {
                    out.append("        - target: ").append(stage.target().display()).append('\n');
                    out.append("          duration: ").append(Durations.compact(stage.duration()))
                            .append('\n');
                }
            }
            case ConstantConcurrencyShape constant -> {
                out.append("      model: concurrency\n");
                out.append("      vus: ").append(constant.vus().vus())
                        .append("    # concurrent virtual users; throughput is an outcome, not a target\n");
                out.append("      duration: ").append(Durations.compact(constant.duration())).append('\n');
            }
            case RampingConcurrencyShape ramping -> {
                out.append("      model: concurrency\n");
                out.append("      startVUs: ").append(ramping.startVus().vus()).append('\n');
                out.append("      stages:\n");
                for (Stage stage : ramping.rampStages()) {
                    out.append("        - target: ").append(stage.target().display()).append('\n');
                    out.append("          duration: ").append(Durations.compact(stage.duration()))
                            .append('\n');
                }
            }
        }
    }

    private void renderThresholds(ThresholdSet thresholds, String indent, StringBuilder out,
            String comment) {
        if (thresholds.isEmpty()) {
            return;
        }
        out.append(comment);
        out.append(indent).append("thresholds:\n");
        renderScopedThresholds(thresholds.overall(), indent + "  ", out);

        for (OperationId operation : thresholds.scopedOperations()) {
            out.append(indent).append("  perOperation:\n");
            break;
        }
        for (OperationId operation : thresholds.scopedOperations()) {
            out.append(indent).append("    ").append(operation.value()).append(":\n");
            renderScopedThresholds(thresholds.forOperation(operation), indent + "      ", out);
        }
        if (indent.isEmpty()) {
            out.append('\n');
        }
    }

    private void renderScopedThresholds(List<Threshold> thresholds, String indent, StringBuilder out) {
        List<LatencyThreshold> latency = thresholds.stream()
                .filter(LatencyThreshold.class::isInstance)
                .map(LatencyThreshold.class::cast)
                .sorted(java.util.Comparator.comparing(LatencyThreshold::percentile))
                .toList();
        if (!latency.isEmpty()) {
            out.append(indent).append("latency:\n");
            latency.forEach(threshold -> out.append(indent).append("  ")
                    .append(threshold.percentile().label()).append(": ")
                    .append(Durations.compact(threshold.maximum())).append('\n'));
        }
        thresholds.stream()
                .filter(ErrorRateThreshold.class::isInstance)
                .map(ErrorRateThreshold.class::cast)
                .findFirst()
                .ifPresent(threshold -> {
                    out.append(indent).append("errorRate:\n");
                    out.append(indent).append("  maximum: ")
                            .append(threshold.maximum().display()).append('\n');
                });
    }

    private void renderProduction(ProjectConfiguration configuration, StringBuilder out) {
        configuration.productionObservationIfPresent().ifPresent(observation -> {
            out.append("""
                    # What the service actually receives in production. This is what turns an
                    # invented traffic number into a production-informed one, and it is the only
                    # basis on which Vortex will calculate capacity headroom.
                    #
                    # The composition matters as much as the volume: 120 requests/sec of cheap status
                    # polling is a different workload from 120 requests/sec of order submission.
                    production:
                    """);
            observation.averageRateIfPresent().ifPresent(rate ->
                    out.append("  observedAverage: ").append(rate.display())
                            .append("    # requests per second\n"));
            observation.p95ObservedRateIfPresent().ifPresent(rate ->
                    out.append("  observedP95Rate: ").append(rate.display())
                            .append("    # the 95th percentile of request RATE, not latency\n"));
            out.append("  observedPeak: ").append(observation.peakRate().display()).append('\n');
            observation.observedMixIfPresent().ifPresent(mix -> {
                out.append("  observedMix:\n");
                mix.entries().forEach(entry ->
                        out.append("    ").append(entry.operationId().value()).append(": ")
                                .append(entry.weight().value()).append('\n'));
            });
            observation.mixCoverageIfPresent().ifPresent(coverage -> {
                out.append("  mixCoverage:\n");
                out.append("    observedRequests: ").append(coverage.totalObservedRequests())
                        .append("   # every request counted in the window\n");
                out.append("    matchedRequests: ").append(coverage.matchedRequests())
                        .append("    # those attributable to an operation above\n");
            });
            observation.sampleResolutionIfPresent().ifPresent(resolution ->
                    out.append("  sampleResolution: ")
                            .append(Durations.display(resolution))
                            .append("   # the interval each rate sample averaged over\n"));
            if (!observation.source().isBlank()) {
                out.append("  source: ").append(quote(observation.source())).append('\n');
            }
            renderObservation(observation.observation(), "  ", out);
            observation.provenanceIfPresent().ifPresent(provenance -> {
                out.append("  provenance:\n");
                out.append("    provider: ").append(quote(provenance.providerId())).append('\n');
                if (!provenance.query().isEmpty()) {
                    out.append("    query: ").append(quote(provenance.query())).append('\n');
                }
                if (!provenance.entityId().isEmpty()) {
                    out.append("    entity: ").append(quote(provenance.entityId())).append('\n');
                }
                if (!provenance.sourceUrl().isEmpty()) {
                    out.append("    url: ").append(quote(provenance.sourceUrl())).append('\n');
                }
            });
            if (!observation.note().isBlank()) {
                out.append("  note: ").append(quote(observation.note())).append('\n');
            }
            out.append('\n');
        });
    }

    /**
     * Writes which monitoring system to ask about production traffic.
     *
     * <p>Header values are written exactly as configured, references and all. That is the point of
     * the reference syntax: {@code ${DT_TOKEN}} is safe to commit, and the value it names never
     * enters this file, the database, a report or a prompt.
     */
    private void renderObservationSource(ProjectConfiguration configuration, StringBuilder out) {
        configuration.observationSourceIfPresent().ifPresent(source -> {
            out.append("""
                    # Where Vortex can go and ask what this service receives in production, so a
                    # workload can be calibrated from measured traffic rather than a remembered
                    # number. Fetching never edits this file on its own — the proposal is reviewed
                    # first.
                    #
                    # Header values may reference environment variables as ${NAME}, exactly as an
                    # environment's headers do. The reference is stored; the secret is not.
                    observation:
                    """);
            out.append("  source: ").append(source.kind().name().toLowerCase(Locale.ROOT)).append('\n');
            out.append("  endpoint: ").append(quote(source.endpoint())).append('\n');
            out.append(source.kind() == ObservationSource.Kind.DYNATRACE ? "  entity: " : "  service: ")
                    .append(quote(source.serviceIdentifier())).append('\n');
            out.append("  window: ")
                    .append(Durations.display(source.window())).append('\n');
            renderInlineMap("  ", "headers", source.headers(), out);
            if (source.kind() == ObservationSource.Kind.PROMETHEUS
                    && !source.labels().equals(ObservationSource.DEFAULT_LABELS)) {
                renderInlineMap("  ", "labels", source.labels(), out);
            }
            out.append('\n');
        });
    }

    /**
     * Writes which Compose file describes this service's local dependencies.
     *
     * <p>The path is relative to the repository, and written back in its normalised form. This file
     * is committed and read on other people's machines, where an absolute path would name a
     * directory that does not exist.
     */
    private void renderLocalLab(ProjectConfiguration configuration, StringBuilder out) {
        configuration.localLabIfPresent().ifPresent(lab -> {
            out.append("""
                    # The Compose file this repository already owns, so dependencies can be started
                    # on a developer's machine. Vortex runs it; it does not generate or manage it.
                    #
                    # The path is relative to this repository, so it still means the same thing
                    # after somebody else clones it.
                    lab:
                    """);
            out.append("  compose: ").append(quote(lab.composeFile())).append('\n');
            out.append('\n');
        });
    }

    /**
     * Writes when an observation was taken, in the shape the parser accepts.
     *
     * <p>Nothing is written when nobody recorded it. A file that says {@code observed:} with no
     * dates under it would suggest the question had been considered and answered.
     */
    private void renderObservation(Observation observation, String indent, StringBuilder out) {
        if (!observation.isKnown()) {
            return;
        }
        out.append(indent).append("observed:\n");
        if (observation.isPoint()) {
            observation.fromIfPresent().ifPresent(at ->
                    out.append(indent).append("  at: ").append(at).append('\n'));
            return;
        }
        observation.fromIfPresent().ifPresent(from ->
                out.append(indent).append("  from: ").append(from).append('\n'));
        observation.toIfPresent().ifPresent(to ->
                out.append(indent).append("  to: ").append(to).append('\n'));
    }

    /** Quotes a scalar so values containing YAML-significant characters survive a round trip. */
    private String quote(String value) {
        if (value == null || value.isEmpty()) {
            return "\"\"";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "") + "\"";
    }
}
