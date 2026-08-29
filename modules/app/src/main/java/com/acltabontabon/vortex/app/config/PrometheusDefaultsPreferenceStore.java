package com.acltabontabon.vortex.app.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Persists what a user changes from Settings → Prometheus defaults.
 *
 * <p>Written to {@code ~/.vortex/config.yaml} under {@code /vortex/prometheus-defaults}, exactly
 * like {@link DynatraceMcpPreferenceStore} writes {@code /vortex/dynatrace-mcp} — a fixed,
 * home-relative path that {@code spring.config.import} reads during startup, so this is still the
 * single place {@code vortex.prometheus-defaults.*} is configured; the Settings page just writes to
 * it instead of a person editing it by hand. Deliberately not nested under {@code
 * /vortex/observability}, which configures the unrelated live-run {@code ObservabilityProvider} — see
 * {@link DynatraceMcpPreferenceStore}'s identical note.
 */
@Component
public class PrometheusDefaultsPreferenceStore {

    private final Path file;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public PrometheusDefaultsPreferenceStore() {
        this(Paths.get(System.getProperty("user.home"), ".vortex", "config.yaml"));
    }

    PrometheusDefaultsPreferenceStore(Path file) {
        this.file = file;
    }

    /** Rewrites {@code vortex.prometheus-defaults} in the file, leaving every other key untouched. */
    public void save(String endpoint, String window, Map<String, String> headers,
            String serviceLabel, String routeLabel, String methodLabel) {
        ObjectNode root = load();
        ObjectNode node = root.withObject("/vortex/prometheus-defaults");
        node.removeAll();
        node.put("endpoint", endpoint == null ? "" : endpoint);
        node.put("window", window == null || window.isBlank() ? "30d" : window);
        node.put("serviceLabel", serviceLabel == null ? "" : serviceLabel);
        node.put("routeLabel", routeLabel == null ? "" : routeLabel);
        node.put("methodLabel", methodLabel == null ? "" : methodLabel);
        ObjectNode headerNode = node.putObject("headers");
        if (headers != null) {
            headers.forEach(headerNode::put);
        }
        write(root);
    }

    private ObjectNode load() {
        if (!Files.isRegularFile(file)) {
            return yaml.createObjectNode();
        }
        try {
            JsonNode existing = yaml.readTree(file.toFile());
            return existing instanceof ObjectNode node ? node : yaml.createObjectNode();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }

    private void write(ObjectNode root) {
        try {
            Files.createDirectories(file.getParent());
            yaml.writeValue(file.toFile(), root);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }
}
