package com.acltabontabon.vortex.k6;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a file that is still being written to, line by line, and stops when the writer is done.
 *
 * <p>This is what makes live progress live. The load generator appends samples to its output file
 * throughout a run; reading that file only after the process exits would mean a test showing nothing
 * for fifteen minutes and then everything at once, which is not progress reporting — it is a
 * progress bar that fills in after the fact.
 *
 * <h2>Only complete lines</h2>
 * A line is emitted only once its terminating newline has been seen. Reading a file mid-write will
 * otherwise hand back half a JSON object, and a parser that has to cope with fragments is a parser
 * that will eventually accept a fragment as valid. The final line of a killed run is simply never
 * emitted, which is correct: it was never finished.
 *
 * <h2>Waiting</h2>
 * At end of file the iterator pauses briefly and looks again, until the supplied predicate says the
 * writer has finished. It then performs one last read so nothing written in the final moments is
 * lost.
 */
final class TailingLines implements Iterable<String> {

    private static final Logger log = LoggerFactory.getLogger(TailingLines.class);

    /** How long to wait before checking a quiet file again. */
    private static final long POLL_MILLIS = 200;

    /** How long to wait for the file to appear at all before giving up. */
    private static final long APPEAR_TIMEOUT_MILLIS = 30_000;

    private final Path path;
    private final BooleanSupplier writerFinished;

    TailingLines(Path path, BooleanSupplier writerFinished) {
        this.path = path;
        this.writerFinished = writerFinished;
    }

    @Override
    public Iterator<String> iterator() {
        return new TailingIterator();
    }

    private final class TailingIterator implements Iterator<String> {

        private InputStream in;
        private final StringBuilder pending = new StringBuilder(512);
        private final byte[] buffer = new byte[8192];
        private String next;
        private boolean exhausted;

        @Override
        public boolean hasNext() {
            if (next != null) {
                return true;
            }
            if (exhausted) {
                return false;
            }
            next = readLine();
            if (next == null) {
                exhausted = true;
                close();
                return false;
            }
            return true;
        }

        @Override
        public String next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            String line = next;
            next = null;
            return line;
        }

        private String readLine() {
            while (true) {
                int newline = indexOfNewline();
                if (newline >= 0) {
                    String line = pending.substring(0, newline);
                    pending.delete(0, newline + 1);
                    return line;
                }

                if (!ensureOpen()) {
                    return null;
                }

                int read;
                try {
                    read = in.read(buffer);
                } catch (IOException e) {
                    log.debug("Could not read the metric stream: {}", e.getMessage());
                    return null;
                }

                if (read > 0) {
                    pending.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    continue;
                }

                // Nothing more right now. If the writer has finished, one final read has already
                // been attempted above, so anything still buffered is an incomplete line and is
                // deliberately discarded.
                if (writerFinished.getAsBoolean()) {
                    return null;
                }
                sleep(POLL_MILLIS);
            }
        }

        private int indexOfNewline() {
            for (int i = 0; i < pending.length(); i++) {
                if (pending.charAt(i) == '\n') {
                    return i;
                }
            }
            return -1;
        }

        /** Opens the file, waiting for the writer to create it. */
        private boolean ensureOpen() {
            if (in != null) {
                return true;
            }
            long deadline = System.nanoTime() + APPEAR_TIMEOUT_MILLIS * 1_000_000L;
            while (!Files.isRegularFile(path)) {
                if (writerFinished.getAsBoolean() || System.nanoTime() > deadline) {
                    return false;
                }
                sleep(POLL_MILLIS);
            }
            try {
                in = Files.newInputStream(path);
                return true;
            } catch (IOException e) {
                log.debug("Could not open the metric stream at {}: {}", path, e.getMessage());
                return false;
            }
        }

        private void close() {
            if (in == null) {
                return;
            }
            try {
                in.close();
            } catch (IOException e) {
                log.debug("Could not close the metric stream: {}", e.getMessage());
            } finally {
                in = null;
            }
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exhausted = true;
            }
        }
    }
}
