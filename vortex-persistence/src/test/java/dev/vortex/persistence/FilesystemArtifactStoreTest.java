package dev.vortex.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.vortex.core.shared.ExecutionId;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("storing artifacts")
class FilesystemArtifactStoreTest {

    @TempDir
    Path workspaceRoot;

    private FilesystemArtifactStore store;
    private ExecutionId executionId;

    @BeforeEach
    void setUp() {
        store = new FilesystemArtifactStore(new VortexWorkspace(workspaceRoot));
        executionId = ExecutionId.generate();
    }

    @Test
    @DisplayName("a written artifact reads back unchanged")
    void writeThenRead() {
        store.writeBytes(executionId, "summary.json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8));

        assertThat(store.read(executionId, "summary.json")).contains("{\"ok\":true}");
    }

    @Test
    @DisplayName("no temporary file survives a successful write")
    void noTemporaryFileSurvives() throws Exception {
        store.writeBytes(executionId, "summary.json", "content".getBytes(StandardCharsets.UTF_8));

        Path directory = Path.of(store.directoryFor(executionId));
        try (var entries = Files.list(directory)) {
            assertThat(entries.map(path -> path.getFileName().toString()))
                    .noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    @Test
    @DisplayName("a write that cannot create its temporary file leaves the previous artifact untouched")
    void aFailedWriteLeavesThePreviousArtifactUntouched() {
        store.writeBytes(executionId, "summary.json", "first".getBytes(StandardCharsets.UTF_8));
        Path directory = Path.of(store.directoryFor(executionId));

        assertThat(directory.toFile().setWritable(false)).isTrue();
        try {
            assertThatThrownBy(() -> store.writeBytes(executionId, "summary.json",
                    "second".getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(UncheckedIOException.class);
        } finally {
            assertThat(directory.toFile().setWritable(true)).isTrue();
        }

        assertThat(store.read(executionId, "summary.json")).contains("first");
    }

    @Test
    @DisplayName("a name that would escape the execution directory is refused")
    void namesCannotTraverse() {
        assertThatThrownBy(() -> store.writeBytes(executionId, "../../.ssh/id_rsa",
                "pwned".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resolves outside its execution directory");
    }
}
