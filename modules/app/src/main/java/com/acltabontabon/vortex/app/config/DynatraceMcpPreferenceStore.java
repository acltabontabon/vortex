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
import org.springframework.stereotype.Component;

/**
 * Persists what a user changes from Settings → Dynatrace: whether it is enabled, its endpoint, and
 * the default observation window.
 *
 * <p>Written to {@code ~/.vortex/config.yaml} under {@code /vortex/dynatrace-mcp}, exactly like
 * {@link AiModelPreferenceStore} writes {@code /vortex/ai} — a fixed, home-relative path that
 * {@code spring.config.import} reads during startup, so this is still the single place
 * {@code vortex.dynatrace-mcp.*} is configured; the Settings page just writes to it instead of a
 * person editing it by hand. Deliberately not nested under {@code /vortex/observability}, which
 * configures the unrelated live-run {@code ObservabilityProvider}.
 */
@Component
public class DynatraceMcpPreferenceStore {

    private final Path file;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public DynatraceMcpPreferenceStore() {
        this(Paths.get(System.getProperty("user.home"), ".vortex", "config.yaml"));
    }

    DynatraceMcpPreferenceStore(Path file) {
        this.file = file;
    }

    /** Rewrites {@code vortex.dynatrace-mcp} in the file, leaving every other key untouched. */
    public void save(boolean enabled, String endpoint, String defaultWindow, String organization) {
        ObjectNode root = load();
        ObjectNode node = root.withObject("/vortex/dynatrace-mcp");
        node.put("enabled", enabled);
        node.put("endpoint", endpoint == null ? "" : endpoint);
        node.put("defaultWindow", defaultWindow == null || defaultWindow.isBlank() ? "30d" : defaultWindow);
        node.put("organization", organization == null ? "" : organization);
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
