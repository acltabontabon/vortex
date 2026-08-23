package com.acltabontabon.vortex.core.port;

import com.acltabontabon.vortex.core.shared.ExecutionId;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Stores the raw evidence an execution produces.
 *
 * <p>Summaries go in the database; artifacts go on the filesystem with a reference in the database.
 * Engine output for a long run can reach hundreds of megabytes, which does not belong in a
 * relational column, and keeping it as files means it can be inspected with ordinary tools long
 * after the fact.
 *
 * <p>Implementations must resolve paths within the execution's own directory and reject anything
 * that escapes it.
 */
public interface ArtifactStore {

    /** Writes text content, returning the path relative to the execution directory. */
    String write(ExecutionId executionId, String name, String content);

    /**
     * Writes binary content, returning the path relative to the execution directory.
     *
     * <p>Separate from {@link #write} rather than replacing it: most artifacts are text, and routing
     * them through a byte array would mean an avoidable UTF-8 round trip on every plan and log. This
     * exists because a PDF report is not text and must not be treated as though it were.
     */
    String writeBytes(ExecutionId executionId, String name, byte[] content);

    /** Reads text content previously written. */
    Optional<String> read(ExecutionId executionId, String name);

    /** Opens a stream, so large artifacts need not be held in memory. */
    Optional<InputStream> open(ExecutionId executionId, String name);

    /** Names of the artifacts stored for an execution. */
    List<String> list(ExecutionId executionId);

    /** The absolute directory holding an execution's artifacts, for display. */
    String directoryFor(ExecutionId executionId);

    /** Size in bytes, for display and for deciding whether to render inline. */
    Optional<Long> sizeOf(ExecutionId executionId, String name);

    /**
     * Removes every artifact stored for an execution, and the execution's directory itself.
     *
     * <p>A no-op when the directory does not exist, so callers need not check first.
     */
    void delete(ExecutionId executionId);

    /**
     * Opens an artifact for incremental writes, for content produced while a run is still in
     * progress — resource telemetry being the first user of this.
     *
     * <p>Distinct from {@link #write}/{@link #writeBytes}, which hold their whole content in memory
     * before writing it once: a run-scoped time series is written one line at a time as it is
     * sampled, and holding hours of it in memory first is exactly what this capability exists to
     * avoid.
     */
    ArtifactWriter openForAppend(ExecutionId executionId, String name);

    /**
     * A handle for writing one artifact incrementally.
     *
     * <p>Each {@link #writeLine} is expected to be flushed to the operating system immediately, so a
     * killed Vortex process does not lose lines already written — but that is process-crash
     * tolerance, not disk-level durability. A host crash or power loss before the OS itself persists
     * the data is not covered, and does not need to be: a partially written artifact is an explicit,
     * expected outcome for whatever reads it back, not corruption.
     */
    interface ArtifactWriter extends AutoCloseable {

        /** Appends one line, terminated for the caller — implementations must not require the line
         *  to already end with a newline. */
        void writeLine(String line);

        @Override
        void close();
    }
}
