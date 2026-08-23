package com.acltabontabon.vortex.core.plan;

import com.acltabontabon.vortex.core.data.DatasetFormat;
import com.acltabontabon.vortex.core.data.DatasetRef;
import java.util.List;
import java.util.Objects;

/**
 * A dataset as it was at the moment a run started.
 *
 * <p>The plan is a snapshot: it copies what it needs rather than referencing configuration that may
 * change underneath it, so a report from six months ago describes the test that actually ran. A
 * dataset is the sharpest case of that. The rows are not copied into the plan — they are somebody's
 * customer list, and keeping a copy in every execution directory forever is not a service to
 * anybody — but what the run needs to be explicable is.
 *
 * <p>{@link #contentHash} is what makes "the data changed" a fact rather than a suspicion. Two runs
 * of the same workload against the same service, one before and one after the CSV was edited, are
 * different experiments, and the hash is what lets Vortex say so instead of reporting a regression.
 *
 * @param ref         name and scope, as the configuration named it
 * @param format      how the source file was written
 * @param stagedFile  the filename Vortex writes beside the generated script, normalised to JSON
 * @param fields      the field names the dataset actually had at resolution time
 * @param recordCount how many rows it had
 * @param contentHash SHA-256 of the source bytes, hex
 */
public record PlannedDataset(
        DatasetRef ref,
        DatasetFormat format,
        String stagedFile,
        List<String> fields,
        int recordCount,
        String contentHash) {

    /** Staged copies are prefixed so they are obviously Vortex's doing in an artifact listing. */
    public static final String STAGED_PREFIX = "dataset-";

    public PlannedDataset {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(format, "format");
        fields = fields == null ? List.of() : List.copyOf(fields);
        contentHash = contentHash == null ? "" : contentHash;
        if (stagedFile == null || stagedFile.isBlank()) {
            throw new IllegalArgumentException(
                    "dataset " + ref.name() + " has no staged filename. The load generator reads a "
                            + "copy Vortex writes beside the script, so the name is not optional.");
        }
        if (recordCount < 0) {
            throw new IllegalArgumentException("a dataset cannot have " + recordCount + " records");
        }
    }

    /** The filename a dataset is staged under, e.g. {@code dataset-customers.json}. */
    public static String stagedFileNameFor(DatasetRef ref) {
        return STAGED_PREFIX + ref.name() + ".json";
    }

    public String name() {
        return ref.name();
    }

    public boolean hasField(String field) {
        return fields.contains(field);
    }

    public boolean isEmpty() {
        return recordCount == 0;
    }

    public String describeFields() {
        return String.join(", ", fields);
    }

    /**
     * The JavaScript identifier the generated script binds this dataset to.
     *
     * <p>Prefixed rather than bare so a dataset called {@code params} or {@code http} cannot shadow
     * something the script already depends on.
     */
    public String scriptBinding() {
        return "dataset_" + ref.name().replaceAll("[^A-Za-z0-9_]", "_");
    }
}
