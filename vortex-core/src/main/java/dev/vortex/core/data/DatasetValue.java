package dev.vortex.core.data;

import java.util.Objects;

/**
 * A field of the current row of a dataset.
 *
 * <p>The mapping is by name — dataset and field — not by expression. A user picks {@code customers}
 * and {@code customerId} from two selectors; they never write {@code ${dataset[__VU].customerId}},
 * because which row a request gets is Vortex's problem, and indexing arithmetic in a configuration
 * file is the kind of thing that is wrong in a way nobody notices for a month.
 *
 * <p>The reference carries its {@link DatasetScope} rather than only a name. Two datasets called
 * {@code customers} — one local to a machine, one committed with the service — are different
 * datasets, and resolving between them by an undocumented precedence rule would mean a run silently
 * using data nobody chose.
 *
 * <h2>Which row</h2>
 *
 * <p>Rows are consumed in order, without reuse, until the dataset is exhausted; then the sequence
 * wraps to the start. The row is chosen by the position of the operation's execution within its k6
 * scenario, which k6 numbers uniquely across every virtual user — so two executions in flight at the
 * same moment never read the same row, and a re-run reads the same rows in the same order. None of
 * this is configurable, which is the point: a user should not have to choose a partitioning strategy
 * to run a test.
 *
 * <p><strong>One row per operation execution.</strong> Every value drawn from a given dataset during
 * a single execution of an operation comes from the <em>same</em> row — the row is bound once and
 * then read. A customer id and the matching mobile number belong to the same customer, which is the
 * only behaviour that makes a realistic dataset worth having.
 *
 * <p>Vortex compiles one k6 scenario per operation (ADR-026), and iteration counters are per
 * scenario. A consequence, not a design goal: two operations reading the same dataset advance
 * through it on separate counters. If a future execution model puts two operations in one scenario,
 * they share an iteration and therefore share a row — which is why the semantics above are stated in
 * terms of an operation execution rather than in terms of a counter.
 *
 * @param dataset the dataset to read, by name and scope
 * @param field   the column (CSV) or property (JSON) to read from the current row
 */
public record DatasetValue(DatasetRef dataset, String field) implements RequestValue {

    public DatasetValue {
        Objects.requireNonNull(dataset, "dataset");
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException(
                    "a dataset value must name a field of " + dataset.name()
                            + ". Mapping a whole row into one request value has no meaning.");
        }
        field = field.trim();
    }

    public static DatasetValue of(DatasetRef dataset, String field) {
        return new DatasetValue(dataset, field);
    }

    /** The dataset's name, without its scope. */
    public String datasetName() {
        return dataset.name();
    }

    @Override
    public String describeSource() {
        return "dataset: " + dataset.name() + " / " + field;
    }

    @Override
    public boolean isDynamic() {
        return true;
    }
}
