package com.acltabontabon.vortex.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acltabontabon.vortex.app.service.LocalLabRunner;
import com.acltabontabon.vortex.core.application.CalibrationService;
import com.acltabontabon.vortex.core.application.CatalogImportService;
import com.acltabontabon.vortex.core.application.PreflightCheck;
import com.acltabontabon.vortex.core.application.ProjectService;
import com.acltabontabon.vortex.core.calibration.CalibrationPolicy;
import com.acltabontabon.vortex.core.calibration.WorkloadDrift;
import com.acltabontabon.vortex.core.capacity.ProductionObservation;
import com.acltabontabon.vortex.core.environment.DependencyMode;
import com.acltabontabon.vortex.core.environment.Environment;
import com.acltabontabon.vortex.core.environment.EnvironmentCapabilities;
import com.acltabontabon.vortex.core.environment.EnvironmentType;
import com.acltabontabon.vortex.core.environment.SecretReferences;
import com.acltabontabon.vortex.core.environment.TargetUrl;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.metrics.ObservationProvenance;
import com.acltabontabon.vortex.core.port.LocalLab;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.NotRetrieved;
import com.acltabontabon.vortex.core.port.ProductionObservationSource.Retrieved;
import com.acltabontabon.vortex.core.port.TargetExecutor;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.shared.EnvironmentId;
import com.acltabontabon.vortex.core.shared.RequestsPerSecond;
import com.acltabontabon.vortex.core.target.ExternalEndpointTarget;
import com.acltabontabon.vortex.core.workload.Observation;
import com.acltabontabon.vortex.core.workload.RateAllocator;
import java.time.Duration;
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
    private com.acltabontabon.vortex.core.application.CapacityService capacityService;
    @MockitoBean
    private com.acltabontabon.vortex.app.service.TestRunner testRunner;
    @MockitoBean
    private WorkloadDrift drift;
    @MockitoBean
    private TargetExecutor targetExecutor;

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
            var target = (com.acltabontabon.vortex.core.target.ExternalEndpointTarget)
                    configuration.environmentByName("local").orElseThrow().target();
            assertThat(target.endpoint().value()).isEqualTo("http://localhost:9090");
        }

        @Test
        @DisplayName("removes an existing environment")
        void removesAnExistingEnvironment() throws Exception {
            mvc.perform(delete("/api/services/" + SERVICE + "/environments/local"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("Environment 'local' removed")));

            assertThat(saved().environmentByName("local")).isEmpty();
        }

        @Test
        @DisplayName("removing a name that isn't configured is not an error")
        void removingAnUnknownNameIsIdempotent() throws Exception {
            mvc.perform(delete("/api/services/" + SERVICE + "/environments/ghost"))
                    .andExpect(status().isOk());

            assertThat(saved().environmentByName("local")).isPresent();
        }

        @Test
        @DisplayName("a masked header value is preserved rather than overwritten literally")
        void preservesAMaskedHeaderValue() throws Exception {
            Environment withSecretHeader = new Environment(EnvironmentId.of("local"), "local",
                    EnvironmentType.LOCAL_ISOLATED,
                    new ExternalEndpointTarget(TargetUrl.of("http://localhost:8080")),
                    EnvironmentCapabilities.localIsolated(), DependencyMode.MOCKED,
                    Map.of("X-Api-Key", "a-real-literal-value"));
            when(projects.configuration(any())).thenReturn(
                    Fixtures.configuration().withEnvironments(List.of(withSecretHeader)));

            mvc.perform(post("/api/services/" + SERVICE + "/environments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"local","baseUrl":"http://localhost:8080",
                                     "type":"LOCAL_ISOLATED","dependencies":"MOCKED",
                                     "headerNames":"X-Api-Key","headerValues":"%s"}
                                    """.formatted(SecretReferences.MASK)))
                    .andExpect(status().isOk());

            var env = saved().environmentByName("local").orElseThrow();
            assertThat(env.headers()).containsEntry("X-Api-Key", "a-real-literal-value");
        }

        @Test
        @DisplayName("a masked header value with nothing to recover it from is rejected")
        void rejectsAMaskedHeaderWithNoExistingMatch() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/environments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"local","baseUrl":"http://localhost:8080",
                                     "type":"LOCAL_ISOLATED","dependencies":"MOCKED",
                                     "headerNames":"X-New-Header","headerValues":"%s"}
                                    """.formatted(SecretReferences.MASK)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("retype its value to change it")));
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

        @Test
        @DisplayName("a missing enum field is a 400 naming the field and its values, not a bare 500")
        void aMissingEnumFieldIsRejectedWithAMessageTheCallerCanActOn() throws Exception {
            // Enum.valueOf answers a missing value with a NullPointerException, which is not an
            // IllegalArgumentException and so escaped this endpoint's translation entirely — the
            // caller got a bare 500 with no indication of which field was wrong.
            mvc.perform(post("/api/services/" + SERVICE + "/environments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"local","baseUrl":"http://localhost:8080",
                                     "type":"LOCAL_ISOLATED"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.allOf(
                                    org.hamcrest.Matchers.containsString("dependencies"),
                                    org.hamcrest.Matchers.containsString("MOCKED"))));
        }

        @Test
        @DisplayName("an unrecognised enum value lists what is accepted rather than naming a Java class")
        void anUnrecognisedEnumValueListsWhatIsAccepted() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/environments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"local","baseUrl":"http://localhost:8080",
                                     "type":"SOMEWHERE_ELSE","dependencies":"MOCKED"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.allOf(
                                    org.hamcrest.Matchers.containsString("SOMEWHERE_ELSE"),
                                    org.hamcrest.Matchers.containsString("LOCAL_ISOLATED"),
                                    org.hamcrest.Matchers.not(
                                            org.hamcrest.Matchers.containsString("No enum constant")))));
        }

        @Test
        @DisplayName("a readiness timeout no service could meet is rejected at configuration time")
        void aReadinessTimeoutOfZeroIsRejected() throws Exception {
            // Left to stand, this configures an environment every run against which fails during
            // preparation — reported as a target that never became ready, which sends the reader to
            // debug a service that was fine.
            mvc.perform(post("/api/services/" + SERVICE + "/environments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"docker-managed","type":"LOCAL_ISOLATED","dependencies":"MOCKED",
                                     "targetKind":"DOCKER_IMAGE","image":"payment-service:1.4.2",
                                     "containerPort":8080,"readinessPath":"/actuator/health",
                                     "readinessExpectedStatus":200,"readinessTimeoutSeconds":0}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("leaves no time")));
        }

        @Test
        @DisplayName("omitting targetKind still produces an ExternalEndpointTarget, exactly as before")
        void omittingTargetKindStillProducesAnExternalEndpointTarget() throws Exception {
            // The single most important regression guard in this step: the existing endpoint-only
            // flow — the one every environment used before Docker/Compose targets existed — must be
            // completely unaffected by the new dispatch.
            mvc.perform(post("/api/services/" + SERVICE + "/environments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"local","baseUrl":"http://localhost:9090",
                                     "type":"LOCAL_ISOLATED","dependencies":"MOCKED"}
                                    """))
                    .andExpect(status().isOk());

            var target = (com.acltabontabon.vortex.core.target.ExternalEndpointTarget)
                    saved().environmentByName("local").orElseThrow().target();
            assertThat(target.endpoint().value()).isEqualTo("http://localhost:9090");
        }

        @Test
        @DisplayName("a DOCKER_IMAGE target with full fields creates the matching DockerImageTarget")
        void dockerImageTargetIsCreated() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/environments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"docker-managed","type":"LOCAL_ISOLATED","dependencies":"MOCKED",
                                     "targetKind":"DOCKER_IMAGE","image":"payment-service:1.4.2",
                                     "containerPort":8080,"cpuMillicores":500,"memoryMebibytes":512,
                                     "readinessPath":"/actuator/health","readinessExpectedStatus":200,
                                     "readinessTimeoutSeconds":30}
                                    """))
                    .andExpect(status().isOk());

            var target = (com.acltabontabon.vortex.core.target.DockerImageTarget)
                    saved().environmentByName("docker-managed").orElseThrow().target();
            assertThat(target.image().value()).isEqualTo("payment-service:1.4.2");
            assertThat(target.containerPort().value()).isEqualTo(8080);
            assertThat(target.resources().cpuIfPresent())
                    .hasValue(com.acltabontabon.vortex.core.target.CpuAllocation.ofMillicores(500));
            assertThat(target.resources().memoryIfPresent())
                    .hasValue(com.acltabontabon.vortex.core.target.MemoryAllocation.ofMebibytes(512));
            assertThat(target.readinessCheckIfPresent()).hasValueSatisfying(readiness ->
                    assertThat(readiness.path()).isEqualTo("/actuator/health"));
        }

        @Test
        @DisplayName("a DOCKER_IMAGE request missing containerPort is rejected with a clear 4xx")
        void dockerImageTargetMissingContainerPortIsRejected() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/environments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"docker-managed","type":"LOCAL_ISOLATED","dependencies":"MOCKED",
                                     "targetKind":"DOCKER_IMAGE","image":"payment-service:1.4.2"}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(
                            org.hamcrest.Matchers.containsString("container listens on")));
        }

        @Test
        @DisplayName("a DOCKER_COMPOSE target with full fields creates the matching DockerComposeTarget")
        void dockerComposeTargetIsCreated() throws Exception {
            mvc.perform(post("/api/services/" + SERVICE + "/environments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"name":"compose-attached","type":"LOCAL_ISOLATED","dependencies":"MOCKED",
                                     "targetKind":"DOCKER_COMPOSE","composeFile":"compose.yaml",
                                     "composeService":"payment-service","containerPort":8080}
                                    """))
                    .andExpect(status().isOk());

            var target = (com.acltabontabon.vortex.core.target.DockerComposeTarget)
                    saved().environmentByName("compose-attached").orElseThrow().target();
            assertThat(target.composeFile()).isEqualTo("compose.yaml");
            assertThat(target.serviceName()).isEqualTo("payment-service");
            assertThat(target.containerPort().value()).isEqualTo(8080);
        }
    }

    @Nested
    @DisplayName("target validation")
    class TargetValidation {

        @BeforeEach
        void aMatchingExecutor() {
            when(targetExecutor.supports(any())).thenReturn(true);
        }

        @Test
        @DisplayName("a Docker image target reports each check and an overall valid=true")
        void dockerImageTargetReportsChecks() throws Exception {
            when(targetExecutor.checkAvailability(any(), any())).thenReturn(List.of(
                    PreflightCheck.pass("Docker available", "Docker is reachable on this machine."),
                    PreflightCheck.pass("Image available", "payment-service:1.4.2")));

            mvc.perform(post("/api/services/" + SERVICE + "/environments/docker-managed/target/validate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"targetKind":"DOCKER_IMAGE","image":"payment-service:1.4.2",
                                     "containerPort":8080}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(true))
                    .andExpect(jsonPath("$.checks[0]").value(
                            "Docker available: Pass — Docker is reachable on this machine."))
                    .andExpect(jsonPath("$.checks[1]").value(
                            "Image available: Pass — payment-service:1.4.2"));

            verify(targetExecutor, never()).prepare(any());
        }

        @Test
        @DisplayName("a Compose target reports a failing stage and an overall valid=false")
        void composeTargetReportsAFailingStage() throws Exception {
            when(targetExecutor.checkAvailability(any(), any())).thenReturn(List.of(
                    PreflightCheck.pass("Compose file found", "compose.yaml"),
                    PreflightCheck.fail("payment-service found",
                            "Service 'payment-service' is not declared in compose.yaml.",
                            "Check the spelling against the file's own service names."),
                    PreflightCheck.skipped("Service running",
                            "Not checked — payment-service found failed.")));

            mvc.perform(post("/api/services/" + SERVICE + "/environments/compose-attached/target/validate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"targetKind":"DOCKER_COMPOSE","composeFile":"compose.yaml",
                                     "composeService":"payment-service","containerPort":8080}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(false))
                    .andExpect(jsonPath("$.checks[1]").value(org.hamcrest.Matchers.containsString(
                            "payment-service found: Failed")))
                    .andExpect(jsonPath("$.checks[2]").value(org.hamcrest.Matchers.containsString(
                            "Service running: Skipped")));

            verify(targetExecutor, never()).prepare(any());
        }

        @Test
        @DisplayName("never calls prepare() — only checkAvailability, even when prepare would fail loudly")
        void neverCallsPrepare() throws Exception {
            when(targetExecutor.checkAvailability(any(), any()))
                    .thenReturn(List.of(PreflightCheck.pass("Docker available", "")));
            when(targetExecutor.prepare(any())).thenThrow(
                    new AssertionError("validate must never call prepare()"));

            mvc.perform(post("/api/services/" + SERVICE + "/environments/docker-managed/target/validate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"targetKind":"DOCKER_IMAGE","image":"payment-service:1.4.2",
                                     "containerPort":8080}
                                    """))
                    .andExpect(status().isOk());

            verify(targetExecutor, never()).prepare(any());
        }

        @Test
        @DisplayName("validates whatever the request body describes, independent of what is saved")
        void validatesTheRequestBodyNotTheSavedConfiguration() throws Exception {
            // aConfiguredService() stashes Fixtures.configuration(), whose only environment is
            // ExternalEndpointTarget "local" — this call names a different, unsaved environment and a
            // wholly different target kind, and must still validate exactly what the body describes.
            when(targetExecutor.checkAvailability(any(), any()))
                    .thenReturn(List.of(PreflightCheck.pass("Image available", "checkout:9.9.9")));

            mvc.perform(post("/api/services/" + SERVICE + "/environments/not-yet-saved/target/validate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"targetKind":"DOCKER_IMAGE","image":"checkout:9.9.9",
                                     "containerPort":9090}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.checks[0]").value(org.hamcrest.Matchers.containsString(
                            "checkout:9.9.9")));

            var targetCaptor = org.mockito.ArgumentCaptor.forClass(
                    com.acltabontabon.vortex.core.target.ExecutionTarget.class);
            verify(targetExecutor).checkAvailability(targetCaptor.capture(), any());
            var validated = (com.acltabontabon.vortex.core.target.DockerImageTarget) targetCaptor.getValue();
            assertThat(validated.image().value()).isEqualTo("checkout:9.9.9");
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

        @Test
        @DisplayName("fetch-and-save persists exactly what was fetched, provenance included")
        void fetchAndSavePersistsWhatWasFetched() throws Exception {
            var observation = new ProductionObservation(
                    RequestsPerSecond.of(120), RequestsPerSecond.of(150), RequestsPerSecond.of(200),
                    null, null, Duration.ofHours(1), "Dynatrace MCP (SERVICE-1)", Observation.unknown(),
                    new ObservationProvenance("dynatrace-mcp", "dynatrace.throughput.v1", "SERVICE-1", ""),
                    "");
            when(calibrationService.fetch(any(), any(), any())).thenReturn(new Retrieved(observation));

            mvc.perform(post("/api/services/" + SERVICE + "/production/fetch-and-save"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.succeeded").value(true));

            var savedObservation = saved().productionObservation();
            assertThat(savedObservation.peakRate().display()).isEqualTo("200");
            assertThat(savedObservation.wasFetched()).isTrue();
        }

        @Test
        @DisplayName("fetch-and-save never persists when there is nothing to fetch")
        void fetchAndSaveNeverPersistsOnFailure() throws Exception {
            when(calibrationService.fetch(any(), any(), any())).thenReturn(new NotRetrieved(
                    "Cannot fetch production traffic", "no observation source is configured for this service.",
                    "Add an 'observation:' section to vortex.yaml."));

            mvc.perform(post("/api/services/" + SERVICE + "/production/fetch-and-save"))
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
            var project = new com.acltabontabon.vortex.core.project.Project(
                    com.acltabontabon.vortex.core.shared.ProjectId.of(SERVICE), "checkout-service", "",
                    workspace.toString(), "", java.time.Instant.now(), java.time.Instant.now());
            when(projects.find(any())).thenReturn(Optional.of(project));
            when(projects.configuration(any())).thenReturn(Fixtures.configuration()
                    .withLocalLab(new com.acltabontabon.vortex.core.lab.LocalLabSettings("compose.yaml")));
            when(lab.status()).thenReturn(new LocalLab.LabStatus(false, false, false, "",
                    "Install Docker Desktop."));

            mvc.perform(post("/api/services/" + SERVICE + "/lab/up"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("Install Docker Desktop."));
        }
    }
}
