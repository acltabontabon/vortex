package dev.vortex.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vortex.core.data.Dataset;
import dev.vortex.core.data.DatasetException;
import dev.vortex.core.data.DatasetFormat;
import dev.vortex.core.data.DatasetHome;
import dev.vortex.core.data.DatasetProblem;
import dev.vortex.core.data.DatasetRecords;
import dev.vortex.core.data.DatasetRef;
import dev.vortex.core.data.DatasetScope;
import dev.vortex.core.port.DatasetStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Datasets on disk, in one of two places.
 *
 * <pre>
 * ~/.vortex/datasets/&lt;project-id&gt;/customers.csv   local — this machine only
 * &lt;service&gt;/.vortex/datasets/customers.csv        portable — committed with the service
 * </pre>
 *
 * <h2>Local is the default, and portable is a decision</h2>
 *
 * <p>Vortex's configuration is portable on purpose, and a dataset is the part of it most likely to
 * be the one thing that must not be committed. So an upload lands locally, and moving it into
 * somebody's repository happens only through {@link #promote}, only when asked, and only after the
 * interface has named the file it is about to write. There is no code path that writes into a user's
 * repository as a side effect of anything else.
 *
 * <h2>Content only</h2>
 *
 * <p>Nothing here takes a path to read from. Vortex is reachable over HTTP, and a method that reads
 * an arbitrary filesystem path on request is a local-file-read primitive with a friendly name. The
 * interface reads the file the user picked, in their browser, and sends the bytes. Being a local
 * tool is a reason to be careful about that, not a licence to skip it.
 *
 * <h2>Derived facts are read, never recorded</h2>
 *
 * <p>Record counts, field names and content hashes are computed by reading the file each time they
 * are asked for. None of them is written into {@code vortex.yaml}, because a count in a
 * configuration file is accurate right up until somebody edits the CSV.
 */
public final class FilesystemDatasetStore implements DatasetStore {

    /** The directory datasets live in, under the workspace root and under a service's own. */
    public static final String DIRECTORY = "datasets";

    private final VortexWorkspace workspace;
    private final DatasetParser parser;
    private final ObjectMapper json;

    public FilesystemDatasetStore(VortexWorkspace workspace, ObjectMapper json) {
        this.workspace = workspace;
        this.json = json;
        this.parser = new DatasetParser(json);
    }

    @Override
    public Dataset store(DatasetHome home, DatasetScope scope, String name, DatasetFormat format,
            byte[] content) {

        DatasetRef ref = new DatasetRef(name, scope);
        // Parsed before it is written. A file Vortex cannot read is not a dataset somebody has to
        // discover is broken at preflight; it is an upload that failed, with the reason attached.
        DatasetRecords records = parser.parse(format, content);

        Path target = fileFor(home, ref, format);
        try {
            Files.createDirectories(target.getParent());
            writeAtomically(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Vortex could not write the dataset '" + name + "' to " + target
                            + ". Check that the directory is writable.", e);
        }
        // Replacing a dataset with one of a different format would otherwise leave both files, and
        // a later read would find whichever the lookup happened to try first.
        deleteOtherFormats(home, ref, format);

        return describe(ref, format, target, records, content);
    }

    @Override
    public List<Dataset> list(DatasetHome home) {
        List<Dataset> datasets = new ArrayList<>();
        for (DatasetScope scope : DatasetScope.values()) {
            Path directory = directoryFor(home, scope);
            if (directory == null || !Files.isDirectory(directory)) {
                continue;
            }
            try (var entries = Files.list(directory)) {
                entries.filter(Files::isRegularFile).forEach(file -> {
                    DatasetFormat format = DatasetFormat.forFileName(file.getFileName().toString());
                    if (format == null) {
                        return;
                    }
                    String name = DatasetRef.nameFromFileName(file.getFileName().toString());
                    try {
                        datasets.add(describeFile(new DatasetRef(name, scope), format, file));
                    } catch (IllegalArgumentException | DatasetException e) {
                        // A file that no longer parses still exists, and the interface has to be
                        // able to say so — but a listing is not the place to fail. It is reported
                        // as a dataset with no fields and no records, and read() explains why.
                        datasets.add(new Dataset(new DatasetRef(name, scope), format, List.of(), 0,
                                "", modifiedAt(file), display(file)));
                    }
                });
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Vortex could not list the datasets in " + directory + ".", e);
            }
        }
        datasets.sort(Comparator.comparing(Dataset::name));
        return datasets;
    }

    @Override
    public Optional<Dataset> find(DatasetHome home, DatasetRef ref) {
        return locate(home, ref).map(found -> describeFile(ref, found.format(), found.file()));
    }

    @Override
    public DatasetRecords read(DatasetHome home, DatasetRef ref) {
        Located found = locate(home, ref).orElseThrow(() -> missing(home, ref));
        return parser.parse(found.format(), bytesOf(found.file()));
    }

    @Override
    public String stagedJson(DatasetHome home, DatasetRef ref) {
        DatasetRecords records = read(home, ref);
        try {
            return json.writeValueAsString(records.rows());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new DatasetException(
                    "Vortex could not prepare the dataset '" + ref.name() + "' for the load "
                            + "generator.", e);
        }
    }

