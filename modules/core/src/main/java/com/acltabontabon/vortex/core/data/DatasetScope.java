package com.acltabontabon.vortex.core.data;

/**
 * Where a dataset lives, and therefore who can run the test that uses it.
 *
 * <p>Vortex's configuration is portable on purpose: {@code vortex.yaml} is committed beside the
 * service it describes so a performance definition can be reviewed in a pull request and run from a
 * pipeline. A dataset creates a genuine tension with that, because test data is frequently the one
 * part of a configuration that must <em>not</em> be committed.
 *
 * <p>So Vortex asks, rather than guessing, and defaults to the safe answer.
 */
public enum DatasetScope {

    /**
     * Kept in the local workspace, on this machine only. The default.
     *
     * <p>The configuration records that the dataset is expected and where its fields are used, but
     * not its contents. Another machine reading the same {@code vortex.yaml} is told plainly that
     * the dataset is local to whoever configured it — the same way Vortex already reports an
     * unresolvable API description rather than inventing one.
     */
    LOCAL("local", "on this machine only"),

    /**
     * Committed with the service, beside {@code vortex.yaml}.
     *
     * <p>Reproducible anywhere the repository is, which is what a pipeline needs. Only ever chosen
     * explicitly: Vortex never writes a file into somebody's repository as a side effect of an
     * upload, because test data that turns out to be real customer data is not a mistake anyone
     * should be able to make by dragging a file into a browser.
     */
    PORTABLE("portable", "committed with the service");

    private final String key;
    private final String meaning;

    DatasetScope(String key, String meaning) {
        this.key = key;
        this.meaning = meaning;
    }

    /** The token used in {@code vortex.yaml}. */
    public String key() {
        return key;
    }

    public String meaning() {
        return meaning;
    }

    public static DatasetScope fromKey(String value) {
        if (value == null || value.isBlank()) {
            return LOCAL;
        }
        String normalised = value.trim().toLowerCase(java.util.Locale.ROOT);
        for (DatasetScope scope : values()) {
            if (scope.key.equals(normalised)) {
                return scope;
            }
        }
        throw new IllegalArgumentException(
                "unknown dataset scope '" + value + "'. Use 'local' (this machine only) or "
                        + "'portable' (committed with the service).");
    }
}
