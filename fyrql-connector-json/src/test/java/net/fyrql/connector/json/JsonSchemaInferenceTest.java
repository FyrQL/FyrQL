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

import net.fyrql.connector.Batches;
import net.fyrql.connector.ColumnPath;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.ConversionException;
import net.fyrql.core.vector.Batch;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each rule of the inference, with the columns DuckDB 1.5.6 infers for the
 * same input; JSON is written with single quotes for readability.
 */
class JsonSchemaInferenceTest {

    /** Returns the columns inferred for the input, as {@code name TYPE, ...}. */
    private static String columns(String json, JsonOptions options) {
        return describe(layout(json, options).schema());
    }

    private static String columns(String json) {
        return columns(json, JsonOptions.DEFAULT);
    }

    static JsonLayout layout(String json, JsonOptions options) {
        byte[] bytes = json.replace('\'', '"').getBytes(StandardCharsets.UTF_8);
        return JsonSchemaInference.infer(new ByteArrayInputStream(bytes), options, "test.json");
    }

    static String describe(Schema schema) {
        List<String> columns = new ArrayList<>();
        for (int c = 0; c < schema.size(); c++) {
            columns.add(schema.name(c) + " " + schema.type(c).sqlName());
        }
        return String.join(", ", columns);
    }

    @Test
    void scalarsAreBooleanBigintDoubleAndVarchar() {
        assertEquals("a BIGINT, b VARCHAR, c BOOLEAN, d DOUBLE",
                columns("[{'a': 1, 'b': 'x', 'c': true, 'd': 1.5}, {'a': 2, 'b': null, 'c': false, 'd': 2}]"));
        assertEquals("x DOUBLE", columns("[{'x': 1.0}, {'x': 1e3}]"));
    }

    @Test
    void theColumnsAreTheKeysInTheOrderTheyFirstAppear() {
        assertEquals("a DOUBLE, b VARCHAR", columns("{'a': 1}\n{'a': 2.5}\n{'b': 'y'}"));
        assertEquals("x BIGINT, y BIGINT, z BIGINT", columns("[{'x': 1, 'y': 2}, {'y': 3, 'z': 4, 'x': 5}]"));
        assertEquals("a BIGINT", columns("[null, {'a': 1}, {}]"));
    }

    @Test
    void integersBeyondBigintAreHugeintUpTo2To64AndDoubleBeyond() {
        assertEquals("n HUGEINT", columns("[{'n': 9223372036854775807}, {'n': 9223372036854775808}]"));
        assertEquals("n HUGEINT", columns("[{'n': 18446744073709551615}]"));
        assertEquals("n DOUBLE", columns("[{'n': 18446744073709551616}]"));
        assertEquals("n DOUBLE", columns("[{'n': -9223372036854775809}]"));
        assertEquals("n BIGINT", columns("[{'n': -9223372036854775808}]"));
        assertEquals("n HUGEINT[]", columns("[{'n': [1, 9223372036854775808]}]"));
    }

    @Test
    void valuesOfNoSingleTypeAreRawJsonText() {
        for (String other : List.of("'x'", "true", "{'x': 1}", "[1, 2]")) {
            assertEquals("a VARCHAR", columns("[{'a': 1}, {'a': " + other + "}]"), other);
        }
        assertEquals("a VARCHAR", columns("[{'a': null}, {'a': null}]"));
        assertEquals("a VARCHAR", columns("[{'a': []}, {'a': {'x': 1}}]"));
        JsonLayout layout = layout("[{'a': 1}, {'a': 'x'}]", JsonOptions.DEFAULT);
        assertTrue(layout.shape(0) instanceof JsonShape.Json);
        assertFalse(layout(("[{'a': 'x'}]"), JsonOptions.DEFAULT).shape(0) instanceof JsonShape.Json);
    }

    @Test
    void arraysAreListsOfTheTypeOfAllTheirElements() {
        assertEquals("a DOUBLE[]", columns("[{'a': [1, 2.5]}, {'a': [null]}]"));
        assertEquals("a BIGINT[]", columns("[{'a': [1, null, 2]}, {'a': null}, {'a': []}]"));
        assertEquals("a VARCHAR[]", columns("[{'a': [1, 'x']}]"));
        assertEquals("a VARCHAR[]", columns("[{'a': []}, {'a': []}]"));
        assertEquals("a BIGINT[][]", columns("[{'a': [[]]}, {'a': [[1]]}]"));
        assertEquals("a DOUBLE[][]", columns("[{'a': [[1], [2.5]]}]"));
        assertEquals("a VARCHAR[]", columns("[{'a': [[1], 'x']}]"));
        assertEquals("b BOOLEAN[]", columns("[{'b': [true, false, null]}]"));
    }

