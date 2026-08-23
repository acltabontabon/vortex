package com.acltabontabon.vortex.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.acltabontabon.vortex.core.resource.LoadGeneratorResourceBudget;
import com.acltabontabon.vortex.core.target.CpuAllocation;
import com.acltabontabon.vortex.core.target.MemoryAllocation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadGeneratorBudgetPreferenceStoreTest {

    @TempDir
    Path home;

    private LoadGeneratorBudgetPreferenceStore store(Path configFile) {
        return new LoadGeneratorBudgetPreferenceStore(configFile);
    }

    @Test
    void writesACustomBudgetAsMillicoresAndMebibytes() throws Exception {
        Path file = home.resolve("config.yaml");
        LoadGeneratorBudgetPreferenceStore store = store(file);

        store.saveBudget(LoadGeneratorResourceBudget.custom(
                CpuAllocation.ofMillicores(2000), MemoryAllocation.ofMebibytes(2048)));

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(written).contains("mode: \"custom\"");
        assertThat(written).contains("cpu-millicores: 2000");
        assertThat(written).contains("memory-mebibytes: 2048");
    }

    @Test
    void writesAnAutomaticBudgetWithNoValues() throws Exception {
        Path file = home.resolve("config.yaml");
        LoadGeneratorBudgetPreferenceStore store = store(file);

        store.saveBudget(LoadGeneratorResourceBudget.automatic());

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(written).contains("mode: \"automatic\"");
        assertThat(written).doesNotContain("cpu-millicores");
        assertThat(written).doesNotContain("memory-mebibytes");
    }

    @Test
    void leavesOtherKeysUntouched() throws Exception {
        Path file = home.resolve("config.yaml");
        Files.writeString(file, """
                vortex:
                  ai:
                    model: qwen3:4b
                """);
        LoadGeneratorBudgetPreferenceStore store = store(file);

        store.saveBudget(LoadGeneratorResourceBudget.custom(
                CpuAllocation.ofMillicores(500), MemoryAllocation.ofMebibytes(256)));

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(written).contains("model: \"qwen3:4b\"");
        assertThat(written).contains("cpu-millicores: 500");
    }

    @Test
    void overwritingReplacesThePreviousBudgetEntirely() throws Exception {
        Path file = home.resolve("config.yaml");
        LoadGeneratorBudgetPreferenceStore store = store(file);

        store.saveBudget(LoadGeneratorResourceBudget.custom(
                CpuAllocation.ofMillicores(2000), MemoryAllocation.ofMebibytes(2048)));
        store.saveBudget(LoadGeneratorResourceBudget.automatic());

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(written).contains("mode: \"automatic\"");
        assertThat(written).doesNotContain("cpu-millicores");
    }
}
