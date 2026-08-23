package com.acltabontabon.vortex.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acltabontabon.vortex.core.application.CatalogImportService;
import com.acltabontabon.vortex.core.application.ProjectService;
import com.acltabontabon.vortex.core.catalog.CatalogSource;
import com.acltabontabon.vortex.core.catalog.HttpMethod;
import com.acltabontabon.vortex.core.catalog.Operation;
import com.acltabontabon.vortex.core.catalog.ParameterLocation;
import com.acltabontabon.vortex.core.catalog.ParameterSpec;
import com.acltabontabon.vortex.core.catalog.RequestBodySpec;
import com.acltabontabon.vortex.core.catalog.SchemaHint;
import com.acltabontabon.vortex.core.catalog.ServiceCatalog;
import com.acltabontabon.vortex.core.data.Dataset;
import com.acltabontabon.vortex.core.data.DatasetFormat;
import com.acltabontabon.vortex.core.data.DatasetRecords;
import com.acltabontabon.vortex.core.data.DatasetRef;
import com.acltabontabon.vortex.core.data.DatasetScope;
import com.acltabontabon.vortex.core.data.DatasetValue;
import com.acltabontabon.vortex.core.data.GeneratedValue;
import com.acltabontabon.vortex.core.data.Generator;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.port.DatasetStore;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.shared.OperationId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The surface a person uses to say what a request needs.
 *
 * <p>Asserts semantic structure rather than a rendering: that a slot exists for every value the
 * specification declares, that a suggestion arrives labelled as one, and that a source round-trips
 * from the form back into the binding.
 */
@WebMvcTest(controllers = RequestDataApiController.class)
class RequestDataApiControllerTest {

    private static final String SERVICE = "checkout";
    private static final OperationId CREATE = OperationId.of("createApplication");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProjectService projects;
    @MockitoBean
    private CatalogImportService catalogs;
    @MockitoBean
    private DatasetStore datasets;

    private final Operation operation = new Operation(
            CREATE, "createApplication", HttpMethod.POST, "/applications/{id}",
            "Create an application", List.of("Applications"),
            List.of(new ParameterSpec("id", ParameterLocation.PATH, true, "string", null,
                            "uuid", List.of()),
                    new ParameterSpec("channel", ParameterLocation.QUERY, false, "string", null,
                            "", List.of("WEB", "MOBILE"))),
            RequestBodySpec.schemaGenerated("application/json",
                    "{\"customerId\":\"\",\"requestId\":\"\"}",
                    List.of(new SchemaHint("customerId", "string", "", List.of()),
                            new SchemaHint("requestId", "string", "uuid", List.of()))));

    @BeforeEach
    void setUp() {
        when(projects.find(any())).thenReturn(Optional.of(Fixtures.project()));
        when(projects.configuration(any())).thenReturn(Fixtures.configuration());
        when(catalogs.catalog(any())).thenReturn(Optional.of(new ServiceCatalog(
                CatalogSource.OPENAPI, "lending.yaml", "Lending", "1.0", Instant.EPOCH,
                List.of(operation), List.of())));
        when(datasets.list(any())).thenReturn(List.of());
    }

    @Nested
    @DisplayName("reading what a request can carry")
    class Reading {

