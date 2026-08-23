package com.acltabontabon.vortex.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the React application shell for every route the SPA owns.
 *
 * <p>One forward, not one mapping per page: any request whose first path segment isn't reserved for
 * something else forwards to the built SPA's {@code index.html}, and React Router resolves the rest
 * client-side. This is what makes a hard refresh on a deep link like {@code /services/abc/traffic}
 * work — the server doesn't need to know that route exists, only that it isn't one of the things it
 * owns itself.
 *
 * <p>The exclusion is a first-segment allow-list-by-exception: {@code api} (every REST controller),
 * {@code actuator} (health), {@code app} (the built SPA's own JS/CSS/assets), {@code palette.json}
 * (the command palette's data source, not nested under {@code /api} for historical reasons), and
 * {@code webjars}. A trailing dot-extension anywhere in the first segment (e.g. a stray
 * {@code /favicon.ico}) is excluded too, so an unmapped static-file request 404s normally instead of
 * silently forwarding to the app shell.
 *
 * <p>Routes that look like they'd collide with this — {@code /runs/{id}/export.{format}},
 * {@code /runs/{id}/artifacts/{name}}, {@code /runs/{id}/stream} — don't: Spring always prefers a
 * more specific, literally-mapped {@code @RequestMapping} over this broad pattern for the same URL,
 * so those controllers keep winning without needing an explicit exclusion here.
 */
@Controller
public class SpaController {

    private static final String RESERVED =
            "^(?!api$)(?!actuator$)(?!app$)(?!palette\\.json$)(?!webjars$)(?!.*\\..*).*$";

    @GetMapping({"/", "/{path:" + RESERVED + "}", "/{path:" + RESERVED + "}/**"})
    public String app() {
        return "forward:/app/index.html";
    }
}
