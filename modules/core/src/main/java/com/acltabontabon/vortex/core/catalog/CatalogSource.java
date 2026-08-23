package com.acltabontabon.vortex.core.catalog;

/** How a service catalog was produced. */
public enum CatalogSource {

    /** Parsed deterministically from an OpenAPI 3.x document. */
    OPENAPI("OpenAPI"),

    /** Entered by hand. */
    MANUAL("Manual"),

    /** Derived from an imported k6 script. */
    K6_SCRIPT("Imported k6 script");

    private final String label;

    CatalogSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
