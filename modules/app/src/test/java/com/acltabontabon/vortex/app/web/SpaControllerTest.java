package com.acltabontabon.vortex.app.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Every route this migration hasn't reached yet, plus every route it hasn't been invented yet
 * either, forwards to the built SPA — the one thing this controller has to get right, since it
 * replaced one mapping ({@code GET /}) with a catch-all covering everything not explicitly
 * reserved. See the class-level doc comment on {@link SpaController} for the exact exclusion list.
 */
@WebMvcTest(controllers = SpaController.class)
class SpaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/",
            "/services",
            // The service workspace. /services/{id} used to redirect to /understand; it is now the
            // React Overview, and its sibling tabs arrive the same way.
            "/services/abc123",
            "/services/abc123/tests",
            "/services/abc123/traffic/checkout-mix",
            "/runs",
            "/runs/abc123/report",
            "/settings",
            "/runtime",
            "/whatever-a-future-react-route-turns-out-to-be",
    })
    @DisplayName("forwards to the built SPA")
    void forwardsToTheApp(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/app/index.html"));
    }

    @Test
    @DisplayName("does not swallow a stray dotted-extension request at the top level")
    void doesNotForwardADottedTopLevelPath() throws Exception {
        // Neither reserved nor forwarded — the point is only that it isn't silently handed to the
        // SPA shell. A real static file at this path would still be served by Spring's own static
        // resource handler, which this test slice doesn't load.
        mockMvc.perform(get("/favicon.ico")).andExpect(status().isNotFound());
    }
}
