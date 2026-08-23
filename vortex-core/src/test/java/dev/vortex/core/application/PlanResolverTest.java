package dev.vortex.core.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vortex.core.catalog.CatalogSource;
import dev.vortex.core.catalog.OperationBinding;
import dev.vortex.core.catalog.ServiceCatalog;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.plan.EffectiveTestPlan;
import dev.vortex.core.plan.RunnerKind;
import dev.vortex.core.plan.ScriptSource;
import dev.vortex.core.project.ProjectConfiguration;
import dev.vortex.core.workload.TestType;
import dev.vortex.core.shared.OperationId;
import dev.vortex.core.workload.ConstantArrivalRateShape;
import dev.vortex.core.workload.OperationMix;
import dev.vortex.core.workload.RateAllocator;
import dev.vortex.core.workload.WeightedOperation;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Resolution is where user intent becomes something the engine can execute — and be attributed. */
class PlanResolverTest {

    private final dev.vortex.core.fixtures.FakeDatasetStore datasets =
            new dev.vortex.core.fixtures.FakeDatasetStore();

    private final PlanResolver resolver =
            new PlanResolver(new RateAllocator(), new RequestDataResolver(), datasets);

    private EffectiveTestPlan resolve(ProjectConfiguration configuration, String workloadName) {
        return resolve(configuration, workloadName, Fixtures.catalog());
    }

    private EffectiveTestPlan resolve(ProjectConfiguration configuration, String workloadName,
            ServiceCatalog catalog) {
        return resolver.resolve(Fixtures.project(), configuration, catalog,
                new PlanResolver.ResolutionRequest(workloadName, "local", null,
                        RunnerKind.LOCAL_BINARY, ScriptSource.GENERATED, List.of(), null, ""));
    }

    @Nested
    @DisplayName("the total is divided, never repeated")
    class Allocation {

        @Test
        void eachOperationReceivesItsShare() {
            var plan = resolve(Fixtures.configuration(), "average-load");

            // A 70/30 mix at 20 requests/sec: 14 and 6.
            assertThat(plan.operations()).extracting(operation ->
                            operation.arrivalRateIfPresent().orElseThrow().asDouble())
                    .containsExactly(14.0, 6.0);
            assertThat(plan.peakLevel().asDouble()).isEqualTo(20.0);
        }

        @Test
        @DisplayName("a concurrency workload allocates nothing, because there is nothing to divide")
        void concurrencyWorkloadsCarryNoRate() {
            var plan = resolve(Fixtures.configuration(), "batch-workers");

            assertThat(plan.operations()).singleElement()
                    .satisfies(operation ->
                            assertThat(operation.arrivalRateIfPresent()).isEmpty());
            assertThat(plan.peakLevel().unit()).isEqualTo("VUs");
        }
    }

    @Nested
    @DisplayName("k6 scenario keys are assigned here, once")
    class ScenarioKeys {

        @Test
        void everyOperationGetsAKeyAndTheMapIsRecorded() {
            var plan = resolve(Fixtures.configuration(), "average-load");

            assertThat(plan.operations()).allSatisfy(operation ->
                    assertThat(operation.k6ScenarioKey()).isNotBlank());
            assertThat(plan.operationsByScenarioKey())
                    .containsEntry("getaccount", Fixtures.GET_ACCOUNT)
                    .containsEntry("getorder", Fixtures.GET_ORDER);
        }

        @Test
        @DisplayName("operation ids that sanitise to the same key still get distinct ones")
        void collidingIdsAreDisambiguated() {
            var catalog = new ServiceCatalog(CatalogSource.OPENAPI, "spec", "svc", "1", Fixtures.NOW,
                    List.of(operationNamed("get-order"), operationNamed("get_order")), List.of());

            var configuration = Fixtures.configuration().withWorkloads(List.of(
                    Fixtures.workload("mixed", TestType.AVERAGE_LOAD,
                            OperationMix.of(List.of(
                                    WeightedOperation.of(OperationId.of("get-order"), 50),
                                    WeightedOperation.of(OperationId.of("get_order"), 50))),
                            ConstantArrivalRateShape.of(10, Duration.ofMinutes(1)))));

            var plan = resolve(configuration, "mixed", catalog);

            // Sanitising is lossy. If attribution recovered the operation by re-sanitising the tag,
            // one of these operations would inherit the other's latency — which is exactly the
            // situation the suffix exists to prevent.
            assertThat(plan.operations()).extracting(operation -> operation.k6ScenarioKey())
                    .containsExactly("get_order", "get_order_2");
            assertThat(plan.operationsByScenarioKey()).hasSize(2);
        }

