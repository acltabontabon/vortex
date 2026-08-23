package dev.vortex.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vortex.app.service.LocalLabRunner;
import dev.vortex.core.application.CalibrationService;
import dev.vortex.core.application.CatalogImportService;
import dev.vortex.core.application.ProjectService;
import dev.vortex.core.calibration.CalibrationPolicy;
import dev.vortex.core.calibration.WorkloadDrift;
import dev.vortex.core.capacity.ProductionObservation;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.port.LocalLab;
import dev.vortex.core.port.ProductionObservationSource.NotRetrieved;
import dev.vortex.core.port.ProductionObservationSource.Retrieved;
import dev.vortex.core.project.ProjectConfiguration;
import dev.vortex.core.shared.RequestsPerSecond;
import dev.vortex.core.workload.Observation;
import dev.vortex.core.workload.RateAllocator;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Configuration's read and its eight forms — each mirrors a Thymeleaf-era
 * {@code ProjectController}/{@code LocalLabController} method, so these tests assert the same
 * validation and the same messages, at the JSON edge instead of a redirect and a flash.
 */
@WebMvcTest(controllers = ConfigurationApiController.class)
@Import({Display.class, WorkloadView.class, WorkloadDiagramRenderer.class,
        LoadAxisRenderer.class, RateAllocator.class,
        WorkspaceAssembler.class})
class ConfigurationApiControllerTest {

    private static final String SERVICE = "checkout";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProjectService projects;
    @MockitoBean
    private CatalogImportService catalogs;
    @MockitoBean
    private CalibrationPolicy calibration;
    @MockitoBean
    private CalibrationService calibrationService;
    @MockitoBean
    private LocalLabRunner lab;
    @MockitoBean
    private dev.vortex.core.application.CapacityService capacityService;
    @MockitoBean
    private dev.vortex.app.service.TestRunner testRunner;
    @MockitoBean
    private WorkloadDrift drift;

    @BeforeEach
    void aConfiguredService() {
        when(projects.find(any())).thenReturn(Optional.of(Fixtures.project()));
        when(projects.configuration(any())).thenReturn(Fixtures.configuration());
        when(projects.catalog(any())).thenReturn(Optional.of(Fixtures.catalog()));
        when(projects.renderConfiguration(any())).thenReturn("version: 1\n");
        when(lab.status()).thenReturn(new LocalLab.LabStatus(true, true, true, "24.0.0", ""));
    }

    private ProjectConfiguration saved() {
        ArgumentCaptor<ProjectConfiguration> captor = ArgumentCaptor.forClass(ProjectConfiguration.class);
        verify(projects).saveConfiguration(any(), captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("assembles every section from the current configuration")
        void assemblesEverySection() throws Exception {
            mvc.perform(get("/api/services/" + SERVICE + "/configuration"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.environments[0].name").value("local"))
                    .andExpect(jsonPath("$.environmentTypes").isArray())
                    .andExpect(jsonPath("$.dependencyModes").isArray())
                    .andExpect(jsonPath("$.localLab.configured").value(false))
                    .andExpect(jsonPath("$.catalog.imported").value(true))
                    .andExpect(jsonPath("$.file.yaml").value("version: 1\n"));
        }
    }

    @Nested
    @DisplayName("environments")
    class Environments {

        @Test
        @DisplayName("saves a new environment and classifies it")
        void savesANewEnvironment() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/environments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"Staging","baseUrl":"https://staging.example.com",
                                     "type":"STAGING","dependencies":"REAL","productionLike":true}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("Environment 'staging' saved")));

            var env = saved().environmentByName("staging").orElseThrow();
            assertThat(env.type().name()).isEqualTo("STAGING");
            assertThat(env.classification().name()).isEqualTo("INTEGRATED");
        }

