package dev.vortex.core.port;

import dev.vortex.core.data.Dataset;
import dev.vortex.core.data.DatasetFormat;
import dev.vortex.core.data.DatasetHome;
import dev.vortex.core.data.DatasetRecords;
import dev.vortex.core.data.DatasetRef;
import dev.vortex.core.data.DatasetScope;
import java.util.List;
import java.util.Optional;

/**
 * Where a service's datasets are kept.
 *
 * <p>The domain refers to a dataset by name and scope and never by path. That is not fastidiousness:
 * it is what makes it possible to replace the filesystem behind this port later — with a real dataset
 * catalog, a shared store, a database — without a filesystem path having leaked into every record
 * that mentions data. The current implementation writes files, and nothing in the domain knows that.
 *
 * <h2>Content, not paths</h2>
 *
 * <p>{@link #store} takes bytes. There is deliberately no {@code storeFromPath(String)}, because
 * Vortex's interface is reachable over HTTP and a method that reads an arbitrary path on request is
 * a local-file-read primitive with a friendly name. The interface reads the file the user chose, in
 * their browser, and sends its contents. Vortex being a local tool is a reason to be careful about
 * this, not a reason to skip it — the loopback binding is one layer, not a licence.
 *
 * <p>Writes go the other way and are narrow: {@link #promote} is the only method that puts a file
 * into a user's repository, it goes to one directory beside their {@code vortex.yaml}, and it happens
 * only when somebody asks for it by name.
 *
 * <h2>Derived facts belong here</h2>
 *
 * <p>Record counts, field names and content hashes are computed by reading what is stored, never
 * copied into configuration. A count in a YAML file is accurate until somebody edits the CSV.
 */
public interface DatasetStore {

    /**
     * Reads, validates and stores a dataset, replacing any existing one of the same name and scope.
     *
     * @throws dev.vortex.core.data.DatasetException with per-row problems when it cannot be parsed
     */
    Dataset store(DatasetHome home, DatasetScope scope, String name, DatasetFormat format,
            byte[] content);

    /** Every dataset this service has, in both scopes. */
    List<Dataset> list(DatasetHome home);

    /** One dataset's metadata, or empty when nothing is stored under that name and scope. */
    Optional<Dataset> find(DatasetHome home, DatasetRef ref);

    /**
     * The rows.
     *
     * <p>Read on demand rather than held: rows exist to validate a configuration and to stage a copy
     * beside the generated script, and a dataset large enough to be realistic is large enough not to
     * want a second copy of it in memory for the lifetime of a page.
     *
     * @throws dev.vortex.core.data.DatasetException when it is absent or no longer parses
     */
    DatasetRecords read(DatasetHome home, DatasetRef ref);

    /**
     * The dataset as a JSON array of records, for staging beside the generated script.
     *
     * <p>Normalising here rather than in the engine is what lets the generated script stay plain
     * k6: it reads JSON with the parser every JavaScript runtime already has, so a CSV dataset costs
     * the script no parser, no dependency and no remote import — whatever format the user supplied.
     *
     * @throws dev.vortex.core.data.DatasetException when it is absent or no longer parses
     */
    String stagedJson(DatasetHome home, DatasetRef ref);

    /**
     * Copies a local dataset into the service's own directory so it can be committed.
     *
     * <p>The one operation that writes into somebody's repository, and never a side effect of
     * anything else.
     *
     * @return the same dataset, now {@link DatasetScope#PORTABLE}
     */
    Dataset promote(DatasetHome home, DatasetRef ref);

    /** Where {@link #promote} would write, so the interface can say so before it happens. */
    String promotionTarget(DatasetHome home, DatasetRef ref);

    void delete(DatasetHome home, DatasetRef ref);
}