        @Test
        @DisplayName("an imported script exposes no mapping, because Vortex did not choose its keys")
        void importedScriptsHaveNoMapping() {
            var plan = resolver.resolve(Fixtures.project(), Fixtures.configuration(),
                    Fixtures.catalog(),
                    new PlanResolver.ResolutionRequest("average-load", "local", null,
                            RunnerKind.LOCAL_BINARY, ScriptSource.IMPORTED, List.of(), null, ""));

            assertThat(plan.operationsByScenarioKey()).isEmpty();
        }

        private dev.vortex.core.catalog.Operation operationNamed(String id) {
            return new dev.vortex.core.catalog.Operation(OperationId.of(id), id,
                    dev.vortex.core.catalog.HttpMethod.GET, "/" + id, "", List.of(), List.of(), null);
        }
    }

    @Nested
    @DisplayName("what resolution refuses to do")
    class Refusals {

        @Test
        @DisplayName("an unreviewed mutating operation is not executed just because it could be")
        void unreviewedMutationsAreRefused() {
            var configuration = Fixtures.configuration()
                    .withBinding(OperationBinding.of(Fixtures.CREATE_ORDER))
                    .withWorkloads(List.of(Fixtures.singleOperationWorkload()));

            assertThatThrownBy(() -> resolve(configuration, "submission-load"))
                    .isInstanceOf(PlanResolutionException.class)
                    .satisfies(thrown -> assertThat(((PlanResolutionException) thrown).problems())
                            .anyMatch(problem -> problem.contains("changes data"))
                            .anyMatch(problem -> problem.contains("Review its request data")));
        }

        @Test
        @DisplayName("an empty catalog is reported once, not once per operation")
        void anEmptyCatalogIsReportedOnce() {
            assertThatThrownBy(() ->
                    resolve(Fixtures.configuration(), "average-load", ServiceCatalog.empty()))
                    .isInstanceOf(PlanResolutionException.class)
                    .satisfies(thrown -> assertThat(((PlanResolutionException) thrown).problems())
                            .hasSize(1)
                            .allMatch(problem -> problem.contains("No API description")));
        }

        @Test
        void anUnknownWorkloadListsWhatIsAvailable() {
            assertThatThrownBy(() -> resolve(Fixtures.configuration(), "nope"))
                    .isInstanceOf(PlanResolutionException.class)
                    .satisfies(thrown -> assertThat(((PlanResolutionException) thrown).problems())
                            .anyMatch(problem -> problem.contains("Available workloads")
                                    && problem.contains("average-load")));
        }

        @Test
        void anUnknownOperationNamesTheWorkloadThatReferencesIt() {
            var catalog = new ServiceCatalog(CatalogSource.OPENAPI, "spec", "svc", "1", Fixtures.NOW,
                    List.of(Fixtures.getAccountOperation()), List.of());

            assertThatThrownBy(() -> resolve(Fixtures.configuration(), "average-load", catalog))
                    .isInstanceOf(PlanResolutionException.class)
                    .satisfies(thrown -> assertThat(((PlanResolutionException) thrown).problems())
                            .anyMatch(problem -> problem.contains("average-load")
                                    && problem.contains("getOrder")
                                    && problem.contains("Re-import the specification")));
        }
    }

    @Nested
    @DisplayName("the plan is a self-contained snapshot")
    class Snapshot {

        @Test
        void theWorkloadsObjectivesAreLayeredOverTheProjectsAndCopiedIn() {
            var plan = resolve(Fixtures.configuration(), "average-load");

            assertThat(plan.thresholds().size()).isEqualTo(3);
            assertThat(plan.workloadName()).isEqualTo("average-load");
            assertThat(plan.testType()).isEqualTo(TestType.AVERAGE_LOAD);
        }

        @Test
        @DisplayName("resolution never writes back into the user's configuration")
        void resolutionIsOneDirectional() {
            var configuration = Fixtures.configuration();
            resolve(configuration, "average-load");

            // The configuration still says "20/sec, 70/30" — the thing a human wrote and will edit
            // next week — not the 14/6 split this particular run used.
            assertThat(configuration.workloadByName("average-load").orElseThrow()
                    .peakLevel().asDouble()).isEqualTo(20.0);
        }

