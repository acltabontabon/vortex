package dev.vortex.app.web;

import dev.vortex.core.application.ProjectService;
import dev.vortex.core.execution.TestExecution;
import dev.vortex.core.port.Repositories.ExecutionRepository;
import dev.vortex.core.project.Project;
import dev.vortex.core.workload.Workload;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The index behind the command palette.
 *
 * <h2>What the palette is, and is not</h2>
 *
 * <p>It is an accelerator. Vortex is a workbench, and somebody who lives in one all day should be
 * able to reach a service, a workload or last night's run without navigating to it.
 *
 * <p>It owns no domain state and no navigation state. Everything it lists is a URL that already
 * exists and is already reachable by clicking — from the home page, the service switcher, or the
 * workspace — so the palette can be absent, broken, or scripted-out entirely without anything
 * becoming unreachable. That constraint is what keeps it from quietly becoming the interface, and
 * it is why {@code vortex.js} reveals its trigger rather than the markup shipping one that might
 * not work (ADR-004).
 *
 * <p>Deliberately not a search endpoint. It returns the whole index once, small, and the filtering
 * happens in the browser — which means no request per keystroke, no server-side ranking to tune,
 * and no way for a slow query to make typing feel slow.
 */
@RestController
public class PaletteController {

    /** Enough recent runs to cover "the one I was just looking at". */
    private static final int RECENT_RUNS = 20;

    private final ProjectService projects;
    private final ExecutionRepository executions;

    public PaletteController(ProjectService projects, ExecutionRepository executions) {
        this.projects = projects;
        this.executions = executions;
    }

    /**
     * One thing the palette can take you to.
     *
     * @param kind    what sort of thing it is, used to group the list
     * @param label   what to show
     * @param detail  the line beneath it, giving the context that tells two similar labels apart
     * @param href    where it goes — always a URL that is reachable without the palette
     */
    public record Entry(String kind, String label, String detail, String href) {
    }

    @GetMapping("/palette.json")
    public List<Entry> index() {
        List<Entry> entries = new ArrayList<>();

        for (Project project : projects.all()) {
            String id = project.id().value();
            entries.add(new Entry("Service", project.name(),
                    project.description(), "/services/" + id + "/configuration"));
            entries.add(new Entry("Service", project.name() + " · evidence",
                    "What this service has been shown to do", "/services/" + id + "/evidence"));
            entries.add(new Entry("Service", project.name() + " · run a test",
                    "Choose what to find out, and run it", "/services/" + id + "/tests"));

            for (Workload workload : projects.configuration(project.id()).workloads()) {
                entries.add(new Entry("Workload", workload.name(),
                        project.name() + " · " + workload.type().label(),
                        "/services/" + id + "/tests/" + workload.name() + "/edit"));
            }
        }

        for (TestExecution execution : executions.findRecent(RECENT_RUNS)) {
            entries.add(new Entry("Run",
                    execution.plan().testType().label() + " · " + execution.plan().workloadName(),
                    execution.plan().projectName() + " · " + execution.state().label(),
                    "/runs/" + execution.id().value()));
        }

        entries.add(new Entry("Go to", "Home", "Every service, and what to do next", "/"));
        entries.add(new Entry("Go to", "All evidence", "Every run, across every service", "/runs"));
        entries.add(new Entry("Go to", "Add a service", "Start testing something new", "/services/new"));
        entries.add(new Entry("Go to", "Runtime", "What this machine can currently do", "/runtime"));
        entries.add(new Entry("Go to", "Settings", "Load generator, workspace and local model", "/settings"));

        return entries;
    }
}
