package dev.vortex.core.data;

import java.util.Locale;
import java.util.Objects;

/**
 * A dataset by name and scope.
 *
 * <p>The domain refers to datasets this way and never by path. Where a dataset actually sits is a
 * property of the store that holds it, and Vortex should be able to grow a real dataset catalog
 * later without every record in the domain having a filesystem path baked into it.
 *
 * @param name  the dataset's name, unique within a service
 * @param scope where it lives
 */
public record DatasetRef(String name, DatasetScope scope) {

    /** Bounded so a name stays legible in a selector and safe as a filename component. */
    public static final int MAX_NAME_LENGTH = 64;

    public DatasetRef {
        Objects.requireNonNull(scope, "scope");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a dataset must have a name");
        }
        name = name.trim();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "a dataset name may be at most " + MAX_NAME_LENGTH + " characters, but '" + name
                            + "' is " + name.length() + ".");
        }
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException(
                    "a dataset name may contain letters, digits, hyphens and underscores, and must "
                            + "start with a letter or digit, but was '" + name + "'. Vortex uses the "
                            + "name as a filename when it stages the dataset for the load generator.");
        }
    }

    public static DatasetRef local(String name) {
        return new DatasetRef(name, DatasetScope.LOCAL);
    }

    public static DatasetRef portable(String name) {
        return new DatasetRef(name, DatasetScope.PORTABLE);
    }

    /** Derives a dataset name from a filename, e.g. {@code customers.csv} to {@code customers}. */
    public static String nameFromFileName(String fileName) {
        String base = fileName == null ? "" : fileName.trim();
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.replaceAll("[^A-Za-z0-9_-]", "-").replaceAll("^-+", "");
        if (base.length() > MAX_NAME_LENGTH) {
            base = base.substring(0, MAX_NAME_LENGTH);
        }
        return base.toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return name;
    }
}