        @Test
        @DisplayName("the workspace path comes from the project, for a target executor to resolve "
                + "paths against — without disturbing the existing endpoint-target behaviour")
        void workspacePathComesFromTheProjectWithoutChangingExternalEndpointBehaviour() {
            var plan = resolve(Fixtures.configuration(), "average-load");

            assertThat(plan.workspacePath()).isEqualTo(Fixtures.project().workspacePath());
            // Unaffected by the workspace-path addition: an ExternalEndpointTarget-based plan still
            // resolves its pre-run address exactly as before.
            assertThat(plan.executionTarget())
                    .isInstanceOf(dev.vortex.core.target.ExternalEndpointTarget.class);
            assertThat(plan.configuredTargetIfPresent()).isPresent();
            assertThat(plan.effectiveTargetIfPresent()).isPresent();
            assertThat(plan.configuredTarget()).isEqualTo(plan.effectiveTarget());
        }

        @Test
        void bindingsSupplyRequestDataAndOverrideGeneratedDefaults() {
            var configuration = Fixtures.configuration().withBinding(
                    new OperationBinding(Fixtures.GET_ORDER,
                            new dev.vortex.core.data.RequestData(
                                    java.util.Map.of("id",
                                            new dev.vortex.core.data.FixedValue("ord-9999")),
                                    java.util.Map.of(),
                                    java.util.Map.of("X-Tenant",
                                            new dev.vortex.core.data.FixedValue("acme")),
                                    "", java.util.Map.of()),
                            dev.vortex.core.catalog.ExpectedResponse.ofStatuses(200), false));

            var plan = resolve(configuration, "average-load");
            var getOrder = plan.operation(Fixtures.GET_ORDER).orElseThrow();

            assertThat(getOrder.resolvedPath()).isEqualTo("/orders/ord-9999");
            assertThat(getOrder.headers())
                    .containsEntry("X-Tenant", new dev.vortex.core.data.FixedValue("acme"));
            assertThat(getOrder.expect().statuses()).containsExactly(200);
        }
    }

    @Nested
    @DisplayName("the data a request reads")
    class Datasets {

        private ProjectConfiguration boundTo(dev.vortex.core.data.DatasetRef ref, String field) {
            return Fixtures.configuration().withBinding(
                    Fixtures.configuration().bindingOrDefault(Fixtures.GET_ORDER)
                            .withRequestData(new dev.vortex.core.data.RequestData(
                                    java.util.Map.of("id",
                                            new dev.vortex.core.data.DatasetValue(ref, field)),
                                    java.util.Map.of(), java.util.Map.of(), "", java.util.Map.of())));
        }

        @Test
        @DisplayName("a dataset is read at resolution, so the plan records what was actually there")
        void datasetsAreResolvedOntoThePlan() {
            datasets.with("customers", List.of("customerId"),
                    List.of(java.util.Map.of("customerId", "C001"),
                            java.util.Map.of("customerId", "C002")));

            var plan = resolve(boundTo(dev.vortex.core.data.DatasetRef.local("customers"),
                    "customerId"), "average-load");

            assertThat(plan.datasets()).singleElement().satisfies(dataset -> {
                assertThat(dataset.name()).isEqualTo("customers");
                assertThat(dataset.recordCount()).isEqualTo(2);
                assertThat(dataset.fields()).containsExactly("customerId");
                assertThat(dataset.contentHash()).isNotBlank();
                assertThat(dataset.stagedFile()).isEqualTo("dataset-customers.json");
            });
        }

        @Test
        @DisplayName("a dataset held on the machine that configured it says so on any other machine")
        void aMissingLocalDatasetExplainsWhy() {
            // What a fresh checkout of somebody else's service actually looks like. The same
            // honesty Vortex already applies to an API description it cannot resolve.
            assertThatThrownBy(() -> resolve(
                    boundTo(dev.vortex.core.data.DatasetRef.local("customers"), "customerId"),
                    "average-load"))
                    .isInstanceOf(PlanResolutionException.class)
                    .hasMessageContaining("data this workload's requests need");

            assertThat(problemsOf(dev.vortex.core.data.DatasetRef.local("customers")))
                    .anySatisfy(problem -> assertThat(problem)
                            .contains("is not on this machine")
                            .contains("make it portable"));
        }

        @Test
        @DisplayName("a dataset that should have been committed says to add it to the repository")
        void aMissingPortableDatasetExplainsWhy() {
            assertThat(problemsOf(dev.vortex.core.data.DatasetRef.portable("customers")))
                    .anySatisfy(problem -> assertThat(problem)
                            .contains("should be committed with this service")
                            .contains("Add it to the repository"));
        }

