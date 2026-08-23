package dev.vortex.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.port.ConfigurationStore;
import dev.vortex.core.project.ProjectConfiguration;
import dev.vortex.core.workload.TestType;
import dev.vortex.core.workload.WorkloadSource;
import dev.vortex.core.workload.WorkloadModel;
import dev.vortex.persistence.config.YamlConfigurationStore;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code vortex.yaml} is the file people read first and edit most, so its failures have to teach.
 *
 * <p>Every rejection here is checked for naming the field, saying what was wrong with it, and saying
 * what to do instead. "Invalid configuration" makes a user hunt for a problem Vortex has already
 * located.
 */
class YamlConfigurationStoreTest {

    private final YamlConfigurationStore store = new YamlConfigurationStore();

    private ProjectConfiguration parse(String yaml) {
        ConfigurationStore.LoadResult result = store.parse(yaml, "vortex.yaml");
        assertThat(result.problems()).isEmpty();
        return result.configuration();
    }

    private java.util.List<String> problemsIn(String yaml) {
        ConfigurationStore.LoadResult result = store.parse(yaml, "vortex.yaml");
        assertThat(result.isValid()).isFalse();
        return result.problems();
    }

    @Nested
    @DisplayName("the shape of a configuration file")
    class Parsing {

        @Test
        @DisplayName("a single operation is enough to define a runnable workload")
        void aSingleOperationWorkload() {
            var configuration = parse("""
                    version: 1
                    service:
                      name: checkout-service
                    environments:
                      local: { type: LOCAL_ISOLATED, baseUrl: "http://localhost:8080" }
                    workloads:
                      submission-load:
                        type: AVERAGE_LOAD
                        shape: { model: arrival-rate, rate: 50, duration: 30m }
                        operations: { createOrder: 100 }
                    """);

            var workload = configuration.workloadByName("submission-load").orElseThrow();

            assertThat(configuration.serviceName()).isEqualTo("checkout-service");
            assertThat(workload.type()).isEqualTo(TestType.AVERAGE_LOAD);
            assertThat(workload.isSingleOperation()).isTrue();
            assertThat(workload.peakLevel().displayWithUnit()).isEqualTo("50 requests/sec");
        }

        @Test
        void aRealisticMultiOperationWorkload() {
            var configuration = parse("""
                    version: 1
                    workloads:
                      production-peak:
                        type: STRESS
                        shape: { model: arrival-rate, rate: 120, duration: 30m }
                        source:
                          kind: PRODUCTION_OBSERVED
                          detail: "Grafana"
                          observedAt: 2026-08-01T09:00:00Z
                        operations:
                          createOrder: 15
                          getOrder: 25
                          getOrderStatus: 55
                          cancelOrder: 5
                    """);

            var workload = configuration.workloadByName("production-peak").orElseThrow();

            assertThat(workload.operations().size()).isEqualTo(4);
            assertThat(workload.operations()
                    .sharePercent(dev.vortex.core.shared.OperationId.of("getOrderStatus")))
                    .isEqualTo("55");
            assertThat(workload.source().kind())
                    .isEqualTo(WorkloadSource.SourceKind.PRODUCTION_OBSERVED);
            assertThat(workload.source().describe()).contains("Grafana");
        }

        @Test
        void aConcurrencyWorkload() {
            var configuration = parse("""
                    version: 1
                    workloads:
                      batch-workers:
                        type: AVERAGE_LOAD
                        shape: { model: concurrency, vus: 50, duration: 10m }
                        operations: { getOrderStatus: 100 }
                        k6: { gracefulStop: 60s }
                    """);

            var workload = configuration.workloadByName("batch-workers").orElseThrow();

            assertThat(workload.model()).isEqualTo(WorkloadModel.CLOSED);
            assertThat(workload.peakLevel().displayWithUnit()).isEqualTo("50 VUs");
            assertThat(workload.k6Options()).containsEntry("gracefulStop", "60s");
        }