    @Test
    void objectsAreStructsOfAllTheirKeys() {
        assertEquals("s STRUCT(x BIGINT, y VARCHAR, z BOOLEAN[])",
                columns("[{'s': {'x': 1, 'y': 'a'}}, {'s': {'y': 'b', 'z': [true]}}]"));
        assertEquals("x BIGINT, s STRUCT(p BIGINT, q BIGINT), w BIGINT",
                columns("[{'x': 1, 's': {'p': 1}}, {'s': {'q': 2, 'p': 3}, 'w': 1}]"));
        assertEquals("a STRUCT(x BIGINT)[]", columns("[{'a': [{}]}, {'a': [{'x': 1}]}]"));
        assertEquals("a STRUCT(x BIGINT, y BIGINT)[]", columns("[{'a': [{'x': 1}, {'y': 2}]}]"));
        assertEquals("a STRUCT(x BIGINT)", columns("[{'a': {}}, {'a': {'x': 1}}]"));
        assertEquals("a MAP(VARCHAR, VARCHAR)", columns("[{'a': {}}, {'a': {}}]"));
    }

    @Test
    void manyKeysOfOneTypeMakeAMap() {
        JsonOptions two = JsonOptions.DEFAULT.withMapInferenceThreshold(2);
        assertEquals("m MAP(VARCHAR, DOUBLE)",
                columns("[{'m': {'k1': 1, 'k2': 2, 'k3': 3}}, {'m': {'k4': 2.5}}]", two));
        assertEquals("m STRUCT(k1 BIGINT, k2 VARCHAR, k3 BIGINT)", columns("[{'m': {'k1': 1, 'k2': 'x', 'k3': 3}}]",
                two));
        assertEquals("m MAP(VARCHAR, MAP(VARCHAR, BIGINT))",
                columns("[{'m': {'k1': {'a': 1}, 'k2': {'b': 2}, 'k3': {'a': 3}}}]", two));
        assertEquals("m STRUCT(k1 BIGINT, k2 BIGINT, k3 BIGINT)", columns("[{'m': {'k1': 1, 'k2': 2, 'k3': 3}}]"));
        String keys200 = IntStream.range(0, 200).mapToObj(i -> "'k" + i + "': " + i).collect(Collectors.joining(", "));
        assertEquals("m MAP(VARCHAR, BIGINT)", columns("{'m': {" + keys200 + "}}"));
        assertEquals("json MAP(VARCHAR, BIGINT)", columns("{" + keys200 + "}"));
        assertEquals("m STRUCT(k0 BIGINT, k1 BIGINT)", columns("{'m': {'k0': 0, 'k1': 1}}",
                JsonOptions.DEFAULT.withMapInferenceThreshold(-1)));
    }

    @Test
    void keysThatAppearInFewObjectsMakeAMap() {
        assertEquals("m MAP(VARCHAR, BIGINT)", columns(objects(20, i -> "{'m': {'k" + i + "': " + i + "}}")));
        assertEquals("m MAP(VARCHAR, BIGINT)", columns(objects(10, i -> "{'m': {'k" + i + "': " + i + "}}")));
        assertEquals("m STRUCT(k0 BIGINT, k1 BIGINT, k2 BIGINT, k3 BIGINT, k4 BIGINT)",
                columns(objects(5, i -> "{'m': {'k" + i + "': " + i + "}}")));
        assertEquals("json MAP(VARCHAR, BIGINT)", columns(objects(20, i -> "{'k" + i + "': " + i + "}")));
        assertEquals("m MAP(VARCHAR, VARCHAR)",
                columns(objects(20, i -> "{'m': {'k" + i + "': " + (i % 2 == 0 ? "'s'" : "1") + "}}")));
        assertEquals("m MAP(VARCHAR, VARCHAR), n BIGINT", columns(objects(20, i -> "{'m': {'k" + i + "': 's'}, 'n': 1}")));
        assertEquals("m STRUCT(k0 BIGINT, k1 BIGINT, k2 BIGINT, k3 BIGINT, k4 BIGINT, k5 BIGINT, k6 BIGINT, "
                + "k7 BIGINT, k8 BIGINT, k9 BIGINT)", columns(objects(10, i -> "{'m': {'k" + i + "': " + i + "}}"),
                        JsonOptions.DEFAULT.withFieldAppearanceThreshold(0.05)));
    }

