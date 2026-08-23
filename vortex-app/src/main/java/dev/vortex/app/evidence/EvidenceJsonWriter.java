package dev.vortex.app.evidence;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.vortex.core.evidence.RunEvidence;
import java.nio.charset.StandardCharsets;

/**
 * The machine-readable form of a run's evidence: a versioned envelope, pretty-printed and stable.
 *
 * <p>Written into every completed run's artifact directory, so the directory is self-describing
 * without asking. This is the shape a later comparison reads and an external tool parses. It
 * therefore has to be boring: the same evidence must produce the same bytes on any machine, and a
 * field must not change meaning between releases without the version saying so.
 *
 * <p>Uses its own mapper rather than the persistence one. That is a deliberate refusal to share:
 * {@code JsonDocuments.mapper()} exists to round-trip the internal object graph and carries type
 * discriminators for sealed hierarchies. Publishing it would make every internal rename a breaking
 * change for whoever reads this file, for no gain.
 */
public final class EvidenceJsonWriter {

    private final ObjectMapper mapper;

    public EvidenceJsonWriter() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                // ISO-8601 strings, not epoch numbers: readable in a diff and unambiguous in a
                // timezone.
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Insertion order is the contract. Sorting would reorder the document whenever a
                // field is renamed, which is a diff nobody asked for.
                .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public byte[] export(RunEvidence evidence) {
        try {
            return mapper.writer(printer())
                    .writeValueAsString(EvidenceEnvelope.from(evidence))
                    .getBytes(StandardCharsets.UTF_8);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new EvidenceWriteException(
                    "Vortex could not serialise the evidence for run "
                            + evidence.identity().executionId().value() + ".", e);
        }
    }

    /**
     * Two-space indentation with Unix line endings, pinned explicitly.
     *
     * <p>Jackson's default pretty printer uses the platform line separator, which would make the
     * same run export differently on Windows and turn a byte comparison in CI into a false alarm.
     */
    private DefaultPrettyPrinter printer() {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }
}
