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

package net.fyrql;

import net.fyrql.core.type.DataType;
import net.fyrql.core.type.Schema;
import net.fyrql.core.value.Casts;
import net.fyrql.core.value.Conversions;
import net.fyrql.exec.QueryContext;
import net.fyrql.sql.SqlException;
import net.fyrql.sql.parse.Parameters;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Queries with parameters: prepared once, run with values, as the query with literals of the values runs. */
class PreparedQueryTest {

    private static FyrQL engine;

    @BeforeAll
    static void open() {
        engine = FyrQL.builder().build();
    }

    @AfterAll
    static void close() {
        engine.close();
    }

    /** A value of a type, which the tests compare with the value of a column of that type. */
    private record Value(DataType type, Object value) {
    }

    private static final DataType STRUCT = DataType.struct(new DataType.Struct.Field("a", DataType.INTEGER),
            new DataType.Struct.Field("b", DataType.VARCHAR));

    private static final List<Value> VALUES = List.of(
            new Value(DataType.BOOLEAN, true),
            new Value(DataType.TINYINT, (byte) -7),
            new Value(DataType.SMALLINT, (short) 300),
            new Value(DataType.INTEGER, Integer.MIN_VALUE),
            new Value(DataType.BIGINT, Long.MAX_VALUE),
            new Value(DataType.HUGEINT, BigInteger.TEN.pow(37).negate()),
            new Value(DataType.FLOAT, 1.5f),
            new Value(DataType.DOUBLE, -2.25e-300),
            new Value(DataType.decimal(10, 2), new BigDecimal("-12.34")),
            new Value(DataType.decimal(38, 10), new BigDecimal("1234567890123456789012345678.0123456789")),
            new Value(DataType.VARCHAR, "it's ? -- not a comment"),
            new Value(DataType.BLOB, new byte[] {0, (byte) 0xff, 39}),
            new Value(DataType.DATE, (int) LocalDate.of(2024, 2, 29).toEpochDay()),
            new Value(DataType.TIMESTAMP, Conversions.toMicros(LocalDateTime.of(1999, 12, 31, 23, 59, 58, 123_456_000))),
            new Value(DataType.UUID, UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")),
            new Value(DataType.listOf(DataType.INTEGER), Arrays.asList(1, null, 3)),
            new Value(DataType.listOf(DataType.VARCHAR), List.of("a", "'b'")),
            new Value(STRUCT, Arrays.asList(1, "x")));

    /** Returns the literal of a value, as the text of a query with literals has it. */
    private static String literal(Value v) {
        return Parameters.literal(v.value(), v.type());
    }

    @Test
    void everyTypeGoesInAsAParameterAndComesOutAsAValue() {
        for (Value v : VALUES) {
            String sql = "SELECT ? = x AS same, coalesce(?, x) AS c, x IS NOT DISTINCT FROM ? AS d FROM (SELECT "
                    + literal(v) + " AS x) s";
            PreparedQuery query = engine.prepare(sql);
            String what = v.type().sqlName();
            assertEquals(List.of(v.type(), v.type(), v.type()), query.parameterTypes(), what);
            assertEquals(List.of(DataType.BOOLEAN, v.type(), DataType.BOOLEAN), query.schema().types(), what);
            List<Object[]> rows = engine.rows(query, List.of(v.value(), v.value(), v.value()));
            assertEquals(1, rows.size(), what);
            assertEquals(true, rows.get(0)[0], what);
            assertEquals(Casts.format(v.value(), v.type()), Casts.format(rows.get(0)[1], v.type()),
                    what);
            assertEquals(true, rows.get(0)[2], what);
            Object[] nulls = engine.rows(query, Arrays.asList(null, null, null)).get(0);
            assertEquals(null, nulls[0], what);
            assertEquals(Casts.format(v.value(), v.type()), Casts.format(nulls[1], v.type()),
                    what);
            assertEquals(false, nulls[2], what);
        }
    }

    @Test
    void aPreparedQueryGivesTheRowsOfTheQueryWithTheLiterals() {
        String table = "(VALUES (1, 'a', CAST('1.50' AS DECIMAL(5,2))), (2, 'b', CAST('-3.25' AS DECIMAL(5,2))),"
                + " (3, NULL, NULL)) t";
        String prepared = "SELECT col0 * ? AS n, upper(col1) || ? AS s, col2 + ? AS d FROM " + table
                + " WHERE col0 BETWEEN ? AND ? OR col1 IN (?, 'z') ORDER BY col0";
        String literal = "SELECT col0 * CAST(10 AS INTEGER) AS n, upper(col1) || CAST('!' AS VARCHAR) AS s, col2 + "
                + "CAST('0.75' AS DECIMAL(5,2)) AS d FROM " + table + " WHERE col0 BETWEEN CAST(2 AS INTEGER) AND "
                + "CAST(3 AS INTEGER) OR col1 IN (CAST('a' AS VARCHAR), 'z') ORDER BY col0";
        PreparedQuery query = engine.prepare(prepared);
        assertEquals(List.of(DataType.INTEGER, DataType.VARCHAR, DataType.decimal(5, 2), DataType.INTEGER,
                DataType.INTEGER, DataType.VARCHAR), query.parameterTypes());
        List<Object> values = List.of(10, "!", new BigDecimal("0.75"), 2, 3, "a");
        assertEquals(engine.plan(literal).schema(), engine.plan(query, values).schema());
        assertEquals(text(engine.rows(literal)), text(engine.rows(query, values)));
        assertEquals(3, engine.rows(query, values).size());
        // The values of one run do not stay for the next.
        assertEquals(List.of("[20, B?, -2.50]"), text(engine.rows(query, List.of(10, "?", new BigDecimal("0.75"),
                2, 2, "y"))));
    }

    @Test
    void aNullKeepsTheTypesOfThePreparedQuery() {
        // With a NULL, the sum would fold into a bare NULL, an INTEGER; the result keeps the DECIMAL.
        PreparedQuery query = engine.prepare("SELECT d + ? AS s, ? || 'x' AS t FROM (SELECT CAST('1.5' AS "
                + "DECIMAL(10,2)) AS d) u");
        Schema schema = Schema.of(new Schema.Field("s", DataType.decimal(11, 2)), new Schema.Field("t",
                DataType.VARCHAR));
        assertEquals(schema.types(), query.schema().types());
        try (QueryResult result = engine.execute(query, Arrays.asList(null, null))) {
            assertEquals(schema.types(), result.schema().types());
            assertEquals(List.of("s", "t"), List.of(result.schema().name(0), result.schema().name(1)));
            assertArrayEquals(new Object[] {null, null}, result.rows().get(0));
        }
        try (QueryResult result = engine.execute(query, List.of(new BigDecimal("2.25"), "y"), new QueryContext())) {
            assertEquals(schema.types(), result.schema().types());
            assertArrayEquals(new Object[] {new BigDecimal("3.75"), "yx"}, result.rows().get(0));
        }
    }

    @Test
    void valuesThatDoNotFitTheParametersAreErrors() {
        PreparedQuery query = engine.prepare("SELECT x FROM (SELECT 'a' AS x) t WHERE x = ?");
        assertEquals("The query has 1 parameters, not 2 values", assertThrows(IllegalArgumentException.class,
                () -> engine.rows(query, List.of("a", "b"))).getMessage());
        assertEquals("Parameter 1: Not a VARCHAR: Integer 5", assertThrows(IllegalArgumentException.class,
                () -> engine.rows(query, List.of(5))).getMessage());
        assertEquals(List.of(), engine.rows(engine.prepare("SELECT 1 AS one WHERE false"), List.of()));
    }

    @Test
    void aQueryWithParametersNeedsPrepareAndTypesForThem() {
        SqlException e = assertThrows(SqlException.class, () -> engine.plan("SELECT 1 + ?"));
        assertEquals("Not supported: parameters outside a prepared query", e.reason());
        e = assertThrows(SqlException.class, () -> engine.prepare("SELECT 1, ?"));
        assertEquals("The type of parameter 1 cannot be inferred; write CAST(? AS type)", e.reason());
        assertEquals(11, e.position().column());
        e = assertThrows(SqlException.class, () -> engine.prepare(
                "SELECT m = ? FROM (SELECT map(['a'], [1]) AS m) t"));
        assertTrue(e.reason().startsWith("Not supported: parameter 1 of the type MAP(VARCHAR, INTEGER)"),
                e.reason());
        assertEquals(engine.catalog().searchPath(), engine.prepare("SELECT CAST(? AS INTEGER)").searchPath());
    }

    private static List<String> text(List<Object[]> rows) {
        List<String> result = new ArrayList<>();
        for (Object[] row : rows) {
            result.add(Arrays.toString(row));
        }
        return result;
    }
}