    private static String objects(int n, java.util.function.IntFunction<String> object) {
        return IntStream.range(0, n).mapToObj(object).collect(Collectors.joining("\n"));
    }

    @Test
    void stringsOfDatesTimestampsAndUuidsAreTheseTypes() {
        assertEquals("d DATE, t TIMESTAMP, t2 TIMESTAMP, t3 VARCHAR, tz TIMESTAMP, tz2 TIMESTAMP, u UUID",
                columns("[{'d': '2020-01-02', 't': '2020-01-02 03:04:05', 't2': '2020-01-02T03:04:05', "
                        + "'t3': '2020-01-02T03:04:05.123456', 'tz': '2020-01-02T03:04:05Z', "
                        + "'tz2': '2020-01-02T03:04:05+02:00', 'u': '123e4567-e89b-12d3-a456-426614174000'}]"));
        assertEquals("d DATE", columns("[{'d': '2020-01-02'}, {'d': '2020-1-2'}, {'d': ' 2020-01-03 '}]"));
        assertEquals("d DATE, e DATE", columns("[{'d': '20-01-02', 'e': '13-02-2020'}]"));
        assertEquals("a DATE[]", columns("[{'a': ['2020-01-02', null]}]"));
        assertEquals("a STRUCT(b TIMESTAMP)", columns("[{'a': {'b': '2020-01-02T03:04:05Z'}}]"));
        assertEquals("u UUID", columns("[{'u': '123E4567-E89B-12D3-A456-426614174000'}]"));
        for (String other : List.of("'2020-01-02T03:04:05.5'", "'2020-01-02 03:04:05Z'", "'2020-01-02 03:04'",
                "'2020-01-02 03:04:05.1234567'", "'2020-01-02 24:00:00'", "'2020-02-30'", "'2020/01/02'",
                "'{123e4567-e89b-12d3-a456-426614174000}'", "'12:34:56'", "'1e3'", "'true'")) {
            assertEquals("a VARCHAR", columns("[{'a': " + other + "}]"), other);
        }
    }

    @Test
    void stringsOfDifferentFormsInOneColumnAreVarchar() {
        assertEquals("d VARCHAR", columns("[{'d': '2020-01-02'}, {'d': 'x'}]"));
        assertEquals("d VARCHAR", columns("[{'d': '2020-01-02'}, {'d': '2020-01-02 03:04:05'}]"));
        assertEquals("t VARCHAR", columns("[{'t': '2020-01-02 03:04:05.5'}, {'t': '2020-01-02 03:04:06'}]"));
        assertEquals("t VARCHAR", columns("[{'t': '2020-01-02T03:04:05Z'}, {'t': '2020-01-02T03:04:05'}]"));
        assertEquals("t TIMESTAMP", columns("[{'t': '2020-01-02T03:04:05+02:00'}, {'t': '2020-01-02T03:04:05-05:30'}]"));
        assertEquals("t TIMESTAMP", columns("[{'t': '2020-01-02 3:4:5'}, {'t': '2020-1-2  03:04:05'}]"));
        assertEquals("a TIMESTAMP, b TIMESTAMP", columns("[{'a': '2020-01-02 03:04:06', 'b': '2020-01-02 03:04:05.5'}]"));
        assertEquals("u VARCHAR", columns("[{'u': '123e4567-e89b-12d3-a456-426614174000'}, {'u': 'x'}]"));
    }

    @Test
    void aDateOrTimestampFormatReplacesTheFormsOfItsType() {
        assertEquals("d VARCHAR", columns("[{'d': '02.01.2020'}, {'d': '2020-01-02'}]",
                JsonOptions.DEFAULT.withDateFormat("%d.%m.%Y")));
        assertEquals("d DATE", columns("[{'d': '02.01.2020'}]", JsonOptions.DEFAULT.withDateFormat("%d.%m.%Y")));
        assertEquals("t TIMESTAMP", columns("[{'t': '02.01.2020 03:04'}]",
                JsonOptions.DEFAULT.withTimestampFormat("%d.%m.%Y %H:%M")));
        assertEquals("t VARCHAR", columns("[{'t': '2020-01-02T03:04:05'}]",
                JsonOptions.DEFAULT.withTimestampFormat("%d.%m.%Y %H:%M")));
        assertEquals("d DATE", columns("[{'d': 'Jan 02 2020'}]", JsonOptions.DEFAULT.withDateFormat("%b %d %Y")));
    }

