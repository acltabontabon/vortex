package com.acltabontabon.vortex.app.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acltabontabon.vortex.app.discovery.DiscoveryConfigurationAssembler;
import com.acltabontabon.vortex.app.discovery.ProjectSnapshotBuilder;
import com.acltabontabon.vortex.core.application.CatalogImportService;
import com.acltabontabon.vortex.core.application.ProjectDiscoveryService;
import com.acltabontabon.vortex.core.application.ProjectService;
import com.acltabontabon.vortex.core.discovery.Confidence;
import com.acltabontabon.vortex.core.discovery.DiscoveryProposal;
import com.acltabontabon.vortex.core.discovery.EnvironmentProposal;
import com.acltabontabon.vortex.core.discovery.Finding;
import com.acltabontabon.vortex.core.discovery.FindingKind;
import com.acltabontabon.vortex.core.discovery.ProjectSnapshot;
import com.acltabontabon.vortex.core.environment.DependencyMode;
import com.acltabontabon.vortex.core.environment.EnvironmentType;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.lab.LocalLabSettings;
import com.acltabontabon.vortex.core.project.Project;
import com.acltabontabon.vortex.core.project.ProjectConfiguration;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.target.ContainerPort;
import com.acltabontabon.vortex.core.target.DockerComposeTarget;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * "Discover from project" for an already-created service: scanning it for a proposal and applying
 * whatever a person approved. Both endpoints are on {@link DiscoveryApiController}.
 */
@WebMvcTest(controllers = DiscoveryApiController.class)
class DiscoveryApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projects;

    @MockitoBean
    private ProjectDiscoveryService discovery;

    @MockitoBean
    private ProjectSnapshotBuilder snapshotBuilder;

    @MockitoBean
    private CatalogImportService catalogs;

    @MockitoBean
    private DiscoveryConfigurationAssembler assembler;

    @Test
    void scanningAServiceWithNoWorkspacePathReportsWhyRatherThanFailing() throws Exception {
        Project noWorkspace = new Project(ProjectId.of("checkout"), "checkout-service", "", "",
                "", Instant.EPOCH, Instant.EPOCH);
        when(projects.find(ProjectId.of("checkout"))).thenReturn(Optional.of(noWorkspace));

        mockMvc.perform(post("/api/services/checkout/discovery/scan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("no project directory")));
    }

    @Test
    void scanningAMissingServiceIsNotFound() throws Exception {
        when(projects.find(ProjectId.of("missing"))).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/services/missing/discovery/scan"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aSuccessfulScanReportsFindingsAndAProposedEnvironment() throws Exception {
        Project project = Fixtures.project();
        when(projects.find(project.id())).thenReturn(Optional.of(project));
        when(projects.configuration(project.id())).thenReturn(ProjectConfiguration.empty());

        ProjectSnapshotBuilder.Result snapshotResult =
                new ProjectSnapshotBuilder.Result(new ProjectSnapshot("checkout", List.of()), List.of());
        when(snapshotBuilder.build(project.workspacePath())).thenReturn(snapshotResult);

        Finding finding = new Finding(FindingKind.BUILD_TOOL_MAVEN, "pom.xml", List.of("pom.xml found"),
                Confidence.HIGH, Map.of("artifactId", "checkout-service"));
        EnvironmentProposal environment = new EnvironmentProposal("app", EnvironmentType.LOCAL_ISOLATED,
                new DockerComposeTarget("compose.yaml", "app", new ContainerPort(8080)),
                DependencyMode.REAL);
        DiscoveryProposal proposal = new DiscoveryProposal("checkout-service", "", null, environment,
                new LocalLabSettings("compose.yaml"), List.of(finding), List.of(), List.of());
        when(discovery.discover(snapshotResult.snapshot(), ProjectConfiguration.empty()))
                .thenReturn(proposal);

        mockMvc.perform(post("/api/services/{id}/discovery/scan", project.id().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.proposedServiceName").value("checkout-service"))
                .andExpect(jsonPath("$.proposedEnvironment.composeService").value("app"))
                .andExpect(jsonPath("$.proposedEnvironment.containerPort").value(8080))
                .andExpect(jsonPath("$.proposedLocalLabComposeFile").value("compose.yaml"))
                .andExpect(jsonPath("$.findings[0].kind").value("BUILD_TOOL_MAVEN"))
                .andExpect(jsonPath("$.findings[0].confidence").value("HIGH"));
    }

    @Test
    void applyingNothingSelectedStillSavesAndNeverImportsOrTouchesTheAssembler() throws Exception {
        Project project = Fixtures.project();
        when(projects.find(project.id())).thenReturn(Optional.of(project));
        when(projects.configuration(project.id())).thenReturn(ProjectConfiguration.empty());

        mockMvc.perform(post("/api/services/{id}/discovery/apply", project.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applyOpenApiSource\":false,\"applyEnvironment\":false,"
                                + "\"applyLocalLab\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Discovered setup applied."));

        verify(catalogs, never()).importCatalog(any(), any(), any());
        verify(assembler, never()).withEnvironment(any(), any());
        verify(assembler, never()).withLocalLab(any(), any());
        verify(projects).saveConfiguration(eq(project.id()), any());
    }

    @Test
    void applyingALocalLabSelectionSavesIt() throws Exception {
        Project project = Fixtures.project();
        when(projects.find(project.id())).thenReturn(Optional.of(project));
        when(projects.configuration(project.id())).thenReturn(ProjectConfiguration.empty());
        when(assembler.withLocalLab(any(), eq(new LocalLabSettings("compose.yaml"))))
                .thenReturn(ProjectConfiguration.empty().withLocalLab(new LocalLabSettings("compose.yaml")));

        mockMvc.perform(post("/api/services/{id}/discovery/apply", project.id().value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applyOpenApiSource\":false,\"applyEnvironment\":false,"
                                + "\"applyLocalLab\":true,\"localLabComposeFile\":\"compose.yaml\"}"))
                .andExpect(status().isOk());

        verify(assembler).withLocalLab(any(), eq(new LocalLabSettings("compose.yaml")));
        verify(projects).saveConfiguration(eq(project.id()), any());
    }
}
