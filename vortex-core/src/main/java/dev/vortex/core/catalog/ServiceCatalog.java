package dev.vortex.core.catalog;

import dev.vortex.core.shared.OperationId;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * The inventory of operations Vortex knows about for a service.
 *
 * <p>A catalog is evidence of what an API description declared at a point in time, not a live view
 * of the service. It records where it came from and when, so a stale import is visible rather than
 * silently trusted.
 *
 * @param source     how the catalog was produced
 * @param sourceRef  the file path or URL it came from, for display and re-import
 * @param title      the service title declared by the specification
 * @param version    the service version declared by the specification
 * @param importedAt when the import ran
 * @param operations discovered operations, in specification order
 * @param warnings   non-fatal problems encountered while parsing, shown to the user
 */
public record ServiceCatalog(
        CatalogSource source,
        String sourceRef,
        String title,
        String version,
        Instant importedAt,
        List<Operation> operations,
        List<String> warnings) {

    public ServiceCatalog {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(importedAt, "importedAt");
        operations = operations == null ? List.of() : List.copyOf(operations);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        title = title == null ? "" : title;
        version = version == null ? "" : version;
        sourceRef = sourceRef == null ? "" : sourceRef;
    }

    public static ServiceCatalog empty() {
        return new ServiceCatalog(CatalogSource.MANUAL, "", "", "", Instant.EPOCH, List.of(), List.of());
    }

    public boolean isEmpty() {
        return operations.isEmpty();
    }

    public int operationCount() {
        return operations.size();
    }

    public Optional<Operation> find(OperationId id) {
        return operations.stream().filter(o -> o.id().equals(id)).findFirst();
    }

    public List<Operation> readOperations() {
        return operations.stream().filter(o -> o.kind() == OperationKind.READ).toList();
    }

    public List<Operation> mutatingOperations() {
        return operations.stream().filter(o -> o.kind() == OperationKind.MUTATION).toList();
    }

    /** Operations grouped by their primary tag, preserving specification order within each group. */
    public SequencedMap<String, List<Operation>> groupedByTag() {
        SequencedMap<String, List<Operation>> grouped = new LinkedHashMap<>();
        for (Operation operation : operations) {
            grouped.computeIfAbsent(operation.primaryTag(), _ -> new java.util.ArrayList<>())
                    .add(operation);
        }
        SequencedMap<String, List<Operation>> immutable = new LinkedHashMap<>();
        for (Map.Entry<String, List<Operation>> entry : grouped.entrySet()) {
            immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return immutable;
    }

    /** Operations in the order the specification declared them, filtered to those in {@code ids}. */
    public List<Operation> select(java.util.Collection<OperationId> ids) {
        return operations.stream().filter(o -> ids.contains(o.id())).toList();
    }

    public ServiceCatalog withOperation(Operation replacement) {
        List<Operation> updated = operations.stream()
                .map(o -> o.id().equals(replacement.id()) ? replacement : o)
                .toList();
        return new ServiceCatalog(source, sourceRef, title, version, importedAt, updated, warnings);
    }
}
