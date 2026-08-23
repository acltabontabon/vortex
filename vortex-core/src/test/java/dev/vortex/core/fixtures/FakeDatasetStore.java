package dev.vortex.core.fixtures;

import dev.vortex.core.data.Dataset;
import dev.vortex.core.data.DatasetException;
import dev.vortex.core.data.DatasetFormat;
import dev.vortex.core.data.DatasetHome;
import dev.vortex.core.data.DatasetProblem;
import dev.vortex.core.data.DatasetRecords;
import dev.vortex.core.data.DatasetRef;
import dev.vortex.core.data.DatasetScope;
import dev.vortex.core.port.DatasetStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Datasets in memory, for tests that need one to exist — or to be missing.
 *
 * <p>Absence is the interesting case and is easy to reach here: a store with nothing in it makes
 * every dataset reference unresolvable, which is what a fresh checkout of somebody else's service
 * actually looks like.
 */
public final class FakeDatasetStore implements DatasetStore {

    private final Map<DatasetRef, DatasetRecords> stored = new LinkedHashMap<>();

    /** A dataset with the given fields and rows, held on this machine. */
    public FakeDatasetStore with(String name, List<String> fields, List<Map<String, Object>> rows) {
        stored.put(DatasetRef.local(name), new DatasetRecords(fields, rows));
        return this;
    }

    /** A dataset committed with the service. */
    public FakeDatasetStore withPortable(String name, List<String> fields,
            List<Map<String, Object>> rows) {
        stored.put(DatasetRef.portable(name), new DatasetRecords(fields, rows));
        return this;
    }

    @Override
    public Dataset store(DatasetHome home, DatasetScope scope, String name, DatasetFormat format,
            byte[] content) {
        throw new UnsupportedOperationException("this fake is populated directly, not by upload");
    }

    @Override
    public List<Dataset> list(DatasetHome home) {
        List<Dataset> datasets = new ArrayList<>();
        stored.forEach((ref, records) -> datasets.add(describe(ref, records)));
        return datasets;
    }

    @Override
    public Optional<Dataset> find(DatasetHome home, DatasetRef ref) {
        return Optional.ofNullable(stored.get(ref)).map(records -> describe(ref, records));
    }

    @Override
    public DatasetRecords read(DatasetHome home, DatasetRef ref) {
        DatasetRecords records = stored.get(ref);
        if (records == null) {
            throw new DatasetException("no dataset '" + ref.name() + "'",
                    List.of(new DatasetProblem(ref.name(), "was not found.", "")));
        }
        return records;
    }

    @Override
    public String stagedJson(DatasetHome home, DatasetRef ref) {
        // Enough to be a plausible artifact. Faithful JSON is the adapter's job, and is tested there.
        return read(home, ref).rows().toString();
    }

    @Override
    public Dataset promote(DatasetHome home, DatasetRef ref) {
        DatasetRecords records = read(home, ref);
        DatasetRef portable = DatasetRef.portable(ref.name());
        stored.put(portable, records);
        return describe(portable, records);
    }

    @Override
    public String promotionTarget(DatasetHome home, DatasetRef ref) {
        return home.workspacePath() + "/.vortex/datasets/" + ref.name() + ".csv";
    }

    @Override
    public void delete(DatasetHome home, DatasetRef ref) {
        stored.remove(ref);
    }

    private Dataset describe(DatasetRef ref, DatasetRecords records) {
        return new Dataset(ref, DatasetFormat.CSV, records.fields(), records.recordCount(),
                "hash-" + ref.name(), Instant.EPOCH, "in memory");
    }
}
