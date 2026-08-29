package com.acltabontabon.vortex.app.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.acltabontabon.vortex.core.discovery.ProjectSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSnapshotBuilderTest {

    private final ProjectSnapshotBuilder builder = new ProjectSnapshotBuilder();

    @Test
    void aMissingDirectoryIsRejected(@TempDir Path root) {
        assertThrows(IllegalArgumentException.class,
                () -> builder.build(root.resolve("does-not-exist").toString()));
    }

    @Test
    void readsOnlyTheKnownCandidateFiles(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("README.md"), "not a candidate, never read");

        ProjectSnapshotBuilder.Result result = builder.build(root.toString());

        ProjectSnapshot snapshot = result.snapshot();
        assertTrue(snapshot.file("pom.xml").isPresent());
        assertTrue(snapshot.file("README.md").isEmpty());
    }

    @Test
    void aRealDotEnvIsNeverACandidate(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve(".env"), "DB_PASSWORD=supersecretvalue123");
        Files.writeString(root.resolve(".env.example"), "DB_PASSWORD=changeme");

        ProjectSnapshot snapshot = builder.build(root.toString()).snapshot();

        assertTrue(snapshot.file(".env").isEmpty());
        assertTrue(snapshot.file(".env.example").isPresent());
    }

    @Test
    void anOversizedFileIsSkippedAndReportedAsAPartialFailure(@TempDir Path root) throws IOException {
        // Two megabytes plus one byte, one over the limit.
        byte[] tooLarge = new byte[2 * 1024 * 1024 + 1];
        Files.write(root.resolve("pom.xml"), tooLarge);

        ProjectSnapshotBuilder.Result result = builder.build(root.toString());

        assertTrue(result.snapshot().file("pom.xml").isEmpty());
        assertTrue(result.partialFailures().stream().anyMatch(line -> line.contains("pom.xml")));
    }

    @Test
    void aBinaryFileIsSkippedAndReportedAsAPartialFailure(@TempDir Path root) throws IOException {
        // Invalid UTF-8: a lone continuation byte can never start a valid sequence.
        Files.write(root.resolve("pom.xml"), new byte[] {(byte) 0x80, (byte) 0x81, (byte) 0x82});

        ProjectSnapshotBuilder.Result result = builder.build(root.toString());

        assertTrue(result.snapshot().file("pom.xml").isEmpty());
        assertTrue(result.partialFailures().stream()
                .anyMatch(line -> line.contains("pom.xml") && line.contains("text file")));
    }

    @Test
    void aSymlinkEscapingTheProjectRootIsRefused(@TempDir Path root) throws IOException {
        Path outside = Files.createTempDirectory("discovery-outside");
        try {
            Files.writeString(outside.resolve("secret.xml"), "<project/>");
            Path link = root.resolve("pom.xml");
            try {
                Files.createSymbolicLink(link, outside.resolve("secret.xml"));
            } catch (UnsupportedOperationException | IOException e) {
                return; // symlinks unsupported on this filesystem — nothing to assert
            }

            ProjectSnapshotBuilder.Result result = builder.build(root.toString());

            assertFalse(result.snapshot().file("pom.xml").isPresent());
            assertTrue(result.partialFailures().stream()
                    .anyMatch(line -> line.contains("outside the project directory")));
        } finally {
            Files.deleteIfExists(outside.resolve("secret.xml"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void findsProfileVariantsAndNestedCompiledFiles(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(root.resolve("src/main/resources/application-local.yml"), "server:\n  port: 8080\n");
        Files.writeString(root.resolve("compose.yaml"), "services: {}\n");

        ProjectSnapshot snapshot = builder.build(root.toString()).snapshot();

        assertTrue(snapshot.file("src/main/resources/application-local.yml").isPresent());
        assertTrue(snapshot.file("compose.yaml").isPresent());
        assertEquals(2, snapshot.files().size());
    }
}
