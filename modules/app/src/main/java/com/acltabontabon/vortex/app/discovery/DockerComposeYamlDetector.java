package com.acltabontabon.vortex.app.discovery;

import com.acltabontabon.vortex.core.discovery.Confidence;
import com.acltabontabon.vortex.core.discovery.Finding;
import com.acltabontabon.vortex.core.discovery.FindingKind;
import com.acltabontabon.vortex.core.discovery.ProjectFile;
import com.acltabontabon.vortex.core.discovery.ProjectSnapshot;
import com.acltabontabon.vortex.core.port.ProjectDetector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads a Compose file for known dependency images and the service that looks like the application
 * itself.
 *
 * <p>Needs Jackson's YAML support, which {@code vortex-core} may not depend on (ADR-013) — this is
 * why this detector is an adapter in {@code vortex-app} rather than living in core.
 *
 * <p>The host side of a port mapping ({@code "9090:8080"}'s {@code 9090}) is kept as evidence text
 * only, never modeled: {@code ContainerPort} has no host-port counterpart anywhere in {@code
 * ExecutionTarget} — Vortex always talks to the container directly.
 */
public final class DockerComposeYamlDetector implements ProjectDetector {

    private static final Set<String> COMPOSE_FILE_NAMES =
            Set.of("compose.yaml", "compose.yml", "docker-compose.yml", "docker-compose.yaml");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Override
    public String name() {
        return "Docker Compose";
    }

    @Override
    public List<Finding> detect(ProjectSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        for (ProjectFile file : snapshot.files()) {
            if (COMPOSE_FILE_NAMES.contains(fileName(file.relativePath()))) {
                findings.addAll(detectFromComposeFile(file));
            }
        }
        return findings;
    }

    private List<Finding> detectFromComposeFile(ProjectFile file) {
        JsonNode root;
        try {
            root = YAML.readTree(file.content());
        } catch (Exception e) {
            return List.of(new Finding(FindingKind.EXECUTION_HINT_COMPOSE_SERVICE, file.relativePath(),
                    List.of("Could not be parsed: " + e.getMessage()), Confidence.LOW, Map.of()));
        }
        if (root == null || !root.has("services") || !root.get("services").isObject()) {
            return List.of();
        }

        List<Finding> findings = new ArrayList<>();
        root.get("services").fields().forEachRemaining(entry ->
                findings.addAll(detectFromService(file.relativePath(), entry.getKey(), entry.getValue())));
        return findings;
    }

    private List<Finding> detectFromService(String sourceFile, String serviceName, JsonNode service) {
        List<Finding> findings = new ArrayList<>();
        PortMapping port = firstPort(service.get("ports"));

        String image = service.path("image").isMissingNode() ? null : service.path("image").asText();
        if (image != null && !image.isBlank()) {
            FindingKind kind = kindForImage(image);
            if (kind != null) {
                Map<String, String> attributes = new LinkedHashMap<>();
                attributes.put("image", image);
                attributes.put("composeService", serviceName);
                if (!port.containerPort().isBlank()) {
                    attributes.put("containerPort", port.containerPort());
                }
                findings.add(new Finding(kind, sourceFile,
                        List.of("image: " + image + " (service '" + serviceName + "')"),
                        Confidence.HIGH, attributes));
            }
        }

        if (service.has("build")) {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("serviceName", serviceName);
            if (!port.containerPort().isBlank()) {
                attributes.put("containerPort", port.containerPort());
            }
            List<String> evidence = new ArrayList<>();
            evidence.add("build: (compose service '" + serviceName + "')");
            if (!port.containerPort().isBlank()) {
                evidence.add("ports: " + (port.hostPort().isBlank()
                        ? port.containerPort()
                        : port.hostPort() + ":" + port.containerPort()));
            }
            Confidence confidence = port.containerPort().isBlank() ? Confidence.MEDIUM : Confidence.HIGH;
            findings.add(new Finding(FindingKind.EXECUTION_HINT_COMPOSE_SERVICE, sourceFile, evidence,
                    confidence, attributes));
        }

        return findings;
    }

    private static FindingKind kindForImage(String image) {
        String lower = image.toLowerCase(Locale.ROOT);
        if (lower.contains("postgres")) {
            return FindingKind.DEPENDENCY_POSTGRESQL;
        }
        if (lower.contains("redis")) {
            return FindingKind.DEPENDENCY_REDIS;
        }
        if (lower.contains("kafka") || lower.contains("confluentinc")) {
            return FindingKind.DEPENDENCY_KAFKA;
        }
        if (lower.contains("wiremock")) {
            return FindingKind.DEPENDENCY_WIREMOCK;
        }
        return null;
    }

    private static PortMapping firstPort(JsonNode portsNode) {
        if (portsNode == null || !portsNode.isArray() || portsNode.isEmpty()) {
            return new PortMapping("", "");
        }
        JsonNode first = portsNode.get(0);
        if (!first.isTextual() && !first.isNumber()) {
            return new PortMapping("", "");
        }
        return parsePort(first.asText());
    }

    /** Handles {@code "8080"}, {@code "9090:8080"} and {@code "127.0.0.1:9090:8080"}. */
    private static PortMapping parsePort(String raw) {
        String[] parts = raw.split(":");
        if (parts.length == 1) {
            return new PortMapping("", stripProtocol(parts[0]));
        }
        if (parts.length == 2) {
            return new PortMapping(parts[0], stripProtocol(parts[1]));
        }
        return new PortMapping(parts[1], stripProtocol(parts[2]));
    }

    private static String stripProtocol(String value) {
        int slash = value.indexOf('/');
        return slash < 0 ? value : value.substring(0, slash);
    }

    private static String fileName(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? relativePath : relativePath.substring(slash + 1);
    }

    private record PortMapping(String hostPort, String containerPort) {
    }
}
