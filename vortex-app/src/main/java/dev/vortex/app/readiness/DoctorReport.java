package dev.vortex.app.readiness;

import dev.vortex.core.port.LocalLab;
import dev.vortex.core.port.PerformanceAssistant;
import dev.vortex.core.port.PerformanceEngine;
import dev.vortex.persistence.VortexWorkspace;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Checks that the tools Vortex needs are present, and says what to do about each that is not.
 *
 * <p>Onboarding friction is mostly environmental. "Nothing happens when I click Run" is almost
 * always a missing k6, a stopped Docker daemon or an unwritable home directory, and every minute
 * spent guessing at that is a minute not spent on performance engineering.
 *
 * <p>Deliberately distinguishes what is required from what is optional. Vortex needs a load
 * generator and a writable workspace. Docker and a local model are conveniences, and a diagnostic
 * that reports their absence as a problem would send people installing things they do not need.
 */
@Component
public class DoctorReport {

    private final PerformanceEngine engine;
    private final PerformanceAssistant assistant;
    private final LocalLab localLab;
    private final VortexWorkspace workspace;

    public DoctorReport(PerformanceEngine engine, PerformanceAssistant assistant, LocalLab localLab,
            VortexWorkspace workspace) {
        this.engine = engine;
        this.assistant = assistant;
        this.localLab = localLab;
        this.workspace = workspace;
    }

    /**
     * One thing that was checked.
     *
     * @param name     what was checked
     * @param status   the outcome
     * @param detail   what was found
     * @param remedy   what to do about it, when something is missing
     * @param required whether Vortex needs this in order to run a test at all
     */
    public record Check(String name, Status status, String detail, String remedy, boolean required) {

        public enum Status { OK, MISSING, PROBLEM }

        public boolean isOk() {
            return status == Status.OK;
        }

        /** The symbol shown alongside this check. */
        public String mark() {
            return switch (status) {
                case OK -> "✓";
                case MISSING -> "○";
                case PROBLEM -> "✗";
            };
        }
    }

    public List<Check> run() {
        List<Check> checks = new ArrayList<>();

        checks.add(new Check("Java", Check.Status.OK,
                System.getProperty("java.version") + " (" + System.getProperty("java.vm.name") + ")",
                "", true));

        var engineAvailability = engine.availability();
        checks.add(engineAvailability.available()
                ? new Check("Load generator", Check.Status.OK, engineAvailability.version(), "", true)
                : new Check("Load generator", Check.Status.PROBLEM, engineAvailability.problem(),
                engineAvailability.remedy(), true));

        checks.add(workspaceCheck());

        var lab = localLab.status();
        if (lab.isUsable()) {
            checks.add(new Check("Docker", Check.Status.OK, lab.version(), "", false));
        } else {
            checks.add(new Check("Docker", Check.Status.MISSING,
                    lab.daemonRunning() ? "installed, Compose unavailable"
                            : lab.dockerAvailable() ? "installed, daemon not running" : "not found",
                    lab.remedy(), false));
        }

        var ai = assistant.availability();
        checks.add(ai.available()
                ? new Check("Local AI", Check.Status.OK, ai.provider() + " · " + ai.model(), "", false)
                : new Check("Local AI", Check.Status.MISSING, ai.problem(), ai.remedy(), false));

        return checks;
    }

    private Check workspaceCheck() {
        if (workspace.isWritable()) {
            return new Check("Workspace", Check.Status.OK, workspace.root().toString(), "", true);
        }
        return new Check("Workspace", Check.Status.PROBLEM,
                workspace.root() + " is not writable",
                "Vortex stores its database and execution artifacts here. Check the directory's "
                        + "permissions, or choose another location with "
                        + "vortex.workspace.directory.", true);
    }

    /** Whether everything Vortex actually requires is present. */
    public boolean isReady(List<Check> checks) {
        return checks.stream().filter(Check::required).allMatch(Check::isOk);
    }
}