        @Test
        void aRampingWorkload() {
            var configuration = parse("""
                    version: 1
                    workloads:
                      capacity:
                        type: BREAKPOINT
                        shape:
                          model: arrival-rate
                          startRate: 10
                          stages:
                            - { target: 50, duration: 5m }
                            - { target: 100, duration: 5m }
                        operations: { createOrder: 100 }
                    """);

            var workload = configuration.workloadByName("capacity").orElseThrow();

            assertThat(workload.stages()).hasSize(2);
            assertThat(workload.peakLevel().asDouble()).isEqualTo(100.0);
            assertThat(workload.shape().isRamping()).isTrue();
        }

        @Test
        @DisplayName("per-operation objectives are read, so a mixed run can be judged properly")
        void perOperationObjectives() {
            var configuration = parse("""
                    version: 1
                    thresholds:
                      latency: { p95: 500ms }
                      errorRate: { maximum: 1% }
                    workloads:
                      production-peak:
                        type: STRESS
                        shape: { model: arrival-rate, rate: 120, duration: 30m }
                        operations: { createOrder: 15, getOrderStatus: 85 }
                        thresholds:
                          perOperation:
                            getOrderStatus:
                              latency: { p95: 200ms }
                    """);

            var workload = configuration.workloadByName("production-peak").orElseThrow();
            var effective = workload.effectiveThresholds(configuration.thresholds());

            assertThat(effective.hasOperationScopedThresholds()).isTrue();
            assertThat(effective.forOperation(
                    dev.vortex.core.shared.OperationId.of("getOrderStatus"))).singleElement()
                    .satisfies(threshold -> assertThat(threshold.describe())
                            .isEqualTo("p95 latency below 200 ms for getOrderStatus"));
            assertThat(effective.overall()).hasSize(2);
        }

        @Test
        void operationBindingsCarryRequestDataAndApproval() {
            var configuration = parse("""
                    version: 1
                    operations:
                      createOrder:
                        body: '{"amount":1}'
                        reviewed: true
                        expect: { status: [200, 201] }
                    """);

            var binding = configuration.bindingOrDefault(Fixtures.CREATE_ORDER);

            assertThat(binding.reviewed()).isTrue();
            assertThat(binding.bodyIfPresent()).hasValue("{\"amount\":1}");
            assertThat(binding.expect().statuses()).containsExactly(200, 201);
        }
    }

    @Nested
    @DisplayName("where a request value comes from")
    class RequestValues {

        private dev.vortex.core.data.RequestValue header(String yaml, String name) {
            return parse("""
                    version: 1
                    operations:
                      createOrder:
                        headers:
                    """ + yaml).bindingOrDefault(dev.vortex.core.shared.OperationId.of("createOrder"))
                    .headers().get(name);
        }

        @Test
        @DisplayName("a plain scalar is a fixed value, exactly as it always was")
        void scalarsStayFixed() {
            // Every configuration written before request data existed keeps working, unmigrated.
            assertThat(header("      X-Tenant: \"acme\"\n", "X-Tenant"))
                    .isEqualTo(new dev.vortex.core.data.FixedValue("acme"));
        }

        @Test
        @DisplayName("a scalar carrying ${NAME} is an environment reference, exactly as it always was")
        void scalarsWithReferencesStayEnvironmentValues() {
            assertThat(header("      Authorization: \"Bearer ${API_TOKEN}\"\n", "Authorization"))
                    .isEqualTo(new dev.vortex.core.data.EnvironmentValue("Bearer ${API_TOKEN}"));
        }

        @Test
        @DisplayName("a generator is read with its lifecycle and its parameters")
        void generatorsAreRead() {
            var value = (dev.vortex.core.data.GeneratedValue)
                    header("      X-Session: { generated: random-string, lifecycle: per-vu, length: 16 }\n",
                            "X-Session");

            assertThat(value.generator()).isEqualTo(dev.vortex.core.data.Generator.RANDOM_STRING);
            assertThat(value.lifecycle()).isEqualTo(dev.vortex.core.data.ValueLifecycle.PER_VU);
            assertThat(value.length()).isEqualTo(16);
        }

