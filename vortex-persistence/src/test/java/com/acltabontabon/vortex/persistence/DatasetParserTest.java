package com.acltabontabon.vortex.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.acltabontabon.vortex.core.data.DatasetException;
import com.acltabontabon.vortex.core.data.DatasetFormat;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("reading a dataset")
class DatasetParserTest {

    private final DatasetParser parser = new DatasetParser(new ObjectMapper());

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("CSV")
    class Csv {

        @Test
        @DisplayName("a header row names the fields, and every row answers for all of them")
        void headerNamesTheFields() {
            var records = parser.parse(DatasetFormat.CSV, bytes("""
                    customerId,mobile,productCode
                    C001,09171234567,CREDIT_CARD
                    C002,09181234567,PERSONAL_LOAN
                    """));

            assertThat(records.fields()).containsExactly("customerId", "mobile", "productCode");
            assertThat(records.recordCount()).isEqualTo(2);
            assertThat(records.rows().getFirst()).containsEntry("customerId", "C001")
                    .containsEntry("productCode", "CREDIT_CARD");
        }

        @Test
        @DisplayName("a quoted field may contain a comma and a line break")
        void quotedFieldsSurviveIntact() {
            // The reason this uses a real CSV library. A hand-rolled split on commas turns the
            // address below into three columns and shifts every field after it.
            var records = parser.parse(DatasetFormat.CSV, bytes(
                    "id,address\n1,\"12 High Street, Flat 4\nLondon\"\n"));

            assertThat(records.recordCount()).isEqualTo(1);
            assertThat(records.rows().getFirst())
                    .containsEntry("address", "12 High Street, Flat 4\nLondon");
        }

        @Test
        @DisplayName("multi-line text is kept, because whether it is legal depends on where it goes")
        void multiLineValuesAreNotRejectedAtIngestion() {
            // A newline is a paragraph in a body field and a request-splitting vector in a header.
            // Deciding that here, before the binding is known, would corrupt legitimate data.
            var records = parser.parse(DatasetFormat.CSV, bytes("notes\n\"line one\nline two\"\n"));

            assertThat(records.rows().getFirst().get("notes")).isEqualTo("line one\nline two");
        }

        @Test
        @DisplayName("a byte-order mark does not become part of the first column's name")
        void byteOrderMarkIsStripped() {
            var records = parser.parse(DatasetFormat.CSV, bytes("﻿customerId,mobile\nC001,090\n"));

            assertThat(records.fields()).containsExactly("customerId", "mobile");
        }

        @Test
        @DisplayName("two columns of the same name are refused rather than one silently winning")
        void duplicateColumnsAreRefused() {
            assertThatThrownBy(() -> parser.parse(DatasetFormat.CSV,
                    bytes("id,id\n1,2\n")))
                    .isInstanceOf(DatasetException.class)
                    .hasMessageContaining("appears more than once")
                    .hasMessageContaining("Rename one");
        }

        @Test
        @DisplayName("a column with no name is refused, because nothing could reference it")
        void unnamedColumnsAreRefused() {
            assertThatThrownBy(() -> parser.parse(DatasetFormat.CSV, bytes("id,,mobile\n1,2,3\n")))
                    .isInstanceOf(DatasetException.class)
                    .hasMessageContaining("column with no name");
        }
    }

    @Nested
    @DisplayName("JSON")
    class Json {

        @Test
        @DisplayName("a list of objects becomes rows, and their properties become fields")
        void objectsBecomeRows() {
            var records = parser.parse(DatasetFormat.JSON, bytes("""
                    [{"customerId": "C001", "amount": 42.5},
                     {"customerId": "C002", "amount": 17}]
                    """));

            assertThat(records.fields()).containsExactly("customerId", "amount");
            assertThat(records.recordCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("numbers stay numbers, so a JSON body field is not sent as a string")
        void typesSurvive() {
            // "42.5" and 42.5 are different requests against a service that validates its input.
            var records = parser.parse(DatasetFormat.JSON,
                    bytes("[{\"amount\": 42.5, \"count\": 3, \"active\": true}]"));

            assertThat(records.rows().getFirst())
                    .containsEntry("amount", 42.5d)
                    .containsEntry("count", 3L)
                    .containsEntry("active", true);
        }

        @Test
        @DisplayName("a field missing from one record is present and null, not absent")
        void missingPropertiesBecomeExplicitNulls() {
            // Otherwise a request would silently send nothing because one record was written
            // differently from the rest.
            var records = parser.parse(DatasetFormat.JSON,
                    bytes("[{\"a\": 1, \"b\": 2}, {\"a\": 3}]"));

            assertThat(records.fields()).containsExactly("a", "b");
            assertThat(records.rows().get(1)).containsKey("b").containsEntry("b", null);
        }

        @Test
        @DisplayName("a nested value is refused rather than flattened into something invented")
        void nestedValuesAreRefused() {
            assertThatThrownBy(() -> parser.parse(DatasetFormat.JSON,
                    bytes("[{\"customer\": {\"id\": \"C001\"}}]")))
                    .isInstanceOf(DatasetException.class)
                    .hasMessageContaining("nested object")
                    .hasMessageContaining("Flatten the field");
        }

        @Test
        @DisplayName("JSON that is not a list of records says so, and says what one looks like")
        void nonArrayJsonIsRefused() {
            assertThatThrownBy(() -> parser.parse(DatasetFormat.JSON, bytes("{\"a\": 1}")))
                    .isInstanceOf(DatasetException.class)
                    .hasMessageContaining("not a list of records")
                    .hasMessageContaining("array of objects");
        }
    }

    @Nested
    @DisplayName("refusals")
    class Refusals {

        @Test
        @DisplayName("an empty dataset is refused, because every value it supplies would be undefined")
        void emptyDatasetsAreRefused() {
            assertThatThrownBy(() -> parser.parse(DatasetFormat.CSV, bytes("   ")))
                    .isInstanceOf(DatasetException.class)
                    .hasMessageContaining("is empty");

            assertThatThrownBy(() -> parser.parse(DatasetFormat.JSON, bytes("[]")))
                    .isInstanceOf(DatasetException.class)
                    .hasMessageContaining("empty list");
        }

        @Test
        @DisplayName("malformed content fails at ingestion, not at preflight and not during a run")
        void malformedContentFailsEarly() {
            assertThatThrownBy(() -> parser.parse(DatasetFormat.JSON, bytes("[{\"a\": ")))
                    .isInstanceOf(DatasetException.class)
                    .hasMessageContaining("could not be read as JSON");
        }

        @Test
        @DisplayName("every refusal carries somewhere to look and something to do")
        void problemsAreActionable() {
            try {
                parser.parse(DatasetFormat.JSON, bytes("[{\"customer\": {\"id\": 1}}]"));
            } catch (DatasetException e) {
                assertThat(e.problems()).singleElement().satisfies(problem -> {
                    assertThat(problem.location()).isEqualTo("record 1, field 'customer'");
                    assertThat(problem.remedy()).isNotBlank();
                });
            }
        }
    }
}
