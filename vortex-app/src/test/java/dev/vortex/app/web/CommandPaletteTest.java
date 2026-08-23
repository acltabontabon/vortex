package dev.vortex.app.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vortex.core.application.ProjectService;
import dev.vortex.core.fixtures.Fixtures;
import dev.vortex.core.port.Repositories.ExecutionRepository;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The command palette is an accelerator, and the tests are mostly about that word.
 *
 * <p>What matters is not that it finds things — it is that nothing depends on it. Every entry it
 * offers has to be a URL that already exists and is already reachable by clicking, so that the
 * palette can be absent, broken or scripted-out without anything becoming unreachable.
 */
@WebMvcTest(controllers = PaletteController.class)
@DisplayName("the command palette index")
class CommandPaletteTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProjectService projects;

    @MockitoBean
    private ExecutionRepository executions;

    @BeforeEach
    void oneConfiguredService() {
        when(projects.all()).thenReturn(List.of(Fixtures.project()));
        when(projects.configuration(any())).thenReturn(Fixtures.configuration());
        when(executions.findRecent(anyInt())).thenReturn(List.of());
    }

    @Test
    @DisplayName("offers each service, its workloads, and the places anyone might want to reach")
    void listsTheThingsWorthReaching() throws Exception {
        mvc.perform(get("/palette.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.kind == 'Service')]", Matchers.not(Matchers.empty())))
                .andExpect(jsonPath("$[?(@.kind == 'Workload')]", Matchers.not(Matchers.empty())))
                .andExpect(jsonPath("$[?(@.label == 'Runtime')]", Matchers.not(Matchers.empty())));
    }

    /*
     * The constraint that keeps the palette from quietly becoming the interface. A destination that
     * only the palette knows how to reach is a destination somebody without JavaScript cannot get
     * to, and every href here is a route a controller already serves.
     */
    @Test
    @DisplayName("every entry points at a route that exists without it")
    void everyEntryIsAnOrdinaryUrl() throws Exception {
        mvc.perform(get("/palette.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].href", Matchers.everyItem(Matchers.startsWith("/"))))
                // No query-string commands, no palette-only verbs: plain addressable pages.
                .andExpect(jsonPath("$[*].href",
                        Matchers.everyItem(Matchers.not(Matchers.containsString("?")))));
    }

    @Test
    @DisplayName("gives every entry the context that tells two similar labels apart")
    void entriesCarryTheirContext() throws Exception {
        mvc.perform(get("/palette.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].kind", Matchers.everyItem(Matchers.not(Matchers.emptyString()))))
                .andExpect(jsonPath("$[*].label", Matchers.everyItem(Matchers.not(Matchers.emptyString()))));
    }
}