        @Test
        @DisplayName("a dataset value carries its scope, so nothing depends on a precedence rule")
        void datasetValuesCarryTheirScope() {
            var local = (dev.vortex.core.data.DatasetValue)
                    header("      X-Customer: { dataset: customers, field: customerId }\n",
                            "X-Customer");
            var portable = (dev.vortex.core.data.DatasetValue)
                    header("      X-Account: { dataset: accounts, scope: portable, field: id }\n",
                            "X-Account");

            assertThat(local.dataset().scope())
                    .isEqualTo(dev.vortex.core.data.DatasetScope.LOCAL);
            assertThat(portable.dataset().scope())
                    .isEqualTo(dev.vortex.core.data.DatasetScope.PORTABLE);
        }

        @Test
        @DisplayName("body fields are addressed by name, and dots for nesting")
        void bodyFieldsAreRead() {
            var binding = parse("""
                    version: 1
                    operations:
                      createOrder:
                        body: '{"customerId":""}'
                        bodyValues:
                          customerId: { dataset: customers, field: customerId }
                          payment.card.token: { environment: CARD_TOKEN }
                    """).bindingOrDefault(dev.vortex.core.shared.OperationId.of("createOrder"));

            assertThat(binding.bodyValues().keySet())
                    .extracting(dev.vortex.core.data.BodyFieldPath::asText)
                    .containsExactlyInAnyOrder("customerId", "payment.card.token");
        }

        @Test
        @DisplayName("a body field path outside the grammar is refused, not quietly reinterpreted")
        void bodyFieldGrammarIsEnforced() {
            // The grammar is names and dots. Array indices are the first step towards JSONPath, and
            // the refusal says so rather than failing somewhere later.
            assertThat(problemsIn("""
                    version: 1
                    operations:
                      createOrder:
                        bodyValues:
                          items[0].sku: "ABC"
                    """))
                    .anySatisfy(problem -> assertThat(problem)
                            .contains("items[0].sku")
                            .contains("Array indices are not supported"));
        }

        @Test
        @DisplayName("a value that names no source says which sources exist")
        void unsourcedValuesAreRefused() {
            assertThat(problemsIn("""
                    version: 1
                    operations:
                      createOrder:
                        headers:
                          X-Thing: { somethingElse: 1 }
                    """))
                    .anySatisfy(problem -> assertThat(problem)
                            .contains("does not say where its value comes from")
                            .contains("'fixed', 'generated', 'dataset' or 'environment'"));
        }

        @Test
        @DisplayName("an unknown generator lists the ones that exist, and says why the set is small")
        void unknownGeneratorsAreExplained() {
            assertThat(problemsIn("""
                    version: 1
                    operations:
                      createOrder:
                        headers:
                          X-Thing: { generated: creditCardNumber }
                    """))
                    .anySatisfy(problem -> assertThat(problem)
                            .contains("unknown generator 'creditCardNumber'")
                            .contains("uuid")
                            .contains("belongs in a dataset"));
        }

        @Test
        @DisplayName("a dataset value with no field says so, and shows the shape that works")
        void datasetValuesNeedAField() {
            assertThat(problemsIn("""
                    version: 1
                    operations:
                      createOrder:
                        headers:
                          X-Customer: { dataset: customers }
                    """))
                    .anySatisfy(problem -> assertThat(problem)
                            .contains("not which field of it to read")
                            .contains("field: customerId"));
        }

        @Test
        @DisplayName("every source survives a round trip through the file")
        void everySourceRoundTrips(@TempDir Path directory) {
            var requestData = new dev.vortex.core.data.RequestData(
                    java.util.Map.of("id",
                            new dev.vortex.core.data.DatasetValue(
                                    dev.vortex.core.data.DatasetRef.local("customers"), "customerId")),
                    java.util.Map.of("channel", new dev.vortex.core.data.FixedValue("web")),
                    java.util.Map.of(
                            "x-idempotency-key", dev.vortex.core.data.GeneratedValue.of(
                                    dev.vortex.core.data.Generator.UUID),
                            "Authorization", new dev.vortex.core.data.EnvironmentValue("${API_TOKEN}")),
                    "{\"productCode\":\"\"}",
                    java.util.Map.of(dev.vortex.core.data.BodyFieldPath.parse("productCode"),
                            new dev.vortex.core.data.FixedValue("CREDIT_CARD")));

            var original = Fixtures.configuration().withBinding(
                    Fixtures.configuration().bindingOrDefault(Fixtures.CREATE_ORDER)
                            .withRequestData(requestData));

            store.save(directory.toString(), original);
            var reloaded = store.load(directory.toString()).configuration()
                    .bindingOrDefault(Fixtures.CREATE_ORDER);

            assertThat(reloaded.requestData()).isEqualTo(requestData);
        }

