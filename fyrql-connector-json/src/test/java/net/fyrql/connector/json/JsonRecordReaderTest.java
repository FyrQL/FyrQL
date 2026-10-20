/*
 * Copyright 2026 the FyrQL authors
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 */

package net.fyrql.connector.json;

import net.fyrql.connector.ColumnPath;
import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The values next to the records along a JSON pointer, and strings read by
 * a parser of their own; JSON is written with single quotes for
 * readability.
 */
class JsonRecordReaderTest {

    private static final Schema A = new Schema(List.of(new Schema.Field("a", DataType.BIGINT, true)));

    private static JsonRecordReader open(String json, Schema schema, JsonStringParser parser, String pointer,
            int batchSize, Set<String> siblings) {
        byte[] bytes = json.replace('\'', '"').getBytes(StandardCharsets.UTF_8);
        List<ColumnPath> columns = new ArrayList<>();
        for (int c = 0; c < schema.size(); c++) {
            columns.add(ColumnPath.whole(c));
        }
        return JsonRecordReader.open(new ByteArrayInputStream(bytes), JsonLayout.of(schema, parser), columns,
                JsonOptions.DEFAULT, pointer, batchSize, "src", siblings);
    }

    /** Reads the rest of the records. */
    private static List<List<Object>> rest(JsonRecordReader reader) {
        List<List<Object>> rows = new ArrayList<>();
        Batch b;
        while ((b = reader.next()) != null) {
            for (int r = 0; r < b.rowCount(); r++) {
                rows.add(Arrays.asList(b.row(r)));
            }
        }
        return rows;
    }

    @Test
    void valuesBeforeAndAfterTheRecords() {
        try (JsonRecordReader r = open("{'d': {'__count': '3', 'skipped': 'x', 'results': [{'a': 1}, {'a': 2},"
                + " {'a': 3}], '__next': 'https://host/Set?$skiptoken=3'}}", A, null, "/d/results", 2,
                Set.of("__count", "__next"))) {
            assertEquals(2, r.next().rowCount());
            assertEquals(Map.of("__count", "3"), r.siblings());
            assertEquals(List.of(List.of(3L)), rest(r));
            assertEquals(Map.of("__count", "3", "__next", "https://host/Set?$skiptoken=3"), r.siblings());
        }
        try (JsonRecordReader r = open("{'@odata.context': 'c', '@odata.count': 5, 'value': [],"
                + " '@odata.nextLink': 'Set?$skiptoken=5', 'flag': true, 'nothing': null, 'object': {'k': 1},"
                + " 'array': [1, 2], '@odata.count': 6}", A, null, "/value", 2,
                Set.of("@odata.count", "@odata.nextLink", "flag", "nothing", "object", "array"))) {
            assertEquals(List.of(), rest(r));
            assertEquals(Map.of("@odata.count", "5", "@odata.nextLink", "Set?$skiptoken=5", "flag", "true"),
                    r.siblings());
        }
    }

    @Test
    void valuesOnEveryLevelAlongThePointer() {
        try (JsonRecordReader r = open("{'first': 1.50, 'pages': [{'skipped': {'items': [9]}}, {'items':"
                + " [{'a': 1}], 'next': 'p3', 'deep': {'next': 'no'}}, {'next': 'no'}], 'total': 7}", A, null,
                "/pages/1/items", 10, Set.of("first", "next", "total"))) {
            assertEquals(List.of(List.of(1L)), rest(r));
            assertEquals(Map.of("first", "1.50", "next", "p3", "total", "7"), r.siblings());
        }
        // The one value the pointer points to, if it is no array, is the record.
        try (JsonRecordReader r = open("{'d': {'a': 4, '__metadata': {}}, 'after': 'x'}", A, null, "/d", 10,
                Set.of("after"))) {
            assertEquals(List.of(List.of(4L)), rest(r));
            assertEquals(Map.of("after", "x"), r.siblings());
        }
    }

    @Test
    void whatFollowsTheRecordsIsReadOnlyForValuesNextToThem() {
        String broken = "{'d': {'results': [{'a': 1}], 'x': ]";
        try (JsonRecordReader r = open(broken, A, null, "/d/results", 10, Set.of())) {
            assertEquals(List.of(List.of(1L)), rest(r));
        }
        try (JsonRecordReader r = open(broken, A, null, "/d/results", 10, Set.of("__next"))) {
            ConversionException e = assertThrows(ConversionException.class, () -> rest(r));
            assertEquals("Malformed JSON in src at line 1, column 36, in record 1: Unexpected character (']' (code"
                    + " 93)): expected a valid value (JSON String, Number (or 'NaN'/'+INF'/'-INF'), Array, Object or"
                    + " token 'null', 'true' or 'false')", e.getMessage());
        }
    }

    @Test
    void aParserReadsTheStringsAlsoInsideStructsAndLists() {
        Schema schema = new Schema(List.of(new Schema.Field("at", DataType.TIMESTAMP, true),
                new Schema.Field("s", DataType.struct(new DataType.Struct.Field("at", DataType.TIMESTAMP)), true),
                new Schema.Field("l", DataType.listOf(DataType.TIMESTAMP), true),
                new Schema.Field("v", DataType.VARCHAR, true),
                new Schema.Field("n", DataType.BIGINT, true)));
        JsonStringParser parser = (text, type) -> {
            if (type == DataType.VARCHAR) {
                return text.toUpperCase();
            }
            if (!text.startsWith("/Date(") || !text.endsWith(")/")) {
                throw new IllegalArgumentException("not a date of OData");
            }
            return Long.parseLong(text.substring(6, text.length() - 2)) * 1000;
        };
        try (JsonRecordReader r = open("[{'at': '/Date(1)/', 's': {'at': '/Date(2)/'}, 'l': ['/Date(3)/', null],"
                + " 'v': 'x', 'n': 5}]", schema, parser, "", 10, Set.of())) {
            assertEquals(List.of(Arrays.asList(1000L, List.of(2000L), Arrays.asList(3000L, null), "X", 5L)),
                    rest(r));
        }
        try (JsonRecordReader r = open("[{'n': 1}, {'at': '2024-01-01'}]", schema, parser, "", 10, Set.of())) {
            ConversionException e = assertThrows(ConversionException.class, () -> rest(r));
            assertEquals("Record 2 of src, column at: cannot read \"2024-01-01\" as TIMESTAMP: not a date of OData",
                    e.getMessage());
        }
    }
}