    @Test
    void recordsThatAreNotObjectsAreValuesOfTheColumnJson() {
        assertEquals("json BIGINT", columns("[1, 2, 3]"));
        assertEquals("json VARCHAR", columns("['a', 'b']"));
        assertEquals("json BIGINT[]", columns("[[1, 2], [3]]"));
        assertEquals("json BIGINT", columns("1\n2"));
        assertEquals("json VARCHAR", columns("[{'a': 1}, 2]"));
        assertTrue(layout("[{'a': 1}, 2]", JsonOptions.DEFAULT).shape(0) instanceof JsonShape.Json);
        assertFalse(layout("[{'a': 1}, 2]", JsonOptions.DEFAULT).records());
        assertEquals("json VARCHAR", columns(""));
        assertEquals("json VARCHAR", columns("  \n "));
        assertEquals("json VARCHAR", columns("[]"));
        // DuckDB reads a sequence of arrays as such in the format auto; FyrQL reads an input that starts with an
        // array as one array of records, which saves reading the whole input to see whether more follows.
        assertEquals("json STRUCT(a BIGINT)[]", columns("[{'a': 1}]\n[{'a': 2}]",
                JsonOptions.DEFAULT.withFormat(JsonFormat.NEWLINE_DELIMITED)));
        assertEquals("a BIGINT", columns("{'a': 1} {'a': 2}"));
        assertEquals("a BIGINT", columns("{\n  'a': 1\n}\n{\n  'a': 2\n}"));
    }

    @Test
    void theFormatSaysWhereTheRecordsAre() {
        assertEquals("json STRUCT(a BIGINT)[]",
                columns("[{'a': 1}, {'a': 2}]", JsonOptions.DEFAULT.withFormat(JsonFormat.NEWLINE_DELIMITED)));
        assertEquals("a BIGINT", columns("[{'a': 1}, {'a': 2}]", JsonOptions.DEFAULT.withFormat(JsonFormat.ARRAY)));
        ConversionException e = assertThrows(ConversionException.class,
                () -> columns("{'a': 1}", JsonOptions.DEFAULT.withFormat(JsonFormat.ARRAY)));
        assertTrue(e.getMessage().startsWith("Expected a top-level JSON array with format 'array' in test.json, "
                + "but it starts with an OBJECT"), e.getMessage());
        e = assertThrows(ConversionException.class, () -> columns("[{'a': 1}] [{'a': 2}]"));
        assertTrue(e.getMessage().startsWith("More follows the top-level JSON array in test.json at line 1, column 12"),
                e.getMessage());
        assertEquals(JsonFormat.NEWLINE_DELIMITED, JsonFormat.of("Newline_Delimited"));
        assertEquals("Unknown JSON format \"records\"; supported are auto, array, newline_delimited",
                assertThrows(IllegalArgumentException.class, () -> JsonFormat.of("records")).getMessage());
    }