        @Test
        @DisplayName("a literal that looks like a reference is written so it reads back as a literal")
        void fixedValuesContainingReferencesSurvive(@TempDir Path directory) {
            // Without the explicit 'fixed:' form, reading this back would resolve it from the
            // environment and send something else entirely.
            var literal = new dev.vortex.core.data.FixedValue("${NOT_A_SECRET}");
            var original = Fixtures.configuration().withBinding(
                    Fixtures.configuration().bindingOrDefault(Fixtures.CREATE_ORDER)
                            .withRequestData(new dev.vortex.core.data.RequestData(
                                    java.util.Map.of(), java.util.Map.of(),
                                    java.util.Map.of("X-Literal", literal), "", java.util.Map.of())));

            store.save(directory.toString(), original);

            assertThat(store.load(directory.toString()).configuration()
                    .bindingOrDefault(Fixtures.CREATE_ORDER).headers())
                    .containsEntry("X-Literal", literal);
        }
    }

    @Nested
    @DisplayName("vocabularies Vortex no longer speaks")
    class LegacyConfiguration {

        @Test
        @DisplayName("a journey-based file is refused with the edit spelled out, not silently migrated")
        void theJourneyModelIsRefusedWithGuidance() {
            var problems = problemsIn("""
                    journeys:
                      checkout:
                        steps:
                          - operation: getAccount
                          - operation: createOrder
                    traffic:
                      checkout: 100
                    """);

            // A one-step journey would translate cleanly. A multi-step one would not: Vortex would
            // have to choose between discarding the sequence and reinterpreting an ordered flow as
            // a concurrent mix, and those mean different things. Guessing is the thing this product
            // refuses to do everywhere else.
            assertThat(problems)
                    .anyMatch(problem -> problem.contains("journeys")
                            && problem.contains("adr-024"))
                    .anyMatch(problem -> problem.contains("Replace 'journeys:' and 'traffic:'"))
                    .anyMatch(problem -> problem.contains("requests per second")
                            && problem.contains("120 requests per second"))
                    .anyMatch(problem -> problem.contains("Set 'version: 1'"));
        }

        @Test
        @DisplayName("a file that still says 'scenarios:' is told what the words are now")
        void theScenarioVocabularyIsRefusedWithGuidance() {
            var problems = problemsIn("""
                    scenarios:
                      production-peak:
                        type: STRESS
                        workload: { model: arrival-rate, rate: 120, duration: 30m }
                        operations:
                          createOrder: 100
                    """);

            // Renaming the key automatically would be easy, and wrong: someone whose file still
            // says "scenario" is thinking in the old model, and a silent rewrite would leave them
            // reading one vocabulary in the interface and another in their editor.
            assertThat(problems)
                    .anyMatch(problem -> problem.contains("'scenarios:'")
                            && problem.contains("workload"))
                    .anyMatch(problem -> problem.contains("Rename 'scenarios:' to 'workloads:'"))
                    .anyMatch(problem -> problem.contains("'workload:' block to 'shape:'"))
                    .anyMatch(problem -> problem.contains("Set 'version: 1'"));
        }

        @Test
        @DisplayName("legacy keys are detected whatever the version field says")
        void legacyKeysAreDetectedWithoutTheVersion() {
            assertThat(problemsIn("""
                    version: 1
                    journeys:
                      checkout:
                        steps:
                          - operation: getAccount
                    """))
                    .anyMatch(problem -> problem.contains("journeys"));
        }
    }

