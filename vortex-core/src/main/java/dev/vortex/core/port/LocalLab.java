package dev.vortex.core.port;

import java.util.List;

/**
 * Manages the local dependencies a service needs in order to be tested on a developer's machine.
 *
 * <p>Local testing is a first-class capability in Vortex, not a lesser mode: an engineer should be
 * able to learn performance testing, validate a workload and catch a regression without booking an
 * environment or waiting for a pipeline.
 *
 * <p>Vortex does not try to own every project's container lifecycle. Where a service already has a
 * working Compose file, Vortex references it rather than replacing it.
 */
public interface LocalLab {

    /** What container tooling is available on this machine. */
    LabStatus status();

    /** Starts the dependencies described by a Compose file. */
    LabResult up(String composeFilePath);

    /** Stops them again. */
    LabResult down(String composeFilePath);

    /**
     * @param dockerAvailable  the Docker CLI is present
     * @param daemonRunning    the daemon is reachable
     * @param composeAvailable Compose is present
     * @param version          detected version information
     * @param remedy           what to do when something is missing
     */
    record LabStatus(
            boolean dockerAvailable,
            boolean daemonRunning,
            boolean composeAvailable,
            String version,
            String remedy) {

        public boolean isUsable() {
            return dockerAvailable && daemonRunning && composeAvailable;
        }
    }

    /**
     * @param success whether the operation worked
     * @param message what happened, in plain language
     * @param output  captured command output for troubleshooting
     */
    record LabResult(boolean success, String message, List<String> output) {

        public LabResult {
            output = output == null ? List.of() : List.copyOf(output);
        }
    }
}
