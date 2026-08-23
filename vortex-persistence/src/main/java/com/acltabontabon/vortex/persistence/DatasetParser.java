package com.acltabontabon.vortex.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.acltabontabon.vortex.core.data.DatasetException;
import com.acltabontabon.vortex.core.data.DatasetFormat;
import com.acltabontabon.vortex.core.data.DatasetProblem;
import com.acltabontabon.vortex.core.data.DatasetRecords;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Reads a dataset file into rows.
 *
 * <p>In the adapter rather than the domain, and using a real CSV library rather than a hand-written
 * one. CSV looks like a comma-separated line until it meets a quoted field containing a comma, a
 * quoted field containing a newline, an escaped quote, a byte-order mark, or a trailing blank line —
 * and a parser that gets any of those wrong corrupts somebody's test data quietly, which is the
 * worst way for it to be wrong. Commons CSV has met all of them.
 *
 * <h2>Types survive where the format has them</h2>
 *
 * <p>JSON carries types, so {@code "amount": 42.5} stays a number and binds into a JSON body as one.
 * A body that sent {@code "42.5"} instead is a different request against a service that validates
 * its input. CSV has no types and yields strings; that is a fact about CSV, stated rather than
 * papered over with guesswork about which columns look numeric.
 *
 * <h2>What is not checked here</h2>
 *
 * <p>Cell contents are read faithfully. Whether a value is <em>legal</em> depends on where it is
 * bound — a newline is a request-splitting vector in a header and a paragraph in a body field — so
 * that check belongs at preflight, where the binding is known. Rejecting multi-line text at
 * ingestion would corrupt legitimate data to defend a position it may never reach.
 */
public final class DatasetParser {

    /** Bounded so a mis-selected file cannot exhaust memory before it is rejected. */
    public static final int MAX_BYTES = 64 * 1024 * 1024;

    /** Bounded for the same reason, and because a selector of 5,000 columns helps nobody. */
    public static final int MAX_FIELDS = 512;

    private final ObjectMapper json;

    public DatasetParser(ObjectMapper json) {
        this.json = json;
    }

    public DatasetRecords parse(DatasetFormat format, byte[] content) {
        byte[] bytes = content == null ? new byte[0] : content;
        if (bytes.length > MAX_BYTES) {
            throw problem("the dataset", "is " + (bytes.length / (1024 * 1024))
                    + " MB, and Vortex reads datasets up to " + (MAX_BYTES / (1024 * 1024)) + " MB.",
                    "A performance test needs realistic data, not all of it — a few thousand rows "
                            + "exercise the same code paths.");
        }
        String text = stripByteOrderMark(new String(bytes, StandardCharsets.UTF_8));
        if (text.isBlank()) {
            throw problem("the dataset", "is empty.",
                    "A dataset with no rows would leave every value it supplies undefined.");
        }
        return switch (format) {
            case CSV -> parseCsv(text);
            case JSON -> parseJson(text);
        };
    }

    private DatasetRecords parseCsv(String text) {
        // Missing and duplicate column names are allowed *through the parser* so that Vortex can
        // reject them itself, in its own words. Commons CSV's own message names the library's
        // internal representation, and a user reading it learns less than they would from being
        // told which column is unnamed and what to do about it.
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setAllowMissingColumnNames(true)
                .setDuplicateHeaderMode(org.apache.commons.csv.DuplicateHeaderMode.ALLOW_ALL)
                .setTrim(false)
                .get();

        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {

            List<String> fields = new ArrayList<>(parser.getHeaderNames());
            rejectUnusableHeader(fields);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (String field : fields) {
                    // A short row leaves later columns absent rather than throwing: a trailing
                    // comma somebody forgot should not cost them the whole file.
                    row.put(field, record.isSet(field) ? record.get(field) : null);
                }
                rows.add(row);
            }
            return new DatasetRecords(fields, rows);

        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            throw problem("the dataset", "could not be read as CSV: " + e.getMessage(),
                    "Check that the first line names the columns and that quoted fields are closed.");
        }
    }

    private DatasetRecords parseJson(String text) {
        JsonNode root;
        try {
            root = json.readTree(text);
        } catch (IOException e) {
            throw problem("the dataset", "could not be read as JSON: " + e.getMessage(), "");
        }
        if (!root.isArray()) {
            throw problem("the dataset", "is JSON, but not a list of records.",
                    "Vortex reads a JSON array of objects: [{\"customerId\": \"C001\"}, …]. Each "
                            + "object is one row, and its property names are the fields.");
        }

        Set<String> fields = new LinkedHashSet<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        int index = 0;
        for (JsonNode element : root) {
            index++;
            if (!element.isObject()) {
                throw problem("record " + index, "is not an object.",
                        "Every record must be an object whose properties are the fields, so that a "
                                + "request value can name the one it needs.");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> property : element.properties()) {
                fields.add(property.getKey());
                row.put(property.getKey(), scalarOf(property.getValue(), property.getKey(), index));
            }
            rows.add(row);
        }
        if (rows.isEmpty()) {
            throw problem("the dataset", "is an empty list.",
                    "A dataset with no rows would leave every value it supplies undefined.");
        }
        List<String> fieldNames = new ArrayList<>(fields);
        rejectUnusableHeader(fieldNames);

        // Absent properties become explicit nulls, so every row answers for every field and a
        // request never silently sends nothing because one record was written differently.
        for (Map<String, Object> row : rows) {
            for (String field : fieldNames) {
                row.putIfAbsent(field, null);
            }
        }
        return new DatasetRecords(fieldNames, rows);
    }

    /**
     * One cell.
     *
     * <p>Nested objects and arrays are refused rather than flattened or stringified. A request value
     * binds one field to one value, and a rule for turning {@code {"a":{"b":1}}} into a scalar would
     * be Vortex inventing a meaning its own configuration grammar cannot express.
     */
    private Object scalarOf(JsonNode value, String field, int record) {
        if (value.isObject() || value.isArray()) {
            throw problem("record " + record + ", field '" + field + "'",
                    "holds " + (value.isArray() ? "a list" : "a nested object") + ".",
                    "Dataset fields are single values. Flatten the field, or bind the whole document "
                            + "as the request body instead of mapping fields into it.");
        }
        if (value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isIntegralNumber()) {
            return value.longValue();
        }
        if (value.isNumber()) {
            return value.doubleValue();
        }
        return value.textValue();
    }

    private void rejectUnusableHeader(List<String> fields) {
        if (fields.isEmpty()) {
            throw problem("the dataset", "declares no fields.",
                    "The first line of a CSV names its columns; a JSON record's properties name its "
                            + "fields.");
        }
        if (fields.size() > MAX_FIELDS) {
            throw problem("the dataset", "declares " + fields.size() + " fields, and Vortex reads up "
                    + "to " + MAX_FIELDS + ".", "");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String field : fields) {
            if (field == null || field.isBlank()) {
                throw problem("the dataset", "has a column with no name.",
                        "Every column needs a name, because that is how a request value says which "
                                + "one it reads.");
            }
            if (!seen.add(field)) {
                throw problem("column '" + field + "'", "appears more than once.",
                        "Vortex reads fields by name, so two columns sharing one name have no "
                                + "unambiguous meaning. Rename one.");
            }
        }
    }

    /** A UTF-8 BOM would otherwise become part of the first column's name, invisibly. */
    private String stripByteOrderMark(String text) {
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    private DatasetException problem(String location, String message, String remedy) {
        DatasetProblem problem = new DatasetProblem(location, message, remedy);
        return new DatasetException(problem.describe(), List.of(problem));
    }
}
