package dev.vortex.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vortex.core.catalog.CatalogSource;
import dev.vortex.core.catalog.OperationBinding;
import dev.vortex.core.catalog.HttpMethod;
import dev.vortex.core.application.RequestDataSuggestions;
import dev.vortex.core.data.Generator;
import dev.vortex.core.catalog.Operation;
import dev.vortex.core.catalog.OperationKind;
import dev.vortex.core.catalog.PayloadProvenance;
import dev.vortex.core.catalog.ServiceCatalog;
import dev.vortex.core.port.ServiceCatalogImporter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OpenApiCatalogImporterTest {

    private final OpenApiCatalogImporter importer = new OpenApiCatalogImporter(
            Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC));

    private static String fixture(String name) {
        try (var in = OpenApiCatalogImporterTest.class.getResourceAsStream("/openapi/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ServiceCatalog importFixture(String name) {
        return importer.importFrom(name, fixture(name));
    }

    @Nested
    @DisplayName("discovery is deterministic")
    class Discovery {

        @Test
        void everyOperationIsFound() {
            ServiceCatalog catalog = importFixture("checkout-service.yaml");

            assertThat(catalog.operationCount()).isEqualTo(4);
            assertThat(catalog.operations()).extracting(Operation::label)
                    .containsExactlyInAnyOrder(
                            "GET /accounts/{id}",
                            "POST /orders",
                            "GET /orders/{id}",
                            "POST /orders/{id}/cancel");
        }

        @Test
        void serviceIdentityIsRecorded() {
            ServiceCatalog catalog = importFixture("checkout-service.yaml");

            assertThat(catalog.title()).isEqualTo("checkout-service");
            assertThat(catalog.version()).isEqualTo("1.0.0");
            assertThat(catalog.source()).isEqualTo(CatalogSource.OPENAPI);
            assertThat(catalog.importedAt()).isEqualTo(Instant.parse("2026-08-21T10:00:00Z"));
        }

        @Test
        @DisplayName("importing the same document twice produces identical identifiers")
        void importIsRepeatable() {
            var first = importFixture("checkout-service.yaml");
            var second = importFixture("checkout-service.yaml");

            assertThat(first.operations()).extracting(operation -> operation.id().value())
                    .isEqualTo(second.operations().stream().map(o -> o.id().value()).toList());
        }

        @Test
        void identifiersComeFromTheSpecificationWhereItProvidesThem() {
            assertThat(importFixture("checkout-service.yaml").operations())
                    .extracting(operation -> operation.id().value())
                    .contains("getAccount", "createOrder", "getOrder", "cancelOrder");
        }

        @Test
        void operationsAreGroupedByTheirTags() {
            var grouped = importFixture("checkout-service.yaml").groupedByTag();

            assertThat(grouped).containsOnlyKeys("Accounts", "Orders");
            assertThat(grouped.get("Orders")).hasSize(3);
        }

        @Test
        void parametersAndTheirExamplesAreCaptured() {
            Operation getAccount = importFixture("checkout-service.yaml")
                    .operations().stream()
                    .filter(operation -> operation.id().value().equals("getAccount"))
                    .findFirst().orElseThrow();

            assertThat(getAccount.parameters()).singleElement().satisfies(parameter -> {
                assertThat(parameter.name()).isEqualTo("id");
                assertThat(parameter.required()).isTrue();
                assertThat(parameter.example()).isEqualTo("acc-1001");
            });
        }
    }

    @Nested
    @DisplayName("what the schema says about a value's shape")
    class SchemaShape {

        private static final String SPEC = """
                openapi: 3.0.3
                info: { title: Lending, version: "1.0" }
                paths:
                  /applications/{id}:
                    post:
                      operationId: createApplication
                      parameters:
                        - name: id
                          in: path
                          required: true
                          schema: { type: string, format: uuid }
                        - name: channel
                          in: query
                          schema: { type: string, enum: [WEB, MOBILE, BRANCH] }
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                requestId: { type: string, format: uuid }
                                submittedAt: { type: string, format: date-time }
                                productType: { type: string, enum: [CREDIT_CARD, PERSONAL_LOAN] }
                                customerId: { type: string }
                                payment:
                                  type: object
                                  properties:
                                    token: { type: string, format: uuid }
                      responses: { "201": { description: created } }
                """;

        private Operation operation() {
            return importer.importFrom("lending.yaml", SPEC).operations().stream()
                    .filter(candidate -> candidate.id().value().equals("createApplication"))
                    .findFirst().orElseThrow();
        }

        @Test
        @DisplayName("a parameter's format and permitted values survive the import")
        void parameterShapeIsKept() {
            // These were read and then discarded: the importer used the format to invent a sample
            // and threw away the knowledge that produced it, so nothing downstream could offer it.
            var parameters = operation().parameters();

            assertThat(parameters).filteredOn(p -> p.name().equals("id"))
                    .singleElement()
                    .satisfies(p -> assertThat(p.format()).isEqualTo("uuid"));
            assertThat(parameters).filteredOn(p -> p.name().equals("channel"))
                    .singleElement()
                    .satisfies(p -> assertThat(p.enumValues())
                            .containsExactly("WEB", "MOBILE", "BRANCH"));
        }

        @Test
        @DisplayName("body fields are described by the name Vortex's own mapping would use")
        void bodyFieldsAreAddressedByTheMappingGrammar() {
            var fields = operation().body().orElseThrow().fields();

            assertThat(fields).extracting(dev.vortex.core.catalog.SchemaHint::field)
                    .contains("requestId", "submittedAt", "productType", "customerId",
                            "payment.token");
        }

        @Test
        @DisplayName("a field declared only as a string carries no format to act on")
        void plainStringsCarryNoHint() {
            var fields = operation().body().orElseThrow().fields();

            assertThat(fields).filteredOn(hint -> hint.field().equals("customerId"))
                    .singleElement()
                    .satisfies(hint -> {
                        assertThat(hint.hasFormat()).isFalse();
                        assertThat(hint.isConstrained()).isFalse();
                    });
        }

        @Test
        @DisplayName("a format Vortex recognises becomes a suggestion, with the reason attached")
        void recognisedFormatsBecomeSuggestions() {
            var suggestions = RequestDataSuggestions.forOperation(operation());

            assertThat(suggestions).filteredOn(s -> s.field().equals("requestId"))
                    .singleElement()
                    .satisfies(s -> {
                        assertThat(s.generatorIfPresent()).contains(Generator.UUID);
                        assertThat(s.reason()).isEqualTo(
                                "the specification declares format: uuid");
                    });
            assertThat(suggestions).filteredOn(s -> s.field().equals("submittedAt"))
                    .singleElement()
                    .satisfies(s -> assertThat(s.generatorIfPresent())
                            .contains(Generator.TIMESTAMP));
        }

        @Test
        @DisplayName("an enum becomes a constrained choice rather than a generator")
        void enumsBecomeChoices() {
            var suggestions = RequestDataSuggestions.forOperation(operation());

            assertThat(suggestions).filteredOn(s -> s.field().equals("productType"))
                    .singleElement()
                    .satisfies(s -> {
                        assertThat(s.isConstrainedChoice()).isTrue();
                        assertThat(s.choices()).containsExactly("CREDIT_CARD", "PERSONAL_LOAN");
                        assertThat(s.generatorIfPresent()).isEmpty();
                    });
        }

        @Test
        @DisplayName("a field the schema says nothing useful about is not suggested at all")
        void nothingIsSuggestedWithoutAReason() {
            // Silence is the correct answer far more often than a guess. Offering "Random string"
            // for every untyped field would devalue the suggestions worth accepting.
            var suggestions = RequestDataSuggestions.forOperation(operation());

            assertThat(suggestions).extracting(RequestDataSuggestions::field)
                    .doesNotContain("customerId");
        }

        @Test
        @DisplayName("a suggestion says how a value looks, never what it means")
        void suggestionsAreNotDecisions() {
            // format: uuid on a customer id says the field holds a UUID. Whether that UUID should
            // be generated, or an existing customer's, is a business fact no schema carries — so
            // this is offered with its reason and never applied.
            var suggestion = RequestDataSuggestions.forOperation(operation()).stream()
                    .filter(s -> s.field().equals("payment.token"))
                    .findFirst().orElseThrow();

            assertThat(suggestion.reason()).startsWith("the specification declares");
        }
    }

    @Nested
    @DisplayName("mutating operations are gated")
    class MutationGate {

        @Test
        void readOperationsNeedNoReview() {
            var reads = importFixture("checkout-service.yaml").readOperations();

            assertThat(reads).hasSize(2);
            assertThat(reads).allSatisfy(operation -> {
                assertThat(operation.requiresReview()).isFalse();
                assertThat(operation.isExecutable(null)).isTrue();
            });
        }

        @Test
        @DisplayName("an operation that changes data is not executable until a person reviews it")
        void mutatingOperationsStartLocked() {
            var mutations = importFixture("checkout-service.yaml").mutatingOperations();

            assertThat(mutations).hasSize(2);
            assertThat(mutations).allSatisfy(operation -> {
                assertThat(operation.kind()).isEqualTo(OperationKind.MUTATION);
                assertThat(operation.requiresReview()).isTrue();
                // No binding means nobody has approved it. Importing a specification can never be
                // the act that makes a mutating operation runnable.
                assertThat(operation.isExecutable(OperationBinding.of(operation.id())))
                        .as("%s must not be runnable before review", operation.label())
                        .isFalse();
            });
        }

        @Test
        @DisplayName("a generated body is labelled schema-valid, never business-validated")
        void generatedBodiesAreLabelled() {
            Operation createOrder = importFixture("checkout-service.yaml")
                    .operations().stream()
                    .filter(operation -> operation.id().value().equals("createOrder"))
                    .findFirst().orElseThrow();

            assertThat(createOrder.body()).hasValueSatisfying(body -> {
                assertThat(body.provenance()).isEqualTo(PayloadProvenance.SCHEMA_GENERATED);
                assertThat(body.provenance().label()).isEqualTo("Schema-valid — not business-validated");
                assertThat(body.payload()).contains("accountId").contains("amount");
            });
        }

        @Test
        void reviewingAnOperationMakesItExecutable() {
            Operation createOrder = importFixture("checkout-service.yaml")
                    .operations().stream()
                    .filter(operation -> operation.method() == HttpMethod.POST)
                    .findFirst().orElseThrow();

            var reviewed = OperationBinding.of(createOrder.id()).withReviewed(true);
            assertThat(createOrder.isExecutable(reviewed)).isTrue();
        }
    }

    @Nested
    @DisplayName("imported text is treated as data")
    class UntrustedInput {

        @Test
        @DisplayName("instruction-shaped descriptions are imported as ordinary, truncated text")
        void hostileDescriptionsAreJustText() {
            ServiceCatalog catalog = importFixture("hostile-descriptions.yaml");

            Operation listItems = catalog.operations().stream()
                    .filter(operation -> operation.id().value().equals("listItems"))
                    .findFirst().orElseThrow();

            // The text is preserved — hiding it would be worse, since a person should see what
            // their specification says. What matters is that it is bounded, clean, and only ever
            // used as data.
            assertThat(listItems.summary()).isNotBlank();
            assertThat(listItems.summary().length()).isLessThanOrEqualTo(201);
            assertThat(listItems.summary()).doesNotContain("\n").doesNotContain("\r");
        }

        @Test
        void aDeleteOperationFromAnUntrustedDocumentIsStillGated() {
            ServiceCatalog catalog = importFixture("hostile-descriptions.yaml");

            Operation delete = catalog.operations().stream()
                    .filter(operation -> operation.method() == HttpMethod.DELETE)
                    .findFirst().orElseThrow();

            assertThat(delete.isExecutable(OperationBinding.of(delete.id()))).isFalse();
            assertThat(delete.requiresReview()).isTrue();
        }

        @Test
        void controlCharactersAreStripped() {
            String withControlChars = "before" + (char) 7 + (char) 0 + "after";

            assertThat(UntrustedText.summary(withControlChars)).isEqualTo("beforeafter");
        }

        @Test
        void longTextIsTruncatedWithAMarker() {
            String long_ = "x".repeat(1000);

            assertThat(UntrustedText.summary(long_)).hasSize(201).endsWith("…");
        }
    }

    @Nested
    @DisplayName("failures explain themselves")
    class Failures {

        @Test
        void anEmptyDocumentIsRejectedWithGuidance() {
            assertThatThrownBy(() -> importer.importFrom("openapi.yaml", ""))
                    .isInstanceOf(ServiceCatalogImporter.ImportException.class)
                    .hasMessageContaining("empty");
        }

        @Test
        @DisplayName("valid YAML that is not an API description says so, rather than importing nothing")
        void nonApiDocumentsAreRejected() {
            assertThatThrownBy(() -> importFixture("not-openapi.yaml"))
                    .isInstanceOf(ServiceCatalogImporter.ImportException.class)
                    .satisfies(thrown -> {
                        var problems = ((ServiceCatalogImporter.ImportException) thrown).problems();
                        assertThat(String.join(" ", problems))
                                .containsAnyOf("declared no paths", "OpenAPI 3.x");
                    });
        }

        @Test
        void garbageIsRejected() {
            assertThatThrownBy(() -> importer.importFrom("openapi.yaml", "this is not a document"))
                    .isInstanceOf(ServiceCatalogImporter.ImportException.class);
        }
    }

    @Test
    void theImporterRecognisesTheFormatsItSupports() {
        assertThat(importer.supports("openapi.yaml")).isTrue();
        assertThat(importer.supports("spec.json")).isTrue();
        assertThat(importer.supports("http://localhost:8080/openapi.yml")).isTrue();
        assertThat(importer.supports("script.js")).isFalse();
        assertThat(importer.supports(null)).isFalse();
    }
}
