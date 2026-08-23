package dev.vortex.app.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.vortex.app.readiness.DoctorReport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The app shell's status bar reads this instead of a Thymeleaf model attribute — same
 * {@link RuntimeStatus}, just as JSON.
 */
@WebMvcTest(controllers = RuntimeApiController.class)
class RuntimeApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RuntimeStatus runtimeStatus;

    @Test
    void currentReflectsTheCachedSummary() throws Exception {
        List<DoctorReport.Check> checks = List.of(
                new DoctorReport.Check("Load generator", DoctorReport.Check.Status.OK, "k6 v1.2.0",
                        "", true),
                new DoctorReport.Check("Docker", DoctorReport.Check.Status.MISSING, "not found",
                        "Install Docker.", false));
        when(runtimeStatus.current())
                .thenReturn(new RuntimeStatus.Summary(checks, 1, 2, true));

        mockMvc.perform(get("/api/runtime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.satisfied").value(1))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.requirementsMet").value(true))
                .andExpect(jsonPath("$.checks[0].name").value("Load generator"))
                .andExpect(jsonPath("$.checks[0].ok").value(true))
                .andExpect(jsonPath("$.checks[1].name").value("Docker"))
                .andExpect(jsonPath("$.checks[1].ok").value(false))
                .andExpect(jsonPath("$.checks[1].remedy").value("Install Docker."));
    }

    @Test
    void refreshDiscardsTheCache() throws Exception {
        when(runtimeStatus.refresh())
                .thenReturn(new RuntimeStatus.Summary(List.of(), 0, 0, true));

        mockMvc.perform(post("/api/runtime/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requirementsMet").value(true));
    }
}
