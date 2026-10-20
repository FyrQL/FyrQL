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

package net.fyrql.connector.parquet;

import net.fyrql.connector.ColumnPath;
import net.fyrql.core.type.Schema;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.MessageTypeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every row of the type mapping: a file with one column of the type,
 * written by parquet-java, whose FyrQL type must be the expected one and
 * the type DuckDB reads, where FyrQL has that type. Two differences are
 * intended: DuckDB cannot read {@code BSON}, and it takes the one field of
 * a repeated group as the element of a list even where the rules of the
 * specification for the older forms make the group the element, as
 * pyarrow and Spark read it.
 */
class ParquetSchemasTest {

    /**
     * A column in the notation of parquet-java, its type in FyrQL and the
     * type or the error of DuckDB, with the names of its types that FyrQL
     * lacks replaced by the FyrQL ones.
     */
    private record Case(String column, String type, String duckdb) {
        Case(String column, String type) {
            this(column, type, type);
        }
    }

    private static final String ONE_FIELD_IS_THE_ELEMENT = "INTEGER[]";

    private static final List<Case> CASES = List.of(
            new Case("required boolean a;", "BOOLEAN"),
            new Case("required int32 a;", "INTEGER"),
            new Case("required int32 a (INTEGER(32,true));", "INTEGER"),
            new Case("required int32 a (INTEGER(8,true));", "TINYINT"),
            new Case("required int32 a (INTEGER(16,true));", "SMALLINT"),
            new Case("required int32 a (INTEGER(8,false));", "SMALLINT"),
            new Case("required int32 a (INTEGER(16,false));", "INTEGER"),
            new Case("required int32 a (INTEGER(32,false));", "BIGINT"),
            new Case("required int64 a;", "BIGINT"),
            new Case("required int64 a (INTEGER(64,true));", "BIGINT"),
            new Case("required int64 a (INTEGER(64,false));", "HUGEINT"),
            new Case("required int32 a (DATE);", "DATE"),
            new Case("required int32 a (TIME(MILLIS,false));", "VARCHAR"),
            new Case("required int64 a (TIME(MICROS,true));", "VARCHAR"),
            new Case("required int64 a (TIME(NANOS,false));", "VARCHAR"),
            new Case("required int64 a (TIMESTAMP(MILLIS,true));", "TIMESTAMP"),
            new Case("required int64 a (TIMESTAMP(MICROS,false));", "TIMESTAMP"),
            new Case("required int64 a (TIMESTAMP(MICROS,true));", "TIMESTAMP"),
            new Case("required int64 a (TIMESTAMP(NANOS,false));", "TIMESTAMP"),
            new Case("required int64 a (TIMESTAMP(NANOS,true));", "TIMESTAMP"),
            new Case("required int96 a;", "TIMESTAMP"),
            new Case("required float a;", "FLOAT"),
            new Case("required double a;", "DOUBLE"),
            new Case("required fixed_len_byte_array(2) a (FLOAT16);", "FLOAT"),
            new Case("required binary a (STRING);", "VARCHAR"),
            new Case("required binary a (ENUM);", "VARCHAR"),
            new Case("required binary a (JSON);", "VARCHAR"),
            new Case("required binary a;", "BLOB"),
            new Case("required binary a (BSON);", "BLOB", "IO Error: Unsupported converted type (20)"),
            new Case("required fixed_len_byte_array(5) a;", "BLOB"),
            new Case("required fixed_len_byte_array(16) a (UUID);", "UUID"),
            new Case("required int32 a (DECIMAL(9,2));", "DECIMAL(9,2)"),
            new Case("required int64 a (DECIMAL(18,3));", "DECIMAL(18,3)"),
            new Case("required fixed_len_byte_array(16) a (DECIMAL(38,10));", "DECIMAL(38,10)"),
            new Case("required binary a (DECIMAL(20,5));", "DECIMAL(20,5)"),
            new Case("optional group a { required int32 x; optional binary y (STRING); }",
                    "STRUCT(x INTEGER, y VARCHAR)"),
            new Case("optional group a (LIST) { repeated group list { optional int32 element; } }", "INTEGER[]"),
            new Case("optional group a (LIST) { repeated int32 element; }", "INTEGER[]"),
            new Case("optional group a (LIST) { repeated group array { required int32 x; } }",
                    "STRUCT(x INTEGER)[]", ONE_FIELD_IS_THE_ELEMENT),
            new Case("optional group a (LIST) { repeated group a_tuple { required int32 x; } }",
                    "STRUCT(x INTEGER)[]", ONE_FIELD_IS_THE_ELEMENT),
            new Case("optional group a (LIST) { repeated group element { required int32 x; required int32 y; } }",
                    "STRUCT(x INTEGER, y INTEGER)[]"),
            new Case("repeated int32 a;", "INTEGER[]"),
            new Case("repeated group a { required int32 x; }", "STRUCT(x INTEGER)[]", ONE_FIELD_IS_THE_ELEMENT),
            new Case("optional group a (MAP) { repeated group key_value { required binary key (STRING);"
                    + " optional int32 value; } }", "MAP(VARCHAR, INTEGER)"),
            new Case("optional group a (MAP) { repeated group map (MAP_KEY_VALUE) { required binary key (STRING);"
                    + " optional int64 value; } }", "MAP(VARCHAR, BIGINT)"),
            new Case("optional group a (LIST) { repeated group list { optional group element (MAP) {"
                    + " repeated group key_value { required int32 key; optional group value { optional double d; } }"
                    + " } } }", "MAP(INTEGER, STRUCT(d DOUBLE))[]"));

