package com.acltabontabon.vortex.core.data;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What Vortex knows about a stored dataset, without its contents.
 *
 * <p>Every field here except the reference is <em>derived</em>: the store computes it by reading the
 * file it holds. None of it is written into {@code vortex.yaml}, and that is deliberate. A record
 * count copied into a configuration file is correct until somebody edits the CSV, after which it is
 * a confident lie — and a configuration full of derived facts is a configuration that has to be kept
 * in sync by hand. {@code vortex.yaml} declares that a dataset named {@code customers} is expected,
 * in which scope, in which format. Everything else is answered by looking.
 *
 * <p>{@link #contentHash} is the exception that earns its place in a <em>run</em>: it is recorded on
 * the effective plan so two runs can be told apart when the data changed underneath them. It is a
 * hash, not the data, and it lives on the execution rather than in the configuration.
 *
 * @param ref          name and scope
 * @param format       how the file is written
 * @param fields       the field names, in source order
 * @param recordCount  how many rows it holds
 * @param contentHash  SHA-256 of the stored bytes, hex
 * @param importedAt   when Vortex last read it in
 * @param location     where it sits, for display only — never parsed, never used to resolve it
 */
public record Dataset(
        DatasetRef ref,
        DatasetFormat format,
        List<String> fields,
        int recordCount,
        String contentHash,
        Instant importedAt,
        String location) {

    public Dataset {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(format, "format");
        fields = fields == null ? List.of() : List.copyOf(fields);
        contentHash = contentHash == null ? "" : contentHash;
        location = location == null ? "" : location;
        if (recordCount < 0) {
            throw new IllegalArgumentException("a dataset cannot have " + recordCount + " records");
        }
    }

    public String name() {
        return ref.name();
    }

    public DatasetScope scope() {
        return ref.scope();
    }

    public boolean hasField(String field) {
        return fields.contains(field);
    }

    public boolean isEmpty() {
        return recordCount == 0;
    }

    /** Field names as a sentence, for "available columns: ..." in an error message. */
    public String describeFields() {
        return String.join(", ", fields);
    }

    /** Short content hash for display, e.g. {@code a1b2c3d4}. */
    public String shortHash() {
        return contentHash.length() <= 8 ? contentHash : contentHash.substring(0, 8);
    }

    /** How this dataset is named where a run's conditions are listed. */
    public String describe() {
        return ref.name() + " (" + recordCount + " record" + (recordCount == 1 ? "" : "s") + ", "
                + format.key() + ", " + ref.scope().meaning() + ")";
    }
}