    @Nested
    @DisplayName("validation names the field and the remedy")
    class Validation {

        @Test
        void aWorkloadWithNoOperationsSaysOneIsEnough() {
            assertThat(problemsIn("""
                    version: 1
                    workloads:
                      empty:
                        type: SMOKE
                        shape: { model: arrival-rate, rate: 1, duration: 30s }
                        operations: {}
                    """))
                    .anyMatch(problem -> problem.contains("workloads.empty.operations")
                            && problem.contains("a single operation is a complete performance target"));
        }

        @Test
        void aZeroWeightIsRejectedRatherThanSilentlyDropped() {
            assertThat(problemsIn("""
                    version: 1
                    workloads:
                      s:
                        type: SMOKE
                        shape: { model: arrival-rate, rate: 1, duration: 30s }
                        operations: { getOrder: 0 }
                    """))
                    .anyMatch(problem -> problem.contains("workloads.s.operations.getOrder")
                            && problem.contains("remove it instead"));
        }

        @Test
        @DisplayName("a concurrency workload with several operations explains why it cannot work")
        void aConcurrencyMixIsRejectedWithTheReason() {
            assertThat(problemsIn("""
                    version: 1
                    workloads:
                      s:
                        type: AVERAGE_LOAD
                        shape: { model: concurrency, vus: 50, duration: 10m }
                        operations: { getOrder: 50, getAccount: 50 }
                    """))
                    .anyMatch(problem ->
                            problem.contains("Weights divide virtual users rather than traffic"));
        }

        @Test
        void anUnknownWorkloadModelListsWhatIsMeant() {
            assertThat(problemsIn("""
                    version: 1
                    workloads:
                      s:
                        type: SMOKE
                        shape: { model: sideways, rate: 1, duration: 30s }
                        operations: { getOrder: 100 }
                    """))
                    .anyMatch(problem -> problem.contains("workloads.s.shape.model")
                            && problem.contains("arrival-rate")
                            && problem.contains("concurrency"));
        }

        @Test
        void anUnreadableDurationShowsTheFormatsAccepted() {
            assertThat(problemsIn("""
                    version: 1
                    workloads:
                      s:
                        type: SMOKE
                        shape: { model: arrival-rate, rate: 1, duration: "a while" }
                        operations: { getOrder: 100 }
                    """))
                    // The property is that the message shows the reader what a duration looks like,
                    // not that it lists a particular set of examples — the accepted units have
                    // grown since (an observation window is written as 30d) and will again.
                    .anyMatch(problem -> problem.contains("500ms")
                            && problem.contains("30s")
                            && problem.contains("10m"));
        }

        @Test
        void invalidYamlIsReportedAsSuch() {
            assertThat(problemsIn("workloads: [unclosed"))
                    .anyMatch(problem -> problem.contains("not valid YAML"));
        }
    }

    @Nested
    @DisplayName("the local lab")
    class LocalLab {

        @Test
        @DisplayName("a service with no dependencies needs no lab section at all")
        void theSectionIsOptional() {
            assertThat(parse("""
                    version: 1
                    service:
                      name: checkout-service
                    """).localLabIfPresent()).isEmpty();
        }

        @Test
        @DisplayName("reads which compose file describes the dependencies")
        void readsTheComposeFile() {
            assertThat(parse("""
                    version: 1
                    service:
                      name: checkout-service
                    lab:
                      compose: infra/compose.yaml
                    """).localLab().composeFile()).isEqualTo("infra/compose.yaml");
        }

        @Test
        @DisplayName("stores a normalized compose path, so one path has one spelling")
        void normalisesOnTheWayIn() {
            assertThat(parse("""
                    version: 1
                    lab:
                      compose: ./infra/../compose.yaml
                    """).localLab().composeFile()).isEqualTo("compose.yaml");
        }

        @Test
        @DisplayName("explains a lab section with no compose file")
        void anEmptySectionSaysWhatIsMissing() {
            assertThat(problemsIn("""
                    version: 1
                    lab:
                      profiles: [db]
                    """))
                    .anyMatch(problem -> problem.contains("lab.compose")
                            && problem.contains("compose.yaml"));
        }