    @TempDir
    Path dir;

    @Test
    void everyTypeIsTheTypeDuckDbReads() throws Exception {
        List<String> differences = new ArrayList<>();
        int n = 0;
        for (Case c : CASES) {
            Path file = ParquetTestFiles.write(dir.resolve("t" + n++ + ".parquet"), "message m { " + c.column() + " }");
            Schema schema = ParquetSchemas.schema(ParquetTestFiles.footer(file).schema());
            String fyrql = schema.type(0).sqlName();
            String duckdb;
            try {
                duckdb = fyrqlName(ParquetTestFiles.duckdbRows("SELECT column_type FROM (DESCRIBE SELECT * FROM "
                        + "read_parquet(" + ParquetTestFiles.sql(file) + "))").get(0).get(0));
            } catch (java.sql.SQLException e) {
                duckdb = e.getMessage().contains(c.duckdb()) ? c.duckdb() : e.getMessage();
            }
            if (!fyrql.equals(c.type()) || !duckdb.equals(c.duckdb())) {
                differences.add(c.column() + " expected " + c.type() + " and " + c.duckdb() + ", FyrQL " + fyrql
                        + ", DuckDB " + duckdb);
            }
        }
        assertEquals(List.of(), differences);
    }

    /** Returns the name of a type of DuckDB as the FyrQL type that stands for it. */
    private static String fyrqlName(String duckdb) {
        return duckdb.replace("TIMESTAMP WITH TIME ZONE", "TIMESTAMP")
                .replace("TIME WITH TIME ZONE", "VARCHAR")
                .replaceAll("\\bTIMESTAMP_(NS|MS|S)\\b", "TIMESTAMP")
                .replaceAll("\\bTIME_NS\\b", "VARCHAR")
                .replaceAll("\\bTIME\\b", "VARCHAR")
                .replaceAll("\\bJSON\\b", "VARCHAR")
                .replaceAll("\\bUTINYINT\\b", "SMALLINT")
                .replaceAll("\\bUSMALLINT\\b", "INTEGER")
                .replaceAll("\\bUINTEGER\\b", "BIGINT")
                .replaceAll("\\bUBIGINT\\b", "HUGEINT");
    }

    @Test
    void requiredColumnsAreNotNullable() {
        Schema s = ParquetSchemas.schema(MessageTypeParser.parseMessageType(
                "message m { required int32 a; optional int32 b; repeated int32 c; }"));
        assertFalse(s.nullable(0));
        assertTrue(s.nullable(1));
        assertFalse(s.nullable(2));
    }

    @Test
    void typesFyrQlCannotReadNameTheColumn() {
        assertEquals("The column a.b has the type DECIMAL(40,2), wider than the largest DECIMAL of 38 digits",
                assertThrows(IllegalArgumentException.class, () -> ParquetSchemas.schema(MessageTypeParser
                        .parseMessageType("message m { optional group a { required fixed_len_byte_array(17) b"
                                + " (DECIMAL(40,2)); } }"))).getMessage());
        assertEquals("The column m2 is a MAP without one repeated group of a key and a value",
                assertThrows(IllegalArgumentException.class, () -> ParquetSchemas.schema(MessageTypeParser
                        .parseMessageType("message m { optional group m2 (MAP) { repeated group key_value {"
                                + " required int32 key; } } }"))).getMessage());
    }

    @Test
    void theRequestedSchemaKeepsTheColumnsAndFieldsOfThePaths() {
        MessageType file = MessageTypeParser.parseMessageType("message m { required int64 id; optional binary name"
                + " (STRING); optional group s { required int32 x; optional group c { optional int32 x;"
                + " optional int32 y; } optional binary z (STRING); } optional group l (LIST) { repeated group list {"
                + " optional int32 element; } } }");
        assertEquals(MessageTypeParser.parseMessageType("message m { required int64 id; optional group l (LIST) {"
                + " repeated group list { optional int32 element; } } }"),
                ParquetSchemas.requested(file, List.of(ColumnPath.whole(3), ColumnPath.whole(0))));
        assertEquals(MessageTypeParser.parseMessageType("message m { optional group s { optional group c {"
                + " optional int32 y; } optional binary z (STRING); } }"),
                ParquetSchemas.requested(file, List.of(new ColumnPath(2, List.of("z")),
                        new ColumnPath(2, List.of("c", "y")))));
        assertEquals(file.getType(2), ParquetSchemas.requested(file, List.of(new ColumnPath(2, List.of("c", "y")),
                ColumnPath.whole(2))).getType(0));
        assertEquals("No field w in s", assertThrows(IllegalArgumentException.class,
                () -> ParquetSchemas.requested(file, List.of(new ColumnPath(2, List.of("w"))))).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> ParquetSchemas.requested(file, List.of(new ColumnPath(3, List.of("element")))));
    }
}
