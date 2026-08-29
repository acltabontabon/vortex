package com.acltabontabon.vortex.core.port;

import com.acltabontabon.vortex.core.discovery.Finding;
import com.acltabontabon.vortex.core.discovery.ProjectSnapshot;
import java.util.List;

/**
 * Reads whatever part of a {@link ProjectSnapshot} this detector understands and reports what it
 * found, with evidence.
 *
 * <p>A detector is read-only by contract: it never touches the filesystem (the snapshot already did
 * that), never writes configuration, and never throws for "nothing here" — an empty list is a
 * normal, common result. Detection is deterministic parsing, the same discipline {@code
 * ServiceCatalogImporter} applies to an OpenAPI document — there is nothing here for a language
 * model to decide that structured parsing cannot decide better and faster.
 */
public interface ProjectDetector {

    /** A short name for this detector, used in partial-failure messages. */
    String name();

    /** Findings this detector could produce from the snapshot. Never {@code null}; empty when
     *  nothing matched. A detector that cannot finish should throw rather than guess — {@code
     *  ProjectDiscoveryService} turns that into a partial failure without failing the whole scan. */
    List<Finding> detect(ProjectSnapshot snapshot);
}