        @Test
        @DisplayName("every value the specification declares gets a slot, configured or not")
        void slotsComeFromTheSpecification() throws Exception {
            // An empty page a user has to guess at would be the wrong starting point. The endpoint
            // already knows which parameters exist.
            mvc.perform(get("/api/services/{id}/operations/{op}/request-data", SERVICE, CREATE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.values[?(@.name == 'id')].target").value("PATH"))
                    .andExpect(jsonPath("$.values[?(@.name == 'channel')].target").value("QUERY"))
                    .andExpect(jsonPath("$.values[?(@.name == 'customerId')].target")
                            .value("BODY_FIELD"));
        }

        @Test
        @DisplayName("an unconfigured value has no source, rather than a source Vortex chose")
        void unconfiguredValuesHaveNoSource() throws Exception {
            mvc.perform(get("/api/services/{id}/operations/{op}/request-data", SERVICE, CREATE))
                    .andExpect(jsonPath("$.values[?(@.name == 'customerId')].source").value(""));
        }

        @Test
        @DisplayName("a schema hint arrives as a suggestion, with the reason it was made")
        void suggestionsCarryTheirReason() throws Exception {
            mvc.perform(get("/api/services/{id}/operations/{op}/request-data", SERVICE, CREATE))
                    .andExpect(jsonPath("$.values[?(@.name == 'requestId')].suggestion.generator")
                            .value("uuid"))
                    .andExpect(jsonPath("$.values[?(@.name == 'requestId')].suggestion.reason")
                            .value("the specification declares format: uuid"));
        }

        @Test
        @DisplayName("an enum arrives as the choices themselves, not as a generator")
        void constrainedValuesOfferTheirChoices() throws Exception {
            mvc.perform(get("/api/services/{id}/operations/{op}/request-data", SERVICE, CREATE))
                    .andExpect(jsonPath("$.values[?(@.name == 'channel')].suggestion.choices[0]")
                            .value("WEB"));
        }

        @Test
        @DisplayName("the generators are described, so the interface never hard-codes the list")
        void generatorsAreDescribed() throws Exception {
            mvc.perform(get("/api/services/{id}/operations/{op}/request-data", SERVICE, CREATE))
                    .andExpect(jsonPath("$.generators[?(@.key == 'uuid')].label").value("UUID"))
                    .andExpect(jsonPath("$.generators[?(@.key == 'random-integer')].usesRange")
                            .value(true));
        }

        @Test
        @DisplayName("an operation the imported description does not have is a 404, not an empty form")
        void unknownOperationsAreNotFound() throws Exception {
            mvc.perform(get("/api/services/{id}/operations/{op}/request-data", SERVICE, "nope"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("saving a source")
    class Saving {

        @Test
        @DisplayName("a generated value round-trips into the binding, with its lifecycle")
        void generatedValuesAreSaved() throws Exception {
            mvc.perform(post("/api/services/{id}/operations/{op}/request-data", SERVICE, CREATE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"body": "", "values": [
                                      {"target": "HEADER", "name": "x-idempotency-key",
                                       "source": "generated", "generator": "uuid",
                                       "lifecycle": "per-request"}
                                    ]}"""))
                    .andExpect(status().isOk());

            assertThat(savedBinding().headers()).containsEntry("x-idempotency-key",
                    GeneratedValue.of(Generator.UUID));
        }

        @Test
        @DisplayName("a dataset value keeps its scope, so nothing is resolved by precedence later")
        void datasetValuesKeepTheirScope() throws Exception {
            mvc.perform(post("/api/services/{id}/operations/{op}/request-data", SERVICE, CREATE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"body": "", "values": [
                                      {"target": "PATH", "name": "id", "source": "dataset",
                                       "dataset": "customers", "datasetScope": "portable",
                                       "field": "customerId"}
                                    ]}"""))
                    .andExpect(status().isOk());

            assertThat(savedBinding().pathValues()).containsEntry("id",
                    new DatasetValue(DatasetRef.portable("customers"), "customerId"));
        }

        @Test
        @DisplayName("a slot with no source is left unconfigured rather than saved as empty")
        void unsourcedSlotsAreNotSaved() throws Exception {
            // "Not configured" and "configured as empty" are different: the first leaves the
            // specification's own default in play, and the second overrides it with nothing.
            mvc.perform(post("/api/services/{id}/operations/{op}/request-data", SERVICE, CREATE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"body": "", "values": [
                                      {"target": "QUERY", "name": "channel", "source": ""}
                                    ]}"""))
                    .andExpect(status().isOk());

            assertThat(savedBinding().queryValues()).doesNotContainKey("channel");
        }

        @Test
        @DisplayName("a body identical to the schema's own is not recorded as a decision")
        void anUnchangedBodyIsNotStored() throws Exception {
            // Storing it would mean re-importing the specification could no longer update it.
            mvc.perform(post("/api/services/{id}/operations/{op}/request-data", SERVICE, CREATE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"body": "{\\"customerId\\":\\"\\",\\"requestId\\":\\"\\"}",
                                     "values": []}"""))
                    .andExpect(status().isOk());

            assertThat(savedBinding().body()).isEmpty();
        }

        @Test
        @DisplayName("an unknown generator is a bad request naming the ones that exist")
        void unknownGeneratorsAreRejected() throws Exception {
            mvc.perform(post("/api/services/{id}/operations/{op}/request-data", SERVICE, CREATE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"body": "", "values": [
                                      {"target": "HEADER", "name": "x-thing",
                                       "source": "generated", "generator": "creditCardNumber"}
                                    ]}"""))
                    .andExpect(status().isBadRequest());

            verify(projects, never()).saveConfiguration(any(), any());
        }

        private com.acltabontabon.vortex.core.catalog.OperationBinding savedBinding() {
            ArgumentCaptor<ProjectConfiguration> saved =
                    ArgumentCaptor.forClass(ProjectConfiguration.class);
            verify(projects).saveConfiguration(any(), saved.capture());
            return saved.getValue().bindingOrDefault(CREATE);
        }
    }

    @Nested
    @DisplayName("datasets")
    class Datasets {

        @Test
        @DisplayName("an upload is local unless somebody says otherwise")
        void uploadsAreLocalByDefault() throws Exception {
            when(datasets.store(any(), any(), any(), any(), any())).thenReturn(
                    new Dataset(DatasetRef.local("customers"), DatasetFormat.CSV,
                            List.of("customerId"), 2, "hash", Instant.EPOCH, "/tmp/customers.csv"));
            when(datasets.read(any(), any())).thenReturn(new DatasetRecords(List.of("customerId"),
                    List.of(Map.of("customerId", "C001"), Map.of("customerId", "C002"))));

            mvc.perform(post("/api/services/{id}/datasets", SERVICE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name": "customers", "format": "csv",
                                     "content": "customerId\\nC001\\nC002\\n"}"""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.scope").value("local"));

            // A file dragged into a browser must not become a commit in somebody's repository.
            verify(datasets).store(any(), eq(DatasetScope.LOCAL), eq("customers"), any(), any());
        }

        @Test
        @DisplayName("a preview is a few records, never the dataset")
        void previewsAreSmall() throws Exception {
            when(datasets.list(any())).thenReturn(List.of(new Dataset(DatasetRef.local("customers"),
                    DatasetFormat.CSV, List.of("customerId"), 5000, "hash", Instant.EPOCH, "/tmp")));
            when(datasets.read(any(), any())).thenReturn(new DatasetRecords(List.of("customerId"),
                    java.util.stream.IntStream.range(0, 5000)
                            .mapToObj(i -> Map.<String, Object>of("customerId", "C" + i))
                            .toList()));

            mvc.perform(get("/api/services/{id}/datasets", SERVICE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].records").value(5000))
                    .andExpect(jsonPath("$[0].preview.length()").value(5));
        }

        @Test
        @DisplayName("a local dataset says which file making it portable would write")
        void promotionTargetIsShownBeforeItHappens() throws Exception {
            when(datasets.list(any())).thenReturn(List.of(new Dataset(DatasetRef.local("customers"),
                    DatasetFormat.CSV, List.of("customerId"), 2, "hash", Instant.EPOCH, "/tmp")));
            when(datasets.read(any(), any())).thenReturn(new DatasetRecords(List.of("customerId"),
                    List.of(Map.of("customerId", "C001"))));
            when(datasets.promotionTarget(any(), any()))
                    .thenReturn("/repo/.vortex/datasets/customers.csv");

            mvc.perform(get("/api/services/{id}/datasets", SERVICE))
                    .andExpect(jsonPath("$[0].promotionTarget")
                            .value("/repo/.vortex/datasets/customers.csv"));
        }

        @Test
        @DisplayName("a dataset that no longer parses is listed with the reason, not omitted")
        void unreadableDatasetsAreStillListed() throws Exception {
            // Omitting it would leave a request value pointing at something invisible.
            when(datasets.list(any())).thenReturn(List.of(new Dataset(DatasetRef.local("customers"),
                    DatasetFormat.CSV, List.of(), 0, "", Instant.EPOCH, "/tmp")));
            when(datasets.read(any(), any())).thenThrow(new com.acltabontabon.vortex.core.data.DatasetException(
                    "broken", List.of(new com.acltabontabon.vortex.core.data.DatasetProblem("column 'id'",
                            "appears more than once.", "Rename one."))));

            mvc.perform(get("/api/services/{id}/datasets", SERVICE))
                    .andExpect(jsonPath("$[0].problem").value(
                            "column 'id': appears more than once. Rename one."));
        }
    }
}