        @Test
        @DisplayName("refuses an absolute compose path, because this file travels between machines")
        void anAbsolutePathIsRefused() {
            assertThat(problemsIn("""
                    version: 1
                    lab:
                      compose: /Users/someone/checkout/compose.yaml
                    """))
                    .anyMatch(problem -> problem.contains("lab.compose")
                            && problem.contains("travels between machines"));
        }

        @Test
        @DisplayName("refuses a compose path that escapes the repository")
        void anEscapingPathIsRefused() {
            assertThat(problemsIn("""
                    version: 1
                    lab:
                      compose: infra/../../outside.yaml
                    """))
                    .anyMatch(problem -> problem.contains("lab.compose")
                            && problem.contains("outside"));
        }
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        @DisplayName("what Vortex writes is what Vortex reads")
        void renderedConfigurationParsesBackToItself(@TempDir Path directory) throws Exception {
            var original = Fixtures.configuration();

            store.save(directory.toString(), original);
            var reloaded = store.load(directory.toString()).configuration();

            assertThat(reloaded.serviceName()).isEqualTo(original.serviceName());
            assertThat(reloaded.serviceVersion()).isEqualTo(original.serviceVersion());
            assertThat(reloaded.workloads()).extracting(workload -> workload.name())
                    .containsExactlyElementsOf(
                            original.workloads().stream().map(s -> s.name()).toList());
            assertThat(reloaded.workloadByName("batch-workers").orElseThrow().model())
                    .isEqualTo(WorkloadModel.CLOSED);
            assertThat(reloaded.bindingOrDefault(Fixtures.CREATE_ORDER).reviewed()).isTrue();
        }

        @Test
        @DisplayName("round-trips a configured local lab")
        void theLocalLabRoundTrips(@TempDir Path directory) {
            var withLab = Fixtures.configuration()
                    .withLocalLab(new dev.vortex.core.lab.LocalLabSettings("infra/compose.yaml"));

            store.save(directory.toString(), withLab);

            assertThat(store.load(directory.toString()).configuration().localLab().composeFile())
                    .isEqualTo("infra/compose.yaml");
        }

        @Test
        @DisplayName("a service with no local lab writes no lab section")
        void noLabMeansNoSection(@TempDir Path directory) {
            store.save(directory.toString(), Fixtures.configuration());

            assertThat(store.load(directory.toString()).configuration().localLabIfPresent())
                    .isEmpty();
        }

        @Test
        @DisplayName("the release under test survives a round trip, and absence stays absence")
        void theReleaseUnderTestRoundTrips(@TempDir Path directory) {
            var identified = Fixtures.configuration().withServiceVersion("3f97a82");
            store.save(directory.toString(), identified);
            assertThat(store.load(directory.toString()).configuration().serviceVersion())
                    .isEqualTo("3f97a82");

            // Clearing it must clear it. A stale release identifier is worse than none: it would
            // make two runs of different builds look like a repeat of the same one.
            store.save(directory.toString(), identified.withServiceVersion(""));
            assertThat(store.load(directory.toString()).configuration().serviceVersion()).isEmpty();
        }

        @Test
        @DisplayName("a file with no service.version parses, because recording one is optional")
        void theReleaseIsOptional() {
            var configuration = parse("""
                    version: 1
                    service:
                      name: checkout-service
                    """);

            assertThat(configuration.serviceVersion()).isEmpty();
            assertThat(configuration.serviceVersionIfPresent()).isEmpty();
        }

        @Test
        @DisplayName("the file explains itself, because it is where people learn the model")
        void theRenderedFileCarriesItsOwnDocumentation() {
            String yaml = store.render(Fixtures.configuration());

            assertThat(yaml).contains("# Vortex performance definition.");

            // Not pinned to specific prose — a copy-edit to the explanatory comments shouldn't break
            // this test — but a file with workloads and environments should carry substantially more
            // comment lines than a bare data dump would.
            long commentLines = yaml.lines().filter(line -> line.strip().startsWith("#")).count();
            assertThat(commentLines)
                    .as("rendered configuration should explain itself with comments, not just values")
                    .isGreaterThan(10);
        }

