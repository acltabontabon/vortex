package dev.vortex.core.execution;

/**
 * Why an execution did not complete.
 *
 * <p>Each reason carries what a user should do about it, because "process exited 1" is not an error
 * message — it is the absence of one.
 */
public enum FailureReason {

    PREFLIGHT_FAILED("Preflight checks failed",
            "One or more checks failed before any traffic was generated. The preflight report lists "
                    + "which, and what to do about each."),

    ENGINE_UNAVAILABLE("Load generator unavailable",
            "Vortex could not start k6. Install it, or configure its location under "
                    + "Settings → Execution engine."),

    ENGINE_FAILED("Load generator failed",
            "k6 started but exited unexpectedly. The captured standard error output is attached to "
                    + "this execution."),

    TARGET_UNREACHABLE("Target unreachable",
            "The service did not respond at the configured address. Check that it is running and "
                    + "that the URL and port are correct."),

    RESULTS_UNREADABLE("Results could not be read",
            "The run finished but Vortex could not parse its output. The raw artifacts are "
                    + "preserved so nothing is lost."),

    INTERRUPTED("Interrupted",
            "Vortex stopped while this run was in progress. Vortex does not adopt orphaned engine "
                    + "processes on restart, so this run was marked failed rather than left pending."),

    INTERNAL_ERROR("Internal error",
            "Something in Vortex itself went wrong. The application log holds the details."),

    DOCKER_UNAVAILABLE("Docker unavailable",
            "Vortex could not reach a usable Docker installation. Install Docker Desktop, or Docker "
                    + "Engine on Linux, make sure its daemon is running, and try again."),

    IMAGE_NOT_FOUND("Docker image not found",
            "The configured image is not present locally. Vortex never pulls or builds images on "
                    + "your behalf — pull or build it yourself, then try again."),

    CONTAINER_START_FAILED("Container did not start",
            "Docker could not create or start the managed container. Check the image reference, the "
                    + "configured container port, and the Docker daemon's own logs for why."),

    TARGET_READINESS_TIMEOUT("Target did not become ready",
            "The managed container started but never accepted traffic within the readiness window. "
                    + "Check the service's own startup logs, or extend the readiness timeout if it "
                    + "normally takes longer to warm up."),

    PORT_RESOLUTION_FAILED("Could not resolve the container's port",
            "Docker did not report a host port for the container's configured port. Confirm the "
                    + "image actually listens on that port and that nothing else interfered with the "
                    + "mapping."),

    RESOURCE_LIMIT_APPLICATION_FAILED("Resource limit could not be applied",
            "Vortex could not confirm that Docker actually applied the configured CPU/memory limit "
                    + "to the container. The run was not started against a container that might be "
                    + "missing its intended resource constraint — check the Docker daemon's own logs, "
                    + "and that the requested CPU/memory values are ones this machine's Docker "
                    + "installation can actually enforce."),

    COMPOSE_FILE_NOT_FOUND("Compose file not found",
            "The configured Compose file does not exist at that path, or Docker could not read it as "
                    + "a usable Compose file (for example, malformed YAML). Check the path is correct "
                    + "and relative to the service's repository, and that the file itself is valid."),

    COMPOSE_SERVICE_NOT_FOUND("Compose service not declared",
            "The configured service name is not declared in the Compose file at all. Check the "
                    + "spelling against the file's own service names."),

    COMPOSE_SERVICE_NOT_RUNNING("Compose service not running",
            "The configured service is declared in the Compose file but is not currently running. "
                    + "Vortex only attaches to an already-running Compose service — it never runs "
                    + "'docker compose up' on your behalf. Start the stack yourself, then try again.");

    private final String label;
    private final String guidance;

    FailureReason(String label, String guidance) {
        this.label = label;
        this.guidance = guidance;
    }

    public String label() {
        return label;
    }

    /** What the user can do next. */
    public String guidance() {
        return guidance;
    }
}
