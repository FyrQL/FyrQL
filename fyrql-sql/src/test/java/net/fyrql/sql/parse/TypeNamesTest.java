/*
 * Copyright 2026 the FyrQL authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fyrql.sql.parse;

import net.fyrql.core.type.DataType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TypeNamesTest {

    /** The names and the types DuckDB 1.5.6 reports for them with typeof(CAST(NULL AS name)). */
    @Test
    void namesAndAliasesAsInDuckDb() {
        Map<String, String> measured = Map.ofEntries(
                Map.entry("BOOL", "BOOLEAN"), Map.entry("LOGICAL", "BOOLEAN"), Map.entry("INT1", "TINYINT"),
                Map.entry("INT2", "SMALLINT"), Map.entry("SHORT", "SMALLINT"), Map.entry("INT4", "INTEGER"),
                Map.entry("INT", "INTEGER"), Map.entry("SIGNED", "INTEGER"), Map.entry("INT8", "BIGINT"),
                Map.entry("LONG", "BIGINT"), Map.entry("INT128", "HUGEINT"), Map.entry("FLOAT4", "FLOAT"),
                Map.entry("REAL", "FLOAT"), Map.entry("FLOAT8", "DOUBLE"), Map.entry("DOUBLE PRECISION", "DOUBLE"),
                Map.entry("NUMERIC", "DECIMAL(18,3)"), Map.entry("NUMERIC(5)", "DECIMAL(5,0)"),
                Map.entry("DECIMAL", "DECIMAL(18,3)"), Map.entry("DECIMAL(5)", "DECIMAL(5,0)"),
                Map.entry("DEC(5,1)", "DECIMAL(5,1)"), Map.entry("CHAR", "VARCHAR"), Map.entry("CHAR(3)", "VARCHAR"),
                Map.entry("BPCHAR", "VARCHAR"), Map.entry("TEXT", "VARCHAR"), Map.entry("STRING", "VARCHAR"),
                Map.entry("VARCHAR(10)", "VARCHAR"), Map.entry("NVARCHAR", "VARCHAR"), Map.entry("STRING(5)", "VARCHAR"),
                Map.entry("BYTEA", "BLOB"), Map.entry("BINARY", "BLOB"), Map.entry("VARBINARY", "BLOB"),
                Map.entry("DATETIME", "TIMESTAMP"), Map.entry("TIMESTAMP WITHOUT TIME ZONE", "TIMESTAMP"),
                Map.entry("TIMESTAMP_US", "TIMESTAMP"), Map.entry("FLOAT(10)", "FLOAT"), Map.entry("FLOAT(30)", "DOUBLE"),
                Map.entry("UUID", "UUID"), Map.entry("GUID", "UUID"), Map.entry("integer", "INTEGER"),
                Map.entry("\"INTEGER\"", "INTEGER"), Map.entry("decimal (10, 2)", "DECIMAL(10,2)"),
                Map.entry("Date", "DATE"), Map.entry("HUGEINT", "HUGEINT"), Map.entry("BLOB", "BLOB"));
        for (Map.Entry<String, String> e : measured.entrySet()) {
            assertEquals(e.getValue(), TypeNames.parse(e.getKey()).sqlName(), e.getKey());
        }
        assertEquals(DataType.decimal(38, 38), TypeNames.parse("DECIMAL(38,38)"));
    }

    @Test
    void errorsAsInDuckDb() {
        Map<String, String> errors = Map.ofEntries(
                Map.entry("DECIMAL(39,1)", "DECIMAL type width must be between 1 and 38"),
                Map.entry("DECIMAL(0,0)", "DECIMAL type width must be between 1 and 38"),
                Map.entry("DECIMAL(5,6)", "DECIMAL type scale cannot be greater than width"),
                Map.entry("DOUBLE(5)", "Type DOUBLE does not take any type parameters"),
                Map.entry("DATE(3)", "Type DATE does not take any type parameters"),
                Map.entry("TIME", "Type TIME is not supported"),
                Map.entry("INTERVAL", "Type INTERVAL is not supported"),
                Map.entry("UINTEGER", "Type UINTEGER is not supported"),
                Map.entry("TIMESTAMPTZ", "Type TIMESTAMPTZ is not supported"),
                Map.entry("TIMESTAMP WITH TIME ZONE", "Type TIMESTAMP WITH TIME ZONE is not supported"),
                Map.entry("TIMESTAMP(3)", "Type TIMESTAMP does not take any type parameters"),
                Map.entry("VARCHAR(10, 2)", "Type VARCHAR takes at most one parameter"),
                Map.entry("DECIMAL(a)", "Invalid type name: DECIMAL(a)"),
                Map.entry("", "Invalid type name: "),
                Map.entry("STRUCT(a INTEGER, A VARCHAR)", "Duplicate STRUCT type argument name \"A\""),
                Map.entry("MAP(VARCHAR)", "MAP type requires exactly two type modifiers: key type and value type"),
                Map.entry("INT[3]", "Arrays of a fixed size are not supported: INT[3]"),
                Map.entry("STRUCT()", "Invalid type name: STRUCT()"),
                Map.entry("STRUCT(a)", "Invalid type name: STRUCT(a)"),
                Map.entry("STRUCT(a INTEGER,)", "Invalid type name: STRUCT(a INTEGER,)"),
                Map.entry("STRUCT(\"\" INTEGER)", "Invalid type name: STRUCT(\"\" INTEGER)"),
                Map.entry("INTEGER[", "Arrays of a fixed size are not supported: INTEGER["),
                Map.entry("LIST(INTEGER)", "Invalid type name: LIST(INTEGER)"),
                Map.entry("STRUCT(a TIME)", "Type TIME is not supported"));
        for (Map.Entry<String, String> e : errors.entrySet()) {
            IllegalArgumentException x = assertThrows(IllegalArgumentException.class, () -> TypeNames.parse(e.getKey()),
                    e.getKey());
            assertEquals(e.getValue(), x.getMessage(), e.getKey());
        }
    }

    /** Nested types to any depth, with the names DuckDB 1.5.6 gives them in typeof, field names quoted as there. */
    @Test
    void nestedTypesAsInDuckDb() {
        Map<String, String> measured = Map.ofEntries(
                Map.entry("INTEGER[]", "INTEGER[]"), Map.entry("int []", "INTEGER[]"),
                Map.entry("INTEGER[][]", "INTEGER[][]"), Map.entry("\"INTEGER\"[]", "INTEGER[]"),
                Map.entry("DECIMAL (10, 2)[]", "DECIMAL(10,2)[]"),
                Map.entry("STRUCT(a INTEGER, \"b c\" VARCHAR[])", "STRUCT(a INTEGER, \"b c\" VARCHAR[])"),
                Map.entry("struct(a int)", "STRUCT(a INTEGER)"), Map.entry("ROW(a INTEGER)", "STRUCT(a INTEGER)"),
                Map.entry("STRUCT (a DECIMAL (10, 2), b VARCHAR(5))", "STRUCT(a DECIMAL(10,2), b VARCHAR)"),
                Map.entry("STRUCT(\"select\" INTEGER, \"x y\" INTEGER, \"q\"\"r\" INTEGER)",
                        "STRUCT(\"select\" INTEGER, \"x y\" INTEGER, \"q\"\"r\" INTEGER)"),
                Map.entry("STRUCT(\"type\" INTEGER, \"Name\" INT, a_1 INT2, \"1a\" INT, _x INT, \"a$\" INT, list INT)",
                        "STRUCT(\"type\" INTEGER, \"Name\" INTEGER, a_1 SMALLINT, \"1a\" INTEGER, _x INTEGER,"
                                + " \"a$\" INTEGER, list INTEGER)"),
                Map.entry("STRUCT(A INTEGER, aB INT)", "STRUCT(A INTEGER, aB INTEGER)"),
                Map.entry("STRUCT(a DOUBLE PRECISION, b TIMESTAMP WITHOUT TIME ZONE)",
                        "STRUCT(a DOUBLE, b TIMESTAMP)"),
                Map.entry("STRUCT(a INTEGER)[]", "STRUCT(a INTEGER)[]"),
                Map.entry("MAP(VARCHAR, STRUCT(x DOUBLE))[][]", "MAP(VARCHAR, STRUCT(x DOUBLE))[][]"),
                Map.entry("map(varchar, int)", "MAP(VARCHAR, INTEGER)"),
                Map.entry("MAP(STRUCT(a INTEGER), INTEGER)", "MAP(STRUCT(a INTEGER), INTEGER)"),
                Map.entry("MAP(INTEGER[], INTEGER)", "MAP(INTEGER[], INTEGER)"));
        for (Map.Entry<String, String> e : measured.entrySet()) {
            assertEquals(e.getValue(), TypeNames.parse(e.getKey()).sqlName(), e.getKey());
        }
        assertEquals(DataType.listOf(DataType.struct(new DataType.Struct.Field("q\"r", DataType.INTEGER))),
                TypeNames.parse("STRUCT(\"q\"\"r\" INTEGER)[]"));
    }
}