    @Override
    public Dataset promote(DatasetHome home, DatasetRef ref) {
        if (ref.scope() == DatasetScope.PORTABLE) {
            return find(home, ref).orElseThrow(() -> missing(home, ref));
        }
        if (!home.hasWorkspace()) {
            throw new DatasetException(
                    "This service has no directory of its own, so there is nowhere to commit '"
                            + ref.name() + "' to.",
                    List.of(new DatasetProblem(ref.name(),
                            "cannot be made portable: the service has no workspace directory.",
                            "Set the service's directory to its repository, then try again.")));
        }
        Located found = locate(home, ref).orElseThrow(() -> missing(home, ref));
        byte[] content = bytesOf(found.file());
        return store(home, DatasetScope.PORTABLE, ref.name(), found.format(), content);
    }

    @Override
    public String promotionTarget(DatasetHome home, DatasetRef ref) {
        if (!home.hasWorkspace()) {
            return "";
        }
        DatasetFormat format = locate(home, ref).map(Located::format).orElse(DatasetFormat.CSV);
        return fileFor(home, new DatasetRef(ref.name(), DatasetScope.PORTABLE), format).toString();
    }

    @Override
    public void delete(DatasetHome home, DatasetRef ref) {
        locate(home, ref).ifPresent(found -> {
            try {
                Files.deleteIfExists(found.file());
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "Vortex could not delete the dataset '" + ref.name() + "' at "
                                + found.file() + ".", e);
            }
        });
    }

    /**
     * Writes to a sibling temporary file, then renames it into place, so a crash mid-write leaves
     * either the old dataset intact or the new one complete — never a truncated file with no signal
     * that it never finished.
     */
    private static void writeAtomically(Path target, byte[] content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.write(tmp, content);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    // ---------------------------------------------------------------- locating

    private record Located(DatasetFormat format, Path file) {
    }

    /**
     * The stored file for a reference.
     *
     * <p>Scope is not searched: a reference names exactly one place, so a local dataset is never
     * quietly answered by a portable one of the same name. Format is, because a reference does not
     * carry one — the file on disk is the authority on how it is written.
     */
    private Optional<Located> locate(DatasetHome home, DatasetRef ref) {
        for (DatasetFormat format : DatasetFormat.values()) {
            Path candidate = fileFor(home, ref, format);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(new Located(format, candidate));
            }
        }
        return Optional.empty();
    }

    private Path fileFor(DatasetHome home, DatasetRef ref, DatasetFormat format) {
        Path directory = directoryFor(home, ref.scope());
        if (directory == null) {
            throw new DatasetException(
                    "This service has no directory of its own, so it cannot hold a portable dataset.",
                    List.of(new DatasetProblem(ref.name(),
                            "is portable, but the service has no workspace directory.",
                            "Set the service's directory, or keep the dataset on this machine.")));
        }
        // DatasetRef's constructor already restricts a name to letters, digits, hyphens and
        // underscores, so a name cannot traverse. Resolving and re-checking anyway, because the
        // cost is one comparison and the failure mode is somebody's private key.
        Path resolved = directory.resolve(ref.name() + "." + format.extension())
                .toAbsolutePath().normalize();
        if (!resolved.startsWith(directory.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException(
                    "dataset name '" + ref.name() + "' resolves outside the dataset directory");
        }
        return resolved;
    }

    private Path directoryFor(DatasetHome home, DatasetScope scope) {
        return switch (scope) {
            case LOCAL -> workspace.root().resolve(DIRECTORY).resolve(home.project().value());
            case PORTABLE -> home.hasWorkspace()
                    ? Path.of(home.workspacePath()).resolve(VortexWorkspace.DEFAULT_DIRECTORY_NAME)
                            .resolve(DIRECTORY)
                    : null;
        };
    }

    private void deleteOtherFormats(DatasetHome home, DatasetRef ref, DatasetFormat kept) {
        for (DatasetFormat format : DatasetFormat.values()) {
            if (format == kept) {
                continue;
            }
            try {
                Files.deleteIfExists(fileFor(home, ref, format));
            } catch (IOException e) {
                throw new UncheckedIOException("Vortex could not replace the dataset '"
                        + ref.name() + "'.", e);
            }
        }
    }

    // ---------------------------------------------------------------- describing

    private Dataset describeFile(DatasetRef ref, DatasetFormat format, Path file) {
        byte[] content = bytesOf(file);
        return describe(ref, format, file, parser.parse(format, content), content);
    }

    private Dataset describe(DatasetRef ref, DatasetFormat format, Path file,
            DatasetRecords records, byte[] content) {
        return new Dataset(ref, format, records.fields(), records.recordCount(),
                hashOf(content), modifiedAt(file), display(file));
    }

    private byte[] bytesOf(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Vortex could not read the dataset at " + file + ".", e);
        }
    }

    private Instant modifiedAt(Path file) {
        try {
            return Files.getLastModifiedTime(file).toInstant();
        } catch (IOException e) {
            return Instant.EPOCH;
        }
    }

    /**
     * Where the file is, for display.
     *
     * <p>A portable dataset shows its path relative to the service, because that is what somebody
     * commits. A local one shows its absolute path, because that is where they would go to find it.
     */
    private String display(Path file) {
        return file.toString();
    }

    private String hashOf(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    private DatasetException missing(DatasetHome home, DatasetRef ref) {
        String where = ref.scope() == DatasetScope.LOCAL
                ? "on this machine"
                : "in this service's own directory";
        return new DatasetException(
                "Dataset '" + ref.name() + "' was not found " + where + ".",
                List.of(new DatasetProblem(ref.name(), "was not found " + where + ".",
                        ref.scope() == DatasetScope.PORTABLE
                                ? "This configuration expects the dataset to be committed with the "
                                        + "service. Add it to the repository, or change the value to "
                                        + "use a dataset held on this machine."
                                : "This dataset is local to the machine that configured it. Add it "
                                        + "here, or make it portable so it travels with the service.")));
    }
}
