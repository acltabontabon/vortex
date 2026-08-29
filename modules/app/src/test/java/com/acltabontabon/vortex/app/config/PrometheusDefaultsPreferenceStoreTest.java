package com.acltabontabon.vortex.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrometheusDefaultsPreferenceStoreTest {

    @TempDir
    Path home;

    private PrometheusDefaultsPreferenceStore store(Path configFile) {
        return new PrometheusDefaultsPreferenceStore(configFile);
    }

    @Test
    void writesEndpointWindowLabelsAndHeaders() throws Exception {
        Path file = home.resolve("config.yaml");
        PrometheusDefaultsPreferenceStore store = store(file);

        store.save("http://prometheus.internal:9090", "30d", Map.of("Authorization", "Bearer ${PROM_TOKEN}"),
                "app", "endpoint", "verb");

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(written).contains("endpoint: \"http://prometheus.internal:9090\"");
        assertThat(written).contains("window: \"30d\"");
        assertThat(written).contains("serviceLabel: \"app\"");
        assertThat(written).contains("routeLabel: \"endpoint\"");
        assertThat(written).contains("methodLabel: \"verb\"");
        assertThat(written).contains("Authorization: \"Bearer ${PROM_TOKEN}\"");
    }

    @Test
    void writesABlankEndpointAsClearingTheDefault() throws Exception {
        Path file = home.resolve("config.yaml");
        PrometheusDefaultsPreferenceStore store = store(file);

        store.save("", "30d", Map.of(), "", "", "");

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(written).contains("endpoint: \"\"");
    }

    @Test
    void leavesOtherKeysUntouched() throws Exception {
        Path file = home.resolve("config.yaml");
        Files.writeString(file, """
                vortex:
                  ai:
                    model: qwen3:4b
                """);
        PrometheusDefaultsPreferenceStore store = store(file);

        store.save("http://prometheus.internal:9090", "30d", Map.of(), "", "", "");

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(written).contains("model: \"qwen3:4b\"");
        assertThat(written).contains("prometheus-defaults");
    }

    @Test
    void overwritingReplacesThePreviousDefaultsEntirely() throws Exception {
        Path file = home.resolve("config.yaml");
        PrometheusDefaultsPreferenceStore store = store(file);

        store.save("http://old:9090", "7d", Map.of("X-Old", "${OLD_TOKEN}"), "old-service", "", "");
        store.save("http://new:9090", "30d", Map.of(), "", "", "");

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(written).contains("endpoint: \"http://new:9090\"");
        assertThat(written).doesNotContain("old:9090");
        assertThat(written).doesNotContain("X-Old");
        assertThat(written).doesNotContain("old-service");
    }
}
