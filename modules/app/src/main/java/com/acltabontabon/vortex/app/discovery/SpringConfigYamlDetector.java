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
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Spring Boot's own {@code application.yml}/{@code .yaml}/{@code .properties} for dependency
 * and observability signals.
 *
 * <p>Needs Jackson's YAML support, which {@code vortex-core} may not depend on (ADR-013) — this is
 * why this detector is an adapter in {@code vortex-app} rather than living in core.
 *
 * <p>Never proposes an {@code ObservationSource}: a monitoring system's address is infrastructure
 * knowledge no git repository states. Actuator/Prometheus configuration here is informational only
 * — see {@code docs/adr/adr-062-prometheus-defaults-are-prefill-only.adoc} for how that field is
 * actually filled in.
 */
public final class SpringConfigYamlDetector implements ProjectDetector {

    private static final Pattern POSTGRES_URL =
            Pattern.compile("jdbc:postgresql://([^:/]+)(?::(\\d+))?/");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Override
    public String name() {
        return "Spring configuration";
    }

    @Override
    public List<Finding> detect(ProjectSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        for (ProjectFile file : snapshot.files()) {
            String name = fileName(file.relativePath());
            if (!isApplicationConfig(name)) {
                continue;
            }
            findings.addAll(detectFrom(file.relativePath(), flatten(file.content(), name)));
        }
        return findings;
    }

    private boolean isApplicationConfig(String name) {
        return name.startsWith("application")
                && (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties"));
    }

    private Map<String, String> flatten(String content, String name) {
        try {
            return name.endsWith(".properties") ? flattenProperties(content) : flattenYaml(content);
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    private Map<String, String> flattenProperties(String content) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(content));
        Map<String, String> flattened = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            flattened.put(key, properties.getProperty(key));
        }
        return flattened;
    }

    private Map<String, String> flattenYaml(String content) throws IOException {
        Map<String, String> flattened = new LinkedHashMap<>();
        JsonNode root = YAML.readTree(content);
        if (root != null) {
            flattenNode("", root, flattened);
        }
        return flattened;
    }

    private void flattenNode(String prefix, JsonNode node, Map<String, String> out) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> flattenNode(
                    prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey(),
                    entry.getValue(), out));
        } else if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(element -> values.add(element.asText()));
            out.put(prefix, String.join(",", values));
        } else if (!node.isMissingNode() && !node.isNull()) {
            out.put(prefix, node.asText());
        }
    }

    private List<Finding> detectFrom(String sourceFile, Map<String, String> config) {
        List<Finding> findings = new ArrayList<>();

        String datasourceUrl = config.get("spring.datasource.url");
        if (datasourceUrl != null) {
            Matcher matcher = POSTGRES_URL.matcher(datasourceUrl);
            if (matcher.find()) {
                Map<String, String> attributes = new LinkedHashMap<>();
                attributes.put("host", matcher.group(1));
                attributes.put("port", matcher.group(2) == null ? "5432" : matcher.group(2));
                findings.add(new Finding(FindingKind.DEPENDENCY_POSTGRESQL, sourceFile,
                        List.of("spring.datasource.url=" + datasourceUrl), Confidence.MEDIUM,
                        attributes));
            }
        }

        String redisHost = firstNonBlank(config.get("spring.data.redis.host"),
                config.get("spring.redis.host"));
        if (redisHost != null) {
            String redisPort = firstNonBlank(config.get("spring.data.redis.port"),
                    config.get("spring.redis.port"), "6379");
            findings.add(new Finding(FindingKind.DEPENDENCY_REDIS, sourceFile,
                    List.of("spring.data.redis.host=" + redisHost), Confidence.MEDIUM,
                    Map.of("host", redisHost, "port", redisPort)));
        }

        String exposure = config.get("management.endpoints.web.exposure.include");
        if (exposure != null) {
            String normalized = exposure.toLowerCase(Locale.ROOT);
            boolean prometheus = normalized.contains("prometheus") || normalized.contains("*");
            findings.add(new Finding(
                    prometheus ? FindingKind.OBSERVABILITY_PROMETHEUS : FindingKind.OBSERVABILITY_ACTUATOR,
                    sourceFile,
                    List.of("management.endpoints.web.exposure.include=" + exposure),
                    prometheus ? Confidence.HIGH : Confidence.MEDIUM, Map.of()));
        }

        return findings;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String fileName(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? relativePath : relativePath.substring(slash + 1);
    }
}
