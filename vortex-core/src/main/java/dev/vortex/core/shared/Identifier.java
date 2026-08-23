package dev.vortex.core.shared;

/**
 * Marker for the small set of opaque, stable identifiers used across the Vortex domain.
 *
 * <p>Identifiers are deliberately opaque strings rather than numbers: they are generated locally,
 * appear in filesystem paths and URLs, and must remain stable across export/import of a project.
 */
public interface Identifier {

    String value();
}