        @Test
        @DisplayName("scope is part of the reference, so a local dataset is not answered by a portable one")
        void scopeIsNotResolvedByPrecedence() {
            datasets.withPortable("customers", List.of("customerId"),
                    List.of(java.util.Map.of("customerId", "C001")));

            assertThat(problemsOf(dev.vortex.core.data.DatasetRef.local("customers")))
                    .anySatisfy(problem -> assertThat(problem).contains("is not on this machine"));
        }

        @Test
        @DisplayName("an empty dataset stops the run rather than supplying undefined values")
        void emptyDatasetsAreRefused() {
            datasets.with("customers", List.of("customerId"), List.of());

            assertThat(problemsOf(dev.vortex.core.data.DatasetRef.local("customers")))
                    .anySatisfy(problem -> assertThat(problem)
                            .contains("has no rows")
                            .contains("would be undefined"));
        }

        @Test
        @DisplayName("a plan reading no datasets carries none, and needs no store to have anything")
        void plansWithoutDatasetsCarryNone() {
            assertThat(resolve(Fixtures.configuration(), "average-load").datasets()).isEmpty();
        }

        private List<String> problemsOf(dev.vortex.core.data.DatasetRef ref) {
            try {
                resolve(boundTo(ref, "customerId"), "average-load");
                throw new AssertionError("expected resolution to fail");
            } catch (PlanResolutionException e) {
                return e.problems();
            }
        }
    }

    @Nested
    @DisplayName("request data Vortex can already tell will not work")
    class RequestDataValidation {

        private ProjectConfiguration bound(dev.vortex.core.data.RequestData requestData) {
            return Fixtures.configuration().withBinding(
                    Fixtures.configuration().bindingOrDefault(Fixtures.GET_ORDER)
                            .withRequestData(requestData));
        }

        private List<String> problemsFor(dev.vortex.core.data.RequestData requestData) {
            try {
                resolve(bound(requestData), "average-load");
                throw new AssertionError("expected resolution to fail");
            } catch (PlanResolutionException e) {
                return e.problems();
            }
        }

        @Test
        @DisplayName("a mistyped column names the column, the dataset, and the columns that exist")
        void unknownColumnsAreNamed() {
            // The message the whole check exists for. k6 would eventually fail with an error about
            // an undefined property, which costs a run and says nothing about the typo.
            datasets.with("customers", List.of("customerId", "mobile"),
                    List.of(java.util.Map.of("customerId", "C001", "mobile", "0917")));

            assertThat(problemsFor(new dev.vortex.core.data.RequestData(
                    java.util.Map.of("id", new dev.vortex.core.data.DatasetValue(
                            dev.vortex.core.data.DatasetRef.local("customers"), "customer_id")),
                    java.util.Map.of(), java.util.Map.of(), "", java.util.Map.of())))
                    .anySatisfy(problem -> assertThat(problem)
                            .contains("reads column 'customer_id'")
                            .contains("customers")
                            .contains("Available columns: customerId, mobile"));
        }

        @Test
        @DisplayName("a line break in a fixed header value is refused, because it can forge headers")
        void lineBreaksInHeadersAreRefused() {
            assertThat(problemsFor(new dev.vortex.core.data.RequestData(
                    java.util.Map.of(), java.util.Map.of(),
                    java.util.Map.of("X-Tenant",
                            new dev.vortex.core.data.FixedValue("acme\r\nX-Admin: true")),
                    "", java.util.Map.of())))
                    .anySatisfy(problem -> assertThat(problem)
                            .contains("X-Tenant")
                            .contains("line break")
                            .contains("forge additional headers"));
        }

        @Test
        @DisplayName("a dataset column bound to a header is checked row by row, and the row is named")
        void datasetColumnsBoundToHeadersAreScanned() {
            datasets.with("tenants", List.of("name"),
                    List.of(java.util.Map.of("name", "acme"),
                            java.util.Map.of("name", "beta\r\nX-Admin: true")));

            assertThat(problemsFor(new dev.vortex.core.data.RequestData(
                    java.util.Map.of(), java.util.Map.of(),
                    java.util.Map.of("X-Tenant", new dev.vortex.core.data.DatasetValue(
                            dev.vortex.core.data.DatasetRef.local("tenants"), "name")),
                    "", java.util.Map.of())))
                    .anySatisfy(problem -> assertThat(problem)
                            .contains("row 2 of tenants")
                            .contains("a body field may contain anything"));
        }

