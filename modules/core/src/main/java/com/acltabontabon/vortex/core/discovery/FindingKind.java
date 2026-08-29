package com.acltabontabon.vortex.core.discovery;

/**
 * What kind of thing a {@link Finding} identified.
 *
 * <p>A closed, deliberately small set — Project Discovery's v1 scope. See
 * {@code docs/04-reference/project-discovery.adoc} for what is and is not detected.
 */
public enum FindingKind {

    BUILD_TOOL_MAVEN("Maven"),
    FRAMEWORK_SPRING_BOOT("Spring Boot"),
    DEPENDENCY_POSTGRESQL("PostgreSQL"),
    DEPENDENCY_REDIS("Redis"),
    DEPENDENCY_KAFKA("Kafka"),
    DEPENDENCY_WIREMOCK("WireMock"),
    OPENAPI_SPEC("OpenAPI specification"),
    OBSERVABILITY_ACTUATOR("Spring Boot Actuator"),
    OBSERVABILITY_PROMETHEUS("Prometheus metrics"),
    EXECUTION_HINT_DOCKERFILE("Dockerfile"),
    EXECUTION_HINT_COMPOSE_SERVICE("Compose service"),
    ENV_TEMPLATE("Environment template");

    private final String label;

    FindingKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
