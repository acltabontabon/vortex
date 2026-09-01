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
 * Persists the AI settings a user can change from Settings → Local AI: which model to use, and
 * which endpoint to reach it at.
 *
 * <p>Written to {@code ~/.vortex/config.yaml} — a fixed, home-relative path rather than one derived
 * from the (rarely relocated) workspace directory, because {@code spring.config.import} reads this
 * file during startup, before any workspace property has been resolved. {@code application.yaml}
 * imports it, so this is still the single place {@code vortex.ai.model} and {@code
 * vortex.ai.base-url} are configured; the settings page just writes to it instead of a person
 * editing it by hand.
 */
@Component
public class AiModelPreferenceStore {

    private final Path file;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public AiModelPreferenceStore() {
        this(Paths.get(System.getProperty("user.home"), ".vortex", "config.yaml"));
    }

    AiModelPreferenceStore(Path file) {
        this.file = file;
    }

    /** Rewrites {@code vortex.ai.model} in the file, leaving every other key untouched. */
    public void saveModel(String model) {
        ObjectNode root = load();
        root.withObject("/vortex/ai").put("model", model);
        write(root);
    }

    /** Rewrites {@code vortex.ai.base-url} in the file, leaving every other key untouched. */
    public void saveBaseUrl(String baseUrl) {
        ObjectNode root = load();
        root.withObject("/vortex/ai").put("base-url", baseUrl);
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