        @Test
        @DisplayName("the same content is fine in a body field, because there it is just text")
        void theSameValueIsLegalInABody() {
            // Validation is per destination on purpose. Rejecting multi-line text everywhere would
            // corrupt legitimate data — an address, a description — to defend a position it never
            // reaches.
            datasets.with("tenants", List.of("name"),
                    List.of(java.util.Map.of("name", "acme\nsecond line")));

            var plan = resolve(bound(new dev.vortex.core.data.RequestData(
                    java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                    "{\"tenant\":\"\"}",
                    java.util.Map.of(dev.vortex.core.data.BodyFieldPath.parse("tenant"),
                            new dev.vortex.core.data.DatasetValue(
                                    dev.vortex.core.data.DatasetRef.local("tenants"), "name")))),
                    "average-load");

            assertThat(plan.operation(Fixtures.GET_ORDER)).isPresent();
        }

        @Test
        @DisplayName("binding body fields onto a body that is not a JSON object is refused")
        void bodyFieldsNeedAJsonObject() {
            assertThat(problemsFor(new dev.vortex.core.data.RequestData(
                    java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                    "plain text, not a document",
                    java.util.Map.of(dev.vortex.core.data.BodyFieldPath.parse("id"),
                            new dev.vortex.core.data.FixedValue("x")))))
                    .anySatisfy(problem -> assertThat(problem)
                            .contains("not a JSON object")
                            .contains("named properties"));
        }

        @Test
        @DisplayName("a fixed value the specification will reject stops the run, naming what it permits")
        void fixedValuesOutsideAnEnumAreRefused() {
            // Sending it would produce a hundred per cent error rate that somebody then has to
            // interpret, when Vortex already had the document that says it cannot work.
            var catalog = catalogWithEnum();
            var configuration = bound(new dev.vortex.core.data.RequestData(
                    java.util.Map.of(), java.util.Map.of("channel",
                            new dev.vortex.core.data.FixedValue("FAX")),
                    java.util.Map.of(), "", java.util.Map.of()));

            assertThatThrownBy(() -> resolve(configuration, "average-load", catalog))
                    .isInstanceOf(PlanResolutionException.class)
                    .satisfies(thrown -> assertThat(((PlanResolutionException) thrown).problems())
                            .anySatisfy(problem -> assertThat(problem)
                                    .contains("'channel' is set to \"FAX\"")
                                    .contains("permits only WEB, MOBILE")));
        }

        @Test
        @DisplayName("a dataset bound to a constrained field is not judged against the schema")
        void datasetValuesAreNotCheckedAgainstEnums() {
            // A schema narrower than reality is frequently the schema's fault, and refusing curated
            // test data on that basis would be Vortex deciding it knows the business better.
            datasets.with("channels", List.of("name"),
                    List.of(java.util.Map.of("name", "FAX")));

            var plan = resolve(bound(new dev.vortex.core.data.RequestData(
                    java.util.Map.of(), java.util.Map.of("channel",
                            new dev.vortex.core.data.DatasetValue(
                                    dev.vortex.core.data.DatasetRef.local("channels"), "name")),
                    java.util.Map.of(), "", java.util.Map.of())), "average-load", catalogWithEnum());

            assertThat(plan.operation(Fixtures.GET_ORDER)).isPresent();
        }

        private ServiceCatalog catalogWithEnum() {
            var original = Fixtures.catalog().find(Fixtures.GET_ORDER).orElseThrow();
            var constrained = new dev.vortex.core.catalog.Operation(
                    original.id(), original.specOperationId(), original.method(), original.path(),
                    original.summary(), original.tags(),
                    List.of(new dev.vortex.core.catalog.ParameterSpec("channel",
                            dev.vortex.core.catalog.ParameterLocation.QUERY, false, "string", null,
                            "", List.of("WEB", "MOBILE"))),
                    original.requestBody());

            var operations = new java.util.ArrayList<>(Fixtures.catalog().operations());
            operations.replaceAll(op -> op.id().equals(Fixtures.GET_ORDER) ? constrained : op);
            return new ServiceCatalog(Fixtures.catalog().source(), Fixtures.catalog().sourceRef(),
                    Fixtures.catalog().title(), Fixtures.catalog().version(),
                    Fixtures.catalog().importedAt(), operations, List.of());
        }

        @Test
        @DisplayName("a generated value needs no checking, and is not scanned as though it did")
        void generatedValuesArePermitted() {
            var plan = resolve(bound(new dev.vortex.core.data.RequestData(
                    java.util.Map.of(), java.util.Map.of(),
                    java.util.Map.of("X-Request-Id", dev.vortex.core.data.GeneratedValue.of(
                            dev.vortex.core.data.Generator.UUID)),
                    "", java.util.Map.of())), "average-load");

            assertThat(plan.operation(Fixtures.GET_ORDER).orElseThrow().headers())
                    .containsKey("X-Request-Id");
        }
    }
}
