package dev.vortex.app.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.vortex.core.resource.LoadGeneratorResourceBudget;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Persists the load generator's resource budget, set from Settings → Load Generator Resources.
 *
 * <p>Written to {@code ~/.vortex/config.yaml} — the same fixed, home-relative file {@link
 * AiModelPreferenceStore} already uses for the one other mutable, per-machine, day-to-day setting
 * Vortex has. A resource budget belongs there rather than in a project's {@code vortex.yaml}: it
 * describes what this machine can safely give the load generator, not the test's own intent, and a
 * laptop's conservative numbers must never travel with a shared project file to run unmodified on a
 * much larger CI box.
 */
@Component
public class LoadGeneratorBudgetPreferenceStore {

    private final Path file;
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    public LoadGeneratorBudgetPreferenceStore() {
        this(Paths.get(System.getProperty("user.home"), ".vortex", "config.yaml"));
    }

    LoadGeneratorBudgetPreferenceStore(Path file) {
        this.file = file;
    }

    /** Rewrites {@code vortex.load-generator} in the file, leaving every other key untouched. */
    public void saveBudget(LoadGeneratorResourceBudget budget) {
        ObjectNode root = load();
        ObjectNode node = root.withObject("/vortex/load-generator");
        node.removeAll();
        node.put("mode", budget.mode().name().toLowerCase(Locale.ROOT));
        budget.envelope().cpuIfPresent()
                .ifPresent(cpu -> node.put("cpu-millicores", cpu.millicores()));
        budget.envelope().memoryIfPresent()
                .ifPresent(memory -> node.put("memory-mebibytes", memory.bytes() / (1024 * 1024)));
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
