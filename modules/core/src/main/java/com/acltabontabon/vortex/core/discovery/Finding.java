package com.acltabontabon.vortex.core.discovery;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One piece of evidence a {@code ProjectDetector} found in a project's own files.
 *
 * <p>Evidence and confidence always travel together, the same discipline {@code
 * ObservabilityProvider} applies to observations and gaps: a reader should never have to take a
 * finding on faith. {@code evidence} strings are already sanitized before they reach this record —
 * see {@code docs/04-reference/project-discovery.adoc}, "Secret handling."
 *
 * @param kind        what was found
 * @param sourceFile  the project file this evidence came from, relative to the project root
 * @param evidence    human-readable evidence lines, already safe to display
 * @param confidence  how certain this finding is
 * @param attributes  structured facts a proposal builder can act on (e.g. a resolved port), never
 *                    shown verbatim as evidence unless a detector also added a matching evidence line
 */
public record Finding(FindingKind kind, String sourceFile, List<String> evidence,
        Confidence confidence, Map<String, String> attributes) {

    public Finding {
        Objects.requireNonNull(kind, "kind");
        sourceFile = sourceFile == null ? "" : sourceFile.trim();
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        Objects.requireNonNull(confidence, "confidence");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** An attribute value, or blank when absent — callers never branch on {@code null}. */
    public String attribute(String key) {
        return attributes.getOrDefault(key, "");
    }
}
