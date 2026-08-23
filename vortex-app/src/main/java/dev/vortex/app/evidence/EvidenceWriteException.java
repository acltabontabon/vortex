package dev.vortex.app.evidence;

/** Thrown when a run's evidence cannot be written to disk, so a caller can log it plainly. */
public final class EvidenceWriteException extends RuntimeException {

    public EvidenceWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