        @Test
        @DisplayName("replaces an existing environment with the same name rather than duplicating")
        void replacesRatherThanDuplicates() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/environments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"local","baseUrl":"http://localhost:9090",
                                     "type":"LOCAL_ISOLATED","dependencies":"MOCKED"}
                                    """))
                    .andExpect(status().isOk());

            var configuration = saved();
            assertThat(configuration.environments()).hasSize(1);
            assertThat(configuration.environmentByName("local").orElseThrow().baseUrl().value())
                    .isEqualTo("http://localhost:9090");
        }

        @Test
        @DisplayName("rejects a blank name with the domain's own message")
        void rejectsABlankName() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/environments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"","baseUrl":"http://localhost:8080",
                                     "type":"LOCAL_ISOLATED","dependencies":"MOCKED"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("A name is required"));
        }
    }

    @Nested
    @DisplayName("release")
    class Release {

        @Test
        @DisplayName("records a release identifier")
        void recordsARelease() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/release")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"serviceVersion":"2.18.0"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(
                            "Runs will record release 2.18.0 until this changes."));

            assertThat(saved().serviceVersion()).isEqualTo("2.18.0");
        }

        @Test
        @DisplayName("clearing reports it distinctly")
        void clearingIsReportedDistinctly() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/release")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"serviceVersion":""}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(
                            "Release identifier cleared. Runs will record no release until one is set."));
        }
    }

    @Nested
    @DisplayName("objectives")
    class Objectives {

        @Test
        @DisplayName("saves all three thresholds")
        void savesAllThree() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/thresholds")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"p95Millis":500,"p99Millis":1000,"errorPercent":1.0}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("Objectives saved."));

            assertThat(saved().thresholds().thresholds()).hasSize(3);
        }

        @Test
        @DisplayName("omitting all three clears the whole set, not just what was given")
        void omittingAllClearsTheWholeSet() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/thresholds")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(
                            "Objectives cleared. Runs will produce measurements but no verdict."));

            assertThat(saved().thresholds().isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("production")
    class Production {

        @Test
        @DisplayName("an empty mix keeps the previously recorded one rather than clearing it")
        void emptyMixPreservesThePreviousOne() throws Exception {
            var existingMix = Fixtures.operationMix();
            var existing = new ProductionObservation(null, null, RequestsPerSecond.of(100),
                    existingMix, "Grafana", Observation.unknown(), "");
            when(projects.configuration(any()))
                    .thenReturn(Fixtures.configuration().withProductionObservation(existing));

            mvc.perform(post("/api/services/" + SERVICE + "/production")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"peakRate":150}
                                    """))
                    .andExpect(status().isOk());

            var savedObservation = saved().productionObservation();
            assertThat(savedObservation.peakRate().display()).isEqualTo("150");
            assertThat(savedObservation.observedMix()).isEqualTo(existingMix);
        }

        @Test
        @DisplayName("a zero peak is refused with the domain's own message")
        void aZeroPeakIsRefused() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/production")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"peakRate":0}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(
                            "is not an observation")));
        }

        @Test
        @DisplayName("fetch never saves, and reports why when there is nothing to fetch")
        void fetchNeverSaves() throws Exception {
            when(calibrationService.fetch(any(), any(), any())).thenReturn(new NotRetrieved(
                    "Cannot fetch production traffic", "no observation source is configured for this service.",
                    "Add an 'observation:' section to vortex.yaml."));

            mvc.perform(post("/api/services/" + SERVICE + "/production/fetch"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.succeeded").value(false));

            verify(projects, org.mockito.Mockito.never()).saveConfiguration(any(), any());
        }
    }

    @Nested
    @DisplayName("observation source")
    class ObservationSourceSection {

        @Test
        @DisplayName("saves a Prometheus source")
        void savesAPrometheusSource() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/observation")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"source":"prometheus","endpoint":"http://prometheus.internal:9090",
                                     "serviceIdentifier":"checkout-service","window":"30d"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("Prometheus")));

            assertThat(saved().observationSource().serviceIdentifier()).isEqualTo("checkout-service");
        }

        @Test
        @DisplayName("test connection never saves")
        void testConnectionNeverSaves() throws Exception {
            when(calibrationService.verify(any(), any())).thenReturn(
                    new Retrieved(new ProductionObservation(null, null, RequestsPerSecond.of(182),
                            null, "", Observation.unknown(), "")));

            mvc.perform(post("/api/services/" + SERVICE + "/observation/test")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"source":"prometheus","endpoint":"http://prometheus.internal:9090",
                                     "serviceIdentifier":"checkout-service","window":"30d"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.succeeded").value(true))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("182")));

            verify(projects, org.mockito.Mockito.never()).saveConfiguration(any(), any());
        }

        @Test
        @DisplayName("rejects an unrecognised system")
        void rejectsAnUnrecognisedSystem() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/observation")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"source":"datadog","endpoint":"http://x","serviceIdentifier":"y","window":"30d"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("Choose Prometheus or Dynatrace")));
        }
    }

    @Nested
    @DisplayName("importing")
    class Importing {

        @Test
        @DisplayName("neither a URL nor pasted content is reported, not silently ignored")
        void neitherUrlNorContentIsReported() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.succeeded").value(false))
                    .andExpect(jsonPath("$.error").value(
                            "Provide either a URL or the contents of your OpenAPI document."));
        }

        @Test
        @DisplayName("pasted content is imported directly, no fetch involved")
        void pastedContentIsImportedDirectly() throws Exception {
            when(catalogs.importCatalog(any(), any(), any())).thenReturn(Fixtures.catalog());

            mvc.perform(post("/api/services/" + SERVICE + "/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"content":"openapi: 3.0.0\\ninfo:\\n  title: checkout-service\\n"}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.succeeded").value(true))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Imported")));
        }
    }

    @Nested
    @DisplayName("reviewing an operation")
    class Reviewing {

        @Test
        @DisplayName("marks an operation reviewed")
        void marksAnOperationReviewed() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/operations/createOrder/review"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(
                            "Reviewed. This operation can now be used in a workload."));

            verify(projects).setOperationReviewed(any(), any(), org.mockito.ArgumentMatchers.eq(true));
        }
    }

    @Nested
    @DisplayName("local lab")
    class LocalLabSection {

        @Test
        @DisplayName("saving a compose file forgets any prior outcome")
        void savingForgetsAnyPriorOutcome() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/lab")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"composeFile":"compose.yaml"}
                                    """))
                    .andExpect(status().isOk());

            verify(lab).forget(any());
            assertThat(saved().localLab().composeFile()).isEqualTo("compose.yaml");
        }

        @Test
        @DisplayName("starting with no compose file configured is refused, not silently ignored")
        void startingWithNoComposeFileIsRefused() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/lab/up"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("nothing to start")));
        }

        @Test
        @DisplayName("starting when Docker itself is unusable reports the machine-level remedy")
        void startingWhenDockerIsUnusable(@org.junit.jupiter.api.io.TempDir java.nio.file.Path workspace)
                throws Exception {
            java.nio.file.Files.createFile(workspace.resolve("compose.yaml"));
            var project = new dev.vortex.core.project.Project(
                    dev.vortex.core.shared.ProjectId.of(SERVICE), "checkout-service", "",
                    workspace.toString(), "", java.time.Instant.now(), java.time.Instant.now());
            when(projects.find(any())).thenReturn(Optional.of(project));
            when(projects.configuration(any())).thenReturn(Fixtures.configuration()
                    .withLocalLab(new dev.vortex.core.lab.LocalLabSettings("compose.yaml")));
            when(lab.status()).thenReturn(new LocalLab.LabStatus(false, false, false, "",
                    "Install Docker Desktop."));

            mvc.perform(post("/api/services/" + SERVICE + "/lab/up"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Install Docker Desktop."));
        }
    }
}
