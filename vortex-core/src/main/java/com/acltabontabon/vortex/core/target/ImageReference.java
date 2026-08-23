package com.acltabontabon.vortex.core.target;

/**
 * Opaque — Docker's own reference grammar is Docker's to interpret, not Vortex's. No length cap: a
 * syntactically invalid reference is Docker's to reject at {@code prepare()} time, not Vortex's to
 * pre-validate.
 */
public record ImageReference(String value) {

    public ImageReference {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("image reference must not be blank");
        }
        value = value.trim();
    }
}
