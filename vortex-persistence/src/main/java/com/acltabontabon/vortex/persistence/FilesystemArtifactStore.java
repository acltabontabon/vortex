package com.acltabontabon.vortex.persistence;

import com.acltabontabon.vortex.core.port.ArtifactStore;
import com.acltabontabon.vortex.core.shared.ExecutionId;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores each execution's raw evidence as files.
 *
 * <p>Summaries go in the database; the evidence behind them goes here. A summary answers the
 * questions someone thought to ask when they wrote it, and the raw artifacts answer the one that
 * comes up six months later — so Vortex keeps both, and keeps the raw form inspectable with
 * ordinary tools rather than locked inside a database column.
 *
 * <h2>Path containment</h2>
 * Artifact names reach this class from parsed documents and, indirectly, from user configuration.
 * Every name is resolved against the execution's own directory and rejected if the result escapes
 * it, so {@code ../../.ssh/id_rsa} is an error rather than a file read.
 */
public final class FilesystemArtifactStore implements ArtifactStore {

    private static final Logger log = LoggerFactory.getLogger(FilesystemArtifactStore.class);

    private final VortexWorkspace workspace;

    public FilesystemArtifactStore(VortexWorkspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String write(ExecutionId executionId, String name, String content) {
        return writeBytes(executionId, name,
                (content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String writeBytes(ExecutionId executionId, String name, byte[] content) {
        // Goes through the same resolve(), so binary artifacts get the same containment check.
        // A second write path would be a second chance to forget it.
        Path target = resolve(executionId, name);
        try {
            Files.createDirectories(target.getParent());
            writeAtomically(target, content == null ? new byte[0] : content);
            return name;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Vortex could not write the artifact '" + name + "' for execution "
                            + executionId + ". Check that " + workspace.root() + " is writable.", e);
        }
    }

    /**
     * Writes to a sibling temporary file, then renames it into place, so a crash mid-write leaves
     * either the old artifact intact or the new one complete — never a truncated file with no signal
     * that it never finished.
     */
    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(tmp, content);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    @Override
    public Optional<String> read(ExecutionId executionId, String name) {
        Path target = resolve(executionId, name);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(target, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Could not read artifact {} for execution {}: {}", name, executionId,
                    e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<InputStream> open(ExecutionId executionId, String name) {
        Path target = resolve(executionId, name);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.newInputStream(target));
        } catch (IOException e) {
            log.warn("Could not open artifact {} for execution {}: {}", name, executionId,
                    e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<String> list(ExecutionId executionId) {
        Path directory = workspace.executionDirectory(executionId.value());
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var entries = Files.list(directory)) {
            return entries
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .toList();
        } catch (IOException e) {
            log.warn("Could not list artifacts for execution {}: {}", executionId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public String directoryFor(ExecutionId executionId) {
        return workspace.executionDirectory(executionId.value()).toString();
    }

    @Override
    public Optional<Long> sizeOf(ExecutionId executionId, String name) {
        Path target = resolve(executionId, name);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.size(target));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(ExecutionId executionId) {
        Path directory = workspace.executionDirectory(executionId.value());
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Vortex could not delete the artifacts for execution " + executionId + " at "
                            + directory + ".", e);
        }
    }

    @Override
    public ArtifactWriter openForAppend(ExecutionId executionId, String name) {
        Path target = resolve(executionId, name);
        try {
            Files.createDirectories(target.getParent());
            BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return new AppendingWriter(writer);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Vortex could not open the artifact '" + name + "' for execution " + executionId
                            + " for incremental writes. Check that " + workspace.root()
                            + " is writable.", e);
        }
    }

    /** Flushes after every line: a killed Vortex process must not lose a line already written, even
     *  though this alone does not guarantee the line survives a host crash (that would need fsync). */
    private static final class AppendingWriter implements ArtifactWriter {

        private final BufferedWriter writer;

        AppendingWriter(BufferedWriter writer) {
            this.writer = writer;
        }

        @Override
        public void writeLine(String line) {
            try {
                writer.write(line);
                writer.write('\n');
                writer.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("Vortex could not write to an in-progress artifact.", e);
            }
        }

        @Override
        public void close() {
            try {
                writer.close();
            } catch (IOException e) {
                throw new UncheckedIOException("Vortex could not close an in-progress artifact.", e);
            }
        }
    }

    /**
     * Resolves an artifact name within its execution directory.
     *
     * @throws IllegalArgumentException when the name would escape the directory
     */
    Path resolve(ExecutionId executionId, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An artifact name must not be blank");
        }
        Path directory = workspace.executionDirectory(executionId.value()).normalize();
        Path resolved = directory.resolve(name).normalize();
        if (!resolved.startsWith(directory)) {
            throw new IllegalArgumentException(
                    "The artifact name '" + name + "' resolves outside its execution directory "
                            + "and was rejected.");
        }
        return resolved;
    }
}
