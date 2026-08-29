package com.acltabontabon.vortex.app.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import com.acltabontabon.vortex.app.discovery.DiscoveryConfigurationAssembler;
import com.acltabontabon.vortex.app.discovery.ProjectSnapshotBuilder;
import com.acltabontabon.vortex.core.application.CatalogImportService;
import com.acltabontabon.vortex.core.application.ProjectDiscoveryService;
import com.acltabontabon.vortex.core.application.ProjectService;
import com.acltabontabon.vortex.core.fixtures.Fixtures;
import com.acltabontabon.vortex.core.port.ServiceCatalogImporter;
import com.acltabontabon.vortex.core.shared.ProjectId;
import com.acltabontabon.vortex.core.workload.RateAllocator;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The service switcher and the standalone Services page read {@link ServicesApiController#list()};
 * the "Add a service" form posts to {@link ServicesApiController#create}.
 */
@WebMvcTest(controllers = ServicesApiController.class)
@Import({Display.class, WorkloadView.class, WorkloadDiagramRenderer.class,
        LoadAxisRenderer.class, RateAllocator.class})
class ServicesApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projects;

    @MockitoBean
    private CatalogImportService catalogs;

    @MockitoBean
    private ProjectDiscoveryService discovery;

    @MockitoBean
    private ProjectSnapshotBuilder snapshotBuilder;

    @MockitoBean
    private DiscoveryConfigurationAssembler discoveryAssembler;

    @Test
    void listsEveryServiceWithItsDescriptionAndRelease() throws Exception {
        when(projects.all()).thenReturn(List.of(Fixtures.project()));

        mockMvc.perform(get("/api/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(Fixtures.project().id().value()))
                .andExpect(jsonPath("$[0].name").value("checkout-service"))
                .andExpect(jsonPath("$[0].description").value("Sample service"))
                .andExpect(jsonPath("$[0].serviceVersion").value("2.17.0"));
    }

    @Test
    void emptyWhenNothingIsSetUpYet() throws Exception {
        when(projects.all()).thenReturn(List.of());

        mockMvc.perform(get("/api/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void createsAServiceWithNoImportAttempted() throws Exception {
        when(projects.create(eq("checkout-service"), any(), any())).thenReturn(Fixtures.project());

        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"checkout-service"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service.id").value(Fixtures.project().id().value()))
                .andExpect(jsonPath("$.importOutcome.attempted").value(false));
    }

    @Test
    void rejectsADuplicateNameWithTheDomainsOwnMessage() throws Exception {
        when(projects.create(eq("checkout-service"), any(), any())).thenThrow(
                new IllegalArgumentException("A project named 'checkout-service' already exists."));

        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"checkout-service"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "A project named 'checkout-service' already exists."));
    }

    @Test
    void reportsAFailedFetchWithoutHidingTheCreatedService() throws Exception {
        // No network involved: an unsupported scheme fails in fetch() itself, before any call to
        // the importer — the same path a real DNS failure or non-2xx response would take.
        when(projects.create(eq("checkout-service"), any(), any())).thenReturn(Fixtures.project());

        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"checkout-service","openApiUrl":"ftp://example.com/spec.yaml"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service.id").value(Fixtures.project().id().value()))
                .andExpect(jsonPath("$.importOutcome.attempted").value(true))
                .andExpect(jsonPath("$.importOutcome.succeeded").value(false))
                .andExpect(jsonPath("$.importOutcome.error").value(
                        "The service was created, but Vortex could not read that API description: "
                                + "Vortex only fetches API descriptions over http or https."));
    }

    @Test
    void creatingAServiceWithAnApprovedLocalLabSelectionSavesIt() throws Exception {
        when(projects.create(eq("checkout-service"), any(), any())).thenReturn(Fixtures.project());
        when(projects.configuration(Fixtures.project().id())).thenReturn(
                com.acltabontabon.vortex.core.project.ProjectConfiguration.empty());
        when(discoveryAssembler.withLocalLab(any(), any())).thenReturn(
                com.acltabontabon.vortex.core.project.ProjectConfiguration.empty()
                        .withLocalLab(new com.acltabontabon.vortex.core.lab.LocalLabSettings("compose.yaml")));

        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"checkout-service","applyLocalLabComposeFile":"compose.yaml"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.setupWarning").doesNotExist());

        verify(discoveryAssembler).withLocalLab(any(),
                eq(new com.acltabontabon.vortex.core.lab.LocalLabSettings("compose.yaml")));
        verify(projects).saveConfiguration(eq(Fixtures.project().id()), any());
    }

    @Test
    void aFailedDiscoveredSetupIsReportedAsAWarningNotAFailure() throws Exception {
        when(projects.create(eq("checkout-service"), any(), any())).thenReturn(Fixtures.project());
        when(projects.configuration(Fixtures.project().id())).thenReturn(
                com.acltabontabon.vortex.core.project.ProjectConfiguration.empty());
        when(discoveryAssembler.withLocalLab(any(), any()))
                .thenThrow(new IllegalArgumentException("'../compose.yaml' points outside the repository"));

        mockMvc.perform(post("/api/services")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"checkout-service","applyLocalLabComposeFile":"../compose.yaml"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service.id").value(Fixtures.project().id().value()))
                .andExpect(jsonPath("$.setupWarning").value(org.hamcrest.Matchers.containsString(
                        "could not apply the discovered setup")));
    }

    @Test
    void scansACandidateDirectoryBeforeAnyServiceExists() throws Exception {
        ProjectSnapshotBuilder.Result snapshotResult = new ProjectSnapshotBuilder.Result(
                new com.acltabontabon.vortex.core.discovery.ProjectSnapshot("checkout", List.of()),
                List.of());
        when(snapshotBuilder.build("/tmp/checkout")).thenReturn(snapshotResult);
        when(discovery.discover(snapshotResult.snapshot(),
                com.acltabontabon.vortex.core.project.ProjectConfiguration.empty()))
                .thenReturn(new com.acltabontabon.vortex.core.discovery.DiscoveryProposal(
                        "checkout-service", "", null, null, null, List.of(), List.of(), List.of()));

        mockMvc.perform(post("/api/services/discovery-scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"path":"/tmp/checkout"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.proposedServiceName").value("checkout-service"));
    }

    @Test
    void scanningWithNoPathAsksForOneRatherThanFailing() throws Exception {
        mockMvc.perform(post("/api/services/discovery-scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"path":""}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    void deletesAService() throws Exception {
        mockMvc.perform(delete("/api/services/" + Fixtures.project().id().value()))
                .andExpect(status().isOk());

        verify(projects).delete(Fixtures.project().id());
    }

    @Test
    void deletingAnUnknownServiceIsReportedAsNotFound() throws Exception {
        doThrow(new IllegalArgumentException("No project with id missing"))
                .when(projects).delete(ProjectId.of("missing"));

        mockMvc.perform(delete("/api/services/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("No project with id missing"));
    }

    @Test
    void deletingAServiceWithARunInProgressIsRefused() throws Exception {
        doThrow(new IllegalStateException("Cannot delete 'checkout-service' while a run is in progress."))
                .when(projects).delete(Fixtures.project().id());

        mockMvc.perform(delete("/api/services/" + Fixtures.project().id().value()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "Cannot delete 'checkout-service' while a run is in progress."));
    }

    @Test
    void previewsWhatAnOpenApiAddressHoldsWithoutCreatingAnything() throws Exception {
        // fetch() is a private, real java.net.http.HttpClient call with no mock seam, so a
        // successful preview needs something to actually answer it — a tiny local server, the same
        // way the demo service answers it for a real user, rather than a hardcoded unreachable URL.
        HttpServer server = startLocalServer("openapi: 3.0.3");
        try {
            String url = localUrl(server);
            when(catalogs.previewCatalog(eq(url), any())).thenReturn(Fixtures.catalog());

            mockMvc.perform(post("/api/services/openapi-preview")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"" + url + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(true))
                    .andExpect(jsonPath("$.title").value("checkout-service"))
                    .andExpect(jsonPath("$.operationCount").value(4))
                    .andExpect(jsonPath("$.sample.length()").value(3))
                    .andExpect(jsonPath("$.sample[0].label").value("GET /accounts/{id}"))
                    .andExpect(jsonPath("$.error").doesNotExist());

            verify(projects, never()).create(any(), any(), any());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void previewReportsAnUnparseableDocumentWithoutThrowing() throws Exception {
        HttpServer server = startLocalServer("not an OpenAPI document");
        try {
            String url = localUrl(server);
            when(catalogs.previewCatalog(eq(url), any())).thenThrow(
                    new ServiceCatalogImporter.ImportException(
                            "Vortex could not parse this API description.",
                            List.of("The document could not be read as OpenAPI 3.x.")));

            mockMvc.perform(post("/api/services/openapi-preview")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"url\":\"" + url + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ok").value(false))
                    .andExpect(jsonPath("$.error").value(
                            "Vortex could not parse this API description."))
                    .andExpect(jsonPath("$.errorDetails[0]").value(
                            "The document could not be read as OpenAPI 3.x."));
        } finally {
            server.stop(0);
        }
    }

    /** A throwaway local HTTP server so {@code fetch()}'s real network call has something to hit. */
    private static HttpServer startLocalServer(String body) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/openapi.yaml", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return server;
    }

    private static String localUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/openapi.yaml";
    }

    @Test
    void previewRejectsAnUnsupportedSchemeWithoutFetchingAnything() throws Exception {
        mockMvc.perform(post("/api/services/openapi-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"ftp://example.com/spec.yaml"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value(
                        "Vortex only fetches API descriptions over http or https."));
    }

    @Test
    void previewOfABlankUrlAsksForOneWithoutCallingTheImporter() throws Exception {
        mockMvc.perform(post("/api/services/openapi-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":""}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").value("Enter a URL to preview it."));

        verify(catalogs, never()).previewCatalog(any(), any());
    }

    @Test
    void checkWorkspaceDetectsAGitRepository(@TempDir Path directory) throws Exception {
        Files.createDirectory(directory.resolve(".git"));

        mockMvc.perform(post("/api/services/workspace-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"" + directory.toString().replace("\\", "\\\\") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.isDirectory").value(true))
                .andExpect(jsonPath("$.writable").value(true))
                .andExpect(jsonPath("$.gitRepository").value(true));
    }

    @Test
    void checkWorkspaceReportsADirectoryWithNoGitRepository(@TempDir Path directory) throws Exception {
        mockMvc.perform(post("/api/services/workspace-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"" + directory.toString().replace("\\", "\\\\") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.gitRepository").value(false));
    }

    @Test
    void checkWorkspaceReportsAMissingPath(@TempDir Path directory) throws Exception {
        Path missing = directory.resolve("does-not-exist");

        mockMvc.perform(post("/api/services/workspace-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"" + missing.toString().replace("\\", "\\\\") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.error").value("Vortex could not find that path."));
    }

    @Test
    void checkWorkspaceRejectsAPathThatIsAFile(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("notes.txt");
        Files.createFile(file);

        mockMvc.perform(post("/api/services/workspace-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"path\":\"" + file.toString().replace("\\", "\\\\") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.isDirectory").value(false))
                .andExpect(jsonPath("$.error").value("That path is not a directory."));
    }

    @Test
    void checkWorkspaceOfABlankPathAsksForOne() throws Exception {
        mockMvc.perform(post("/api/services/workspace-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"path":""}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.error").value("Enter a path to check it."));
    }
}
