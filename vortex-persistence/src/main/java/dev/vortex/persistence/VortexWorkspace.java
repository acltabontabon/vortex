package dev.vortex.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * The directory Vortex keeps its own state in.
 *
 * <pre>
 * ~/.vortex/
 * ├── vortex.db
 * ├── logs/
 * └── executions/&lt;execution-id&gt;/
 *     ├── plan.json           the effective plan, with secret references and never their values
 *     ├── generated-test.js   the workload as executed
 *     ├── k6-summary.json     the engine's own summary
 *     ├── raw-metrics.json.gz the full sample stream, compressed
 *     ├── stdout.log stderr.log
 *     └── report.html
 * </pre>
 *
 * <p>Separate from a project's own workspace, which holds {@code .vortex/vortex.yaml} and belongs
 * in version control. This directory holds local state and evidence; that one holds intent.
 */
public final class VortexWorkspace {

    public static final String DEFAULT_DIRECTORY_NAME = ".vortex";
    public static final String DATABASE_FILE = "vortex.db";

    private final Path root;

    public VortexWorkspace(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** The default location, {@code ~/.vortex}. */
    public static VortexWorkspace defaultLocation() {
        return new VortexWorkspace(
                Paths.get(System.getProperty("user.home"), DEFAULT_DIRECTORY_NAME));
    }

    public Path root() {
        return root;
    }

    public Path databaseFile() {
        return root.resolve(DATABASE_FILE);
    }

    public Path executionsDirectory() {
        return root.resolve("executions");
    }

    public Path executionDirectory(String executionId) {
        return executionsDirectory().resolve(executionId);
    }

    public Path logsDirectory() {
        return root.resolve("logs");
    }

    /** Creates the workspace layout, failing with a clear message when the location is unusable. */
    public VortexWorkspace ensureExists() {
        try {
            Files.createDirectories(executionsDirectory());
            Files.createDirectories(logsDirectory());
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Vortex could not create its workspace at " + root
                            + ". Check that the location is writable, or choose a different one "
                            + "under Settings.", e);
        }
        return this;
    }

    public boolean isWritable() {
        return Files.isDirectory(root) && Files.isWritable(root);
    }

    /**
     * The JDBC URL for the local database.
     *
     * <p>Write-ahead logging is enabled so a reader — the UI polling history while a test runs —
     * never blocks the writer recording that test's progress. Foreign keys are enabled explicitly
     * because SQLite leaves them off by default, which would quietly turn the schema's cascade
     * rules into decoration.
     */
    public String jdbcUrl() {
        return "jdbc:sqlite:" + databaseFile()
                + "?journal_mode=WAL&synchronous=NORMAL&foreign_keys=on&busy_timeout=5000";
    }
}
