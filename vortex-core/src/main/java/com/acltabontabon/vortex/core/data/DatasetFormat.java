package com.acltabontabon.vortex.core.data;

import java.util.Locale;

/** The structured formats Vortex reads a dataset from. */
public enum DatasetFormat {

    /** RFC 4180 comma-separated values, with a header row naming the fields. */
    CSV("csv", "text/csv"),

    /** A JSON array of flat objects, whose property names are the fields. */
    JSON("json", "application/json");

    private final String key;
    private final String mediaType;

    DatasetFormat(String key, String mediaType) {
        this.key = key;
        this.mediaType = mediaType;
    }

    public String key() {
        return key;
    }

    public String mediaType() {
        return mediaType;
    }

    /** The file extension a staged copy of this dataset carries. */
    public String extension() {
        return key;
    }

    public static DatasetFormat fromKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a dataset must state its format: csv or json");
        }
        String normalised = value.trim().toLowerCase(Locale.ROOT);
        if (normalised.startsWith(".")) {
            normalised = normalised.substring(1);
        }
        for (DatasetFormat format : values()) {
            if (format.key.equals(normalised)) {
                return format;
            }
        }
        throw new IllegalArgumentException(
                "unsupported dataset format '" + value + "'. Vortex reads CSV and JSON.");
    }

    /** Best-effort format for a filename, or {@code null} when the extension says nothing. */
    public static DatasetFormat forFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        try {
            return fromKey(fileName.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