        @Test
        void theExampleProjectIsValid() throws Exception {
            Path example = Path.of("..", "examples", "checkout-service");
            String yaml = Files.readString(example.resolve(".vortex").resolve("vortex.yaml"));

            var result = store.parse(yaml, "examples/checkout-service");

            assertThat(result.problems()).isEmpty();
        }
    }

    @Nested
    @DisplayName("saving is atomic")
    class AtomicSave {

        @Test
        @DisplayName("no temporary file survives a successful save")
        void noTemporaryFileSurvives(@TempDir Path directory) throws Exception {
            store.save(directory.toString(), Fixtures.configuration());

            try (var entries = Files.list(directory.resolve(".vortex"))) {
                assertThat(entries.map(path -> path.getFileName().toString()))
                        .noneMatch(name -> name.endsWith(".tmp"));
            }
        }

        @Test
        @DisplayName("a save that cannot write its temporary file leaves the previous file untouched")
        void aFailedSaveLeavesThePreviousFileUntouched(@TempDir Path directory) {
            var original = Fixtures.configuration().withServiceVersion("first-version");
            store.save(directory.toString(), original);
            Path vortexDir = directory.resolve(".vortex");

            assertThat(vortexDir.toFile().setWritable(false)).isTrue();
            try {
                assertThatThrownBy(() -> store.save(directory.toString(),
                        Fixtures.configuration().withServiceVersion("second-version")))
                        .isInstanceOf(java.io.UncheckedIOException.class);
            } finally {
                assertThat(vortexDir.toFile().setWritable(true)).isTrue();
            }

            assertThat(store.load(directory.toString()).configuration().serviceVersion())
                    .isEqualTo("first-version");
        }
    }

    @Nested
    @DisplayName("the example this product ships")
    class ShippedExample {

        /** From the module directory, which is where surefire runs. */
        private static final Path EXAMPLE = Path.of("..", "examples", "checkout-service");

        @Test
        @DisplayName("examples/checkout-service parses, so the documented example is not fiction")
        void theShippedExampleParses() {
            // It is referenced from the configuration reference and from the README, and somebody
            // will copy it. An example that no longer parses teaches the wrong thing twice.
            var result = store.load(EXAMPLE.toString());

            assertThat(result.problems()).isEmpty();
            assertThat(result.configuration().serviceName()).isEqualTo("checkout-service");
        }

        @Test
        @DisplayName("its createOrder shows all four sources in one request")
        void theExampleDemonstratesEverySource() {
            var binding = store.load(EXAMPLE.toString()).configuration()
                    .bindingOrDefault(dev.vortex.core.shared.OperationId.of("createOrder"));

            assertThat(binding.headers().get("X-Client-Id"))
                    .isInstanceOf(dev.vortex.core.data.FixedValue.class);
            assertThat(binding.headers().get("X-Idempotency-Key"))
                    .isInstanceOf(dev.vortex.core.data.GeneratedValue.class);
            assertThat(binding.headers().get("Authorization"))
                    .isInstanceOf(dev.vortex.core.data.EnvironmentValue.class);
            assertThat(binding.bodyValues().values())
                    .anyMatch(value -> value instanceof dev.vortex.core.data.DatasetValue);
        }

        @Test
        @DisplayName("the dataset it maps is committed beside it, or the example could not run")
        void theExampleShipsItsDataset() {
            var value = (dev.vortex.core.data.DatasetValue) store.load(EXAMPLE.toString())
                    .configuration()
                    .bindingOrDefault(dev.vortex.core.shared.OperationId.of("createOrder"))
                    .bodyValues().get(dev.vortex.core.data.BodyFieldPath.parse("accountId"));

            assertThat(value.dataset().scope())
                    .isEqualTo(dev.vortex.core.data.DatasetScope.PORTABLE);
            assertThat(EXAMPLE.resolve(".vortex/datasets/accounts.csv")).exists();
        }
    }
}
