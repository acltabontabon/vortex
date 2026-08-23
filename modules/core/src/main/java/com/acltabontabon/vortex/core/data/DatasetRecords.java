package com.acltabontabon.vortex.core.data;

import java.util.List;
import java.util.Map;

/**
 * The contents of a dataset, parsed.
 *
 * <p>Cell values are JDK types — {@code String}, {@code Long}, {@code Double}, {@code Boolean} or
 * {@code null} — rather than everything flattened to text. A JSON dataset declaring {@code "amount":
 * 42.5} binds a number into a JSON body field, and a body that sent {@code "42.5"} instead would be
 * a different request against a service that validates its input. CSV has no types and always yields
 * strings, which is a fact about CSV and is stated rather than papered over.
 *
 * <p>This is the only place row data exists in the domain, and it is deliberately transient: it is
 * read to validate a configuration and to stage a copy beside the generated script, never persisted
 * into the run's evidence. What a run records about its data is the dataset's name, its size and its
 * content hash — enough to answer "which data produced this" without keeping a copy of somebody's
 * customer list in every execution directory forever.
 *
 * @param fields the field names, in the order the source declared them
 * @param rows   the rows, each keyed by field name
 */
public record DatasetRecords(List<String> fields, List<Map<String, Object>> rows) {

    public DatasetRecords {
        fields = fields == null ? List.of() : List.copyOf(fields);
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public int recordCount() {
        return rows.size();
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    public boolean hasField(String field) {
        return fields.contains(field);
    }

    /** Field names as a sentence, for "available columns: ..." in an error message. */
    public String describeFields() {
        return String.join(", ", fields);
    }
}