    @Test
    void columnNamesAreMadeUniqueAsInDuckDb() {
        assertEquals("A BIGINT, a_1 BIGINT", columns("[{'A': 1, 'a': 2}]"));
        assertEquals("x BIGINT, C1 BIGINT", columns("[{'x': 1, '': 2}]"));
        assertEquals("C0 BIGINT", columns("[{'': 1}]"));
        JsonLayout layout = layout("[{'A': 1, 'a': 2}]", JsonOptions.DEFAULT);
        assertEquals(List.of("A", "a"), layout.keys());
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> columns("[{'a': 1, 'B': {'Bb': 1, 'bb': 2}}]"));
        assertEquals("Duplicate name \"bb\" in a STRUCT inferred from JSON at B; an explicit schema reads it",
                e.getMessage());
    }

    @Test
    void theSampleIsTheFirstRecords() {
        String json = "[{'a': 1}, {'b': 2}, {'c': 3}]";
        assertEquals("a BIGINT, b BIGINT", columns(json, JsonOptions.DEFAULT.withSampleSize(2)));
        assertEquals("a BIGINT, b BIGINT, c BIGINT", columns(json, JsonOptions.DEFAULT.withSampleSize(-1)));
        assertEquals(2, layout(json, JsonOptions.DEFAULT.withSampleSize(2)).sampled());
        assertEquals(-1, layout(json, JsonOptions.DEFAULT.withSampleSize(3)).sampled());
        assertEquals("The sample_size must be -1 or positive: 0",
                assertThrows(IllegalArgumentException.class, () -> JsonOptions.DEFAULT.withSampleSize(0)).getMessage());
    }

    @Test
    void malformedJsonIsAnErrorWithItsPlace() {
        ConversionException e = assertThrows(ConversionException.class, () -> columns("[{'a': 1},\n {'a': ]"));
        assertTrue(e.getMessage().startsWith("Malformed JSON in test.json at line 2, column 8, in record 2: "),
                e.getMessage());
        e = assertThrows(ConversionException.class, () -> columns("{'a': 1}\n{'a': "));
        assertTrue(e.getMessage().startsWith("Malformed JSON in test.json at line 2"), e.getMessage());
    }

    /** Infers the records a pointer points to and reads all their columns. */
    private static List<List<Object>> pointed(String json, String pointer, JsonOptions options) {
        byte[] bytes = json.replace('\'', '"').getBytes(StandardCharsets.UTF_8);
        JsonLayout layout = JsonSchemaInference.infer(new ByteArrayInputStream(bytes), options, pointer, "test.json");
        List<ColumnPath> all = ColumnPath.whole(IntStream.range(0, layout.schema().size()).boxed().toList());
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of(describe(layout.schema())));
        for (Batch b : Batches.readAll(JsonRecordReader.open(new ByteArrayInputStream(bytes), layout, all, options,
                pointer, 2, "test.json"))) {
            for (int r = 0; r < b.rowCount(); r++) {
                rows.add(Arrays.asList(b.row(r)));
            }
        }
        return rows;
    }

    @Test
    void aPointerSelectsTheRecordsAndWhatFollowsThemIsNotRead() {
        String page = "{'meta': {'n': [1, {'x': 2}]}, 'data': [{'id': 1, 'a/b': 'x'}, {'id': 2}, {'id': 3}],"
                + " 'next': 'broken";
        assertEquals(List.of(List.of("id BIGINT, a/b VARCHAR"), List.of(1L, "x"), Arrays.asList(2L, null),
                Arrays.asList(3L, null)), pointed(page, "/data", JsonOptions.DEFAULT));
        assertEquals(List.of(List.of("x BIGINT"), List.of(2L)), pointed(page, "/meta/n/1", JsonOptions.DEFAULT));
        assertEquals(List.of(List.of("id BIGINT"), List.of(2L)), pointed(page, "/data/1", JsonOptions.DEFAULT));
        assertEquals(List.of(List.of("a/b VARCHAR"), List.of("x")), pointed("{'v': [{'a/b': 'x'}]}", "/v",
                JsonOptions.DEFAULT));
        assertEquals(List.of(List.of("json VARCHAR")), pointed("{'v': []}", "/v", JsonOptions.DEFAULT));
        assertEquals(List.of(List.of("json BIGINT[]"), List.of(List.of(1L, 2L))), pointed("{'v': [1, 2]}", "/v",
                JsonOptions.DEFAULT.withFormat(JsonFormat.NEWLINE_DELIMITED)));
        assertEquals(List.of(List.of("k BIGINT"), List.of(1L)), pointed("{'a~b': {'c/d': [{'k': 1}]}}", "/a~0b/c~1d",
                JsonOptions.DEFAULT));

        ConversionException e = assertThrows(ConversionException.class,
                () -> pointed("{'data': []}", "/rows", JsonOptions.DEFAULT));
        assertTrue(e.getMessage().startsWith("The JSON pointer \"/rows\" points to nothing in test.json: the object"
                + " has no key \"rows\""), e.getMessage());
        e = assertThrows(ConversionException.class, () -> pointed(page, "/data/7", JsonOptions.DEFAULT));
        assertTrue(e.getMessage().contains("the array has no element 7"), e.getMessage());
        e = assertThrows(ConversionException.class, () -> pointed(page, "/meta/n/0/x", JsonOptions.DEFAULT));
        assertTrue(e.getMessage().contains("where it looks for \"x\", it finds an INTEGER"), e.getMessage());
        e = assertThrows(ConversionException.class, () -> pointed(page, "/meta",
                JsonOptions.DEFAULT.withFormat(JsonFormat.ARRAY)));
        assertTrue(e.getMessage().startsWith("Expected a JSON array at the pointer \"/meta\" with format 'array' in"
                + " test.json, but it is an OBJECT"), e.getMessage());
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> pointed(page, "data", JsonOptions.DEFAULT));
        assertTrue(bad.getMessage().startsWith("Invalid JSON pointer \"data\""), bad.getMessage());
    }
}
